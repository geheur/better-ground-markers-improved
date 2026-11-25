package com.rspaint;

import com.rspaint.RsPaintConfig.LineStyle;
import com.rspaint.RsPaintPlugin.Stroke;
import com.rspaint.RsPaintPlugin.StrokePoint;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Model;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.Scene;
import net.runelite.api.TextureProvider;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

// Strokes as flat ground models (GPU plugin or 117 HD), one set per zone
// Client thread only, except getModel of the scene objects
@Slf4j
@Singleton
class SceneStrokes
{
	// Width w is w * REFERENCE_DEPTH / REFERENCE_SCALE world units. Matches the overlay at the player with the camera
	// pitched up, a little wider than the overlay when pitched down
	static final float REFERENCE_DEPTH = 2800;
	static final float REFERENCE_SCALE = 512;

	// Points per mesh part, so a new point rebuilds only the last part
	private static final int CHUNK_POINTS = 48;
	// Mesh time per frame, except the stroke being drawn
	private static final long BUILD_BUDGET_NANOS = 3_000_000;
	// Unchanged frames before a transparent stroke gets a whole mesh
	private static final int STABLE_FRAMES = 30;
	// Unseen frames before a mesh is forgotten
	private static final int FORGET_FRAMES = 600;

	// Some tiles are not two triangles, so strokes stay a little above ground
	// A stroke is LIFT_STEP above the highest older stroke it crosses
	private static final float BASE_LIFT = 6;
	private static final float LIFT_STEP = 0.25f;
	private static final int MAX_LEVEL = 120;
	// Far opaque models sort by depth buffer, which cannot separate small steps
	private static final float FAR_BASE_LIFT = 12;
	private static final float FAR_LIFT_STEP = 1;

	// GPU plugin skips transparent models of diameter 6000 or more, bounds are about 2.83 times the radius
	private static final int RADIUS = 2000;

	// Objects stand only on scene tiles, so zones further out are beyond RADIUS
	private static final int OUTSIDE_ZONES = 1;

	// GPU plugin extended scene. Opaque models have no size limit, so they can stand far from their strokes
	private static final int FAR_ZONES = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2 / StrokeMesher.ZONE_TILES;
	private static final int FAR_RADIUS = 10_000;

	private static final int HIDDEN = 0, NEAR = 1, FAR = 2;

	private static final int MAX_SPARES = 64;
	// Renderer may still read a freed model
	private static final int SPARE_FRAMES = 2;

	private static final String HD_PLUGIN = "rs117.hd.HdPlugin";

	private final Client client;
	private final CarrierModels carriers;
	private final PluginManager pluginManager;
	private final ConfigManager configManager;

	private volatile ExecutorService worker;
	private int frame;
	// Bumped on scene load to rebuild meshes
	private int generation;
	private boolean active;
	// 117 HD caps color lightness, so light colors need a white layer. Null to re-read
	private Boolean lightnessCap;
	private boolean hdActive;

	private final Map<Stroke, Entry> entries = new IdentityHashMap<>();
	private final Map<Long, Zone> zones = new HashMap<>();
	private final Deque<Carrier> spares = new ArrayDeque<>();
	// Shown in the world this frame, and shown only there
	private final Set<Stroke> drawn = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<Stroke> drawnWhole = Collections.newSetFromMap(new IdentityHashMap<>());
	// Opaque strokes in drawn
	private final Set<Stroke> drawnFar = Collections.newSetFromMap(new IdentityHashMap<>());
	private boolean farZones;
	// Draw order, and last frame's list for levels
	private List<Entry> shown = new ArrayList<>();
	private List<Entry> lastShown = new ArrayList<>();
	private int[] levels = new int[64];
	private int[] lastVersions = new int[64];
	// Null to rebuild
	private RainbowLayers rainbowLayers;

	private WorldView worldView;
	private int baseX, baseY, sizeX, sizeY, plane;
	private int cameraTileX, cameraTileY, renderDistance;
	private final WorldGround ground = new WorldGround();

	@Inject
	SceneStrokes(Client client, CarrierModels carriers, PluginManager pluginManager, ConfigManager configManager)
	{
		this.client = client;
		this.carriers = carriers;
		this.pluginManager = pluginManager;
		this.configManager = configManager;
	}

	void startUp()
	{
		worker = Executors.newSingleThreadExecutor(r ->
		{
			Thread thread = new Thread(r, "RsPaint meshes");
			thread.setDaemon(true);
			return thread;
		});
		lightnessCap = null;
	}

	// Any thread, then clearAll on the client thread
	void shutDown()
	{
		ExecutorService w = worker;
		worker = null;
		if (w != null)
		{
			w.shutdownNow();
		}
	}

	void clearAll()
	{
		clear();
		active = false;
		entries.clear();
		spares.clear();
		carriers.reset();
	}

	boolean isActive()
	{
		return active;
	}

	boolean drew(Stroke stroke)
	{
		return active && drawn.contains(stroke);
	}

	boolean drewWhole(Stroke stroke)
	{
		return active && drawnWhole.contains(stroke);
	}

	boolean coversTile(Stroke stroke, int worldX, int worldY)
	{
		if (!active || !drawn.contains(stroke))
		{
			return false;
		}
		int reach = zoneReach(Math.floorDiv(worldX, StrokeMesher.ZONE_TILES), Math.floorDiv(worldY, StrokeMesher.ZONE_TILES));
		return reach == NEAR || reach == FAR && drawnFar.contains(stroke);
	}

	static double worldWidth(double width, boolean scaleWithZoom, int scale)
	{
		return width * REFERENCE_DEPTH / (scaleWithZoom || scale <= 0 ? REFERENCE_SCALE : scale);
	}

	void sceneChanged()
	{
		generation++;
		clear();
	}

	@Subscribe
	public void onPluginChanged(PluginChanged e)
	{
		lightnessCap = null;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if ("hd".equals(e.getGroup()))
		{
			lightnessCap = null;
		}
	}

	// Detected as HD World Markers does
	private boolean lightnessCap()
	{
		if (lightnessCap == null)
		{
			boolean hd = false;
			for (Plugin p : pluginManager.getPlugins())
			{
				if (p.getClass().getName().equals(HD_PLUGIN))
				{
					hd = pluginManager.isPluginActive(p);
					break;
				}
			}
			hdActive = hd;
			String shading = configManager.getConfiguration("hd", "experimentalShadingMode");
			lightnessCap = hd && (shading == null || "DEFAULT".equals(shading))
				&& !"true".equals(configManager.getConfiguration("hd", "legacyGreyColors"));
		}
		return lightnessCap;
	}

	// Game brightness for GPU, 1 for 117 HD. Call after lightnessCap
	private float gamma()
	{
		TextureProvider textures = hdActive ? null : client.getTextureProvider();
		return textures == null ? 1f : (float) textures.getBrightness();
	}

	// Strokes oldest to newest. Call before the scene draws
	void update(WorldView wv, List<Stroke> strokes, Stroke current, boolean scaleWithZoom, int scale, LineStyle style,
		boolean transparent, float opacity, int renderDistance)
	{
		if (wv == null || !client.isGpu() || worker == null)
		{
			off();
			return;
		}
		frame++;
		carriers.nextFrame();
		drawn.clear();
		drawnWhole.clear();
		drawnFar.clear();
		farZones = carriers.canMakeOpaque();
		if (wv != worldView || wv.getBaseX() != baseX || wv.getBaseY() != baseY || wv.getSizeX() != sizeX
			|| wv.getSizeY() != sizeY || wv.getPlane() != plane)
		{
			worldView = wv;
			baseX = wv.getBaseX();
			baseY = wv.getBaseY();
			sizeX = wv.getSizeX();
			sizeY = wv.getSizeY();
			plane = wv.getPlane();
			sceneChanged();
		}
		cameraTileX = baseX + (int) Math.floor(client.getCameraFpX() / StrokeMesher.TILE);
		cameraTileY = baseY + (int) Math.floor(client.getCameraFpY() / StrokeMesher.TILE);
		this.renderDistance = renderDistance;
		ground.set(wv, plane);

		long deadline = System.nanoTime() + BUILD_BUDGET_NANOS;
		List<Entry> now = lastShown;
		lastShown = shown;
		shown = now;
		shown.clear();
		for (Stroke s : strokes)
		{
			Entry e = entries.computeIfAbsent(s, k -> new Entry());
			e.seenFrame = frame;
			int alpha = transparent ? Math.round(s.color.getAlpha() * opacity) : 255;
			if (!e.update(s, worldWidth(s.strokeWidth, scaleWithZoom, scale), style, s.rainbow, alpha < 255 || s.rainbow,
				s == current ? Long.MAX_VALUE : deadline))
			{
				continue;
			}
			if (alpha <= 0)
			{
				// Fully transparent
				drawnWhole.add(s);
				continue;
			}
			if (alpha < 255 || s.rainbow)
			{
				// Crossing parts would flicker at one place and height
				e.wantWhole(s == current);
			}
			e.stroke = s;
			e.alpha = alpha;
			shown.add(e);
		}
		findLevels();
		boolean ok = assemble();
		if (!ok)
		{
			// Models not makeable yet, such as just after login
			clear();
			drawn.clear();
			drawnWhole.clear();
			drawnFar.clear();
			active = false;
			return;
		}
		active = true;
		if (frame % 64 == 0)
		{
			entries.values().removeIf(e -> frame - e.seenFrame > FORGET_FRAMES && e.forget());
		}
	}

	void off()
	{
		frame++;
		drawn.clear();
		drawnWhole.clear();
		drawnFar.clear();
		if (active || !zones.isEmpty())
		{
			clear();
		}
		active = false;
	}

	// Level is one above the highest older overlapping stroke. Recomputed from the first change on
	private void findLevels()
	{
		int n = shown.size();
		if (levels.length < n)
		{
			levels = java.util.Arrays.copyOf(levels, n * 2);
			lastVersions = java.util.Arrays.copyOf(lastVersions, n * 2);
		}
		int first = 0;
		while (first < n && first < lastShown.size() && shown.get(first) == lastShown.get(first)
			&& shown.get(first).version == lastVersions[first])
		{
			first++;
		}
		for (int j = first; j < n; j++)
		{
			Entry e = shown.get(j);
			int level = 0;
			for (int k = 0; k < j; k++)
			{
				Entry older = shown.get(k);
				if (levels[k] + older.levelCount() - 1 >= level && older.overlaps(e))
				{
					level = Math.min(MAX_LEVEL, levels[k] + older.levelCount());
				}
			}
			levels[j] = level;
			lastVersions[j] = e.version;
		}
	}

	private boolean isNear(int zoneX, int zoneY)
	{
		return inScene(zoneX, zoneY, OUTSIDE_ZONES);
	}

	private boolean inScene(int zoneX, int zoneY, int outsideZones)
	{
		int sceneX = zoneX * StrokeMesher.ZONE_TILES - baseX;
		int sceneY = zoneY * StrokeMesher.ZONE_TILES - baseY;
		int outside = outsideZones * StrokeMesher.ZONE_TILES;
		return sceneX >= -outside && sceneY >= -outside && sceneX < sizeX + outside && sceneY < sizeY + outside;
	}

	private int zoneReach(int zoneX, int zoneY)
	{
		int reach = isNear(zoneX, zoneY) ? NEAR : farZones && inScene(zoneX, zoneY, FAR_ZONES) ? FAR : HIDDEN;
		if (reach == HIDDEN || renderDistance <= 0)
		{
			return reach;
		}
		// Square around the camera tile like the overlay
		int minX = zoneX * StrokeMesher.ZONE_TILES, minY = zoneY * StrokeMesher.ZONE_TILES;
		int maxX = minX + StrokeMesher.ZONE_TILES - 1, maxY = minY + StrokeMesher.ZONE_TILES - 1;
		return maxX >= cameraTileX - renderDistance && minX <= cameraTileX + renderDistance
			&& maxY >= cameraTileY - renderDistance && minY <= cameraTileY + renderDistance ? reach : HIDDEN;
	}

	private static boolean shows(int reach, boolean opaque)
	{
		return reach == NEAR || reach == FAR && opaque;
	}

	// False on failure
	private boolean assemble()
	{
		boolean cap = lightnessCap();
		float gamma = gamma();
		for (int j = 0; j < shown.size(); j++)
		{
			Entry e = shown.get(j);
			int[] layers = e.layers(cap, gamma);
			RainbowLayers rainbow = e.stroke.rainbow ? rainbowLayers(e.alpha, cap, gamma) : null;
			boolean opaque = rainbow != null ? rainbow.opaque : layers[1] >= 255 && layers[2] <= 0;
			boolean whole = true;
			List<StrokeMesher.Mesh> meshes = e.meshes();
			boolean tail = e.hasTail();
			for (int m = 0; m < meshes.size(); m++)
			{
				// Newest points sit one level over the older mesh
				int level = tail && m > 0 ? Math.min(MAX_LEVEL, levels[j] + 1) : levels[j];
				for (StrokeMesher.Piece piece : meshes.get(m).pieces)
				{
					if (!shows(zoneReach(piece.zoneX, piece.zoneY), opaque))
					{
						whole = false;
						continue;
					}
					long key = (long) piece.zoneX << 32 | (piece.zoneY & 0xFFFFFFFFL);
					Zone zone = zones.get(key);
					if (zone == null)
					{
						zone = new Zone(piece.zoneX, piece.zoneY);
						zones.put(key, zone);
					}
					zone.add(frame, piece, level, layers[0], layers[1], layers[2], piece.distances != null ? rainbow : null);
				}
			}
			// Tiles past modeled zones show over the game
			whole = whole && boxShown(e, opaque);
			drawn.add(e.stroke);
			if (opaque)
			{
				drawnFar.add(e.stroke);
			}
			if (whole)
			{
				drawnWhole.add(e.stroke);
			}
		}

		boolean ok = true;
		for (Iterator<Zone> it = zones.values().iterator(); it.hasNext(); )
		{
			Zone zone = it.next();
			if (zone.frame != frame)
			{
				zone.retire();
				it.remove();
				continue;
			}
			if (zone.hash != zone.writtenHash)
			{
				if (!zone.write())
				{
					ok = false;
					break;
				}
				zone.writtenHash = zone.hash;
			}
			zone.register();
		}
		return ok;
	}

	private RainbowLayers rainbowLayers(int alpha, boolean cap, float gamma)
	{
		RainbowLayers r = rainbowLayers;
		if (r == null || r.alpha != alpha || r.cap != cap || Float.compare(r.gamma, gamma) != 0)
		{
			r = new RainbowLayers(alpha, cap, gamma);
			rainbowLayers = r;
		}
		return r;
	}

	private static final class RainbowLayers
	{
		final int alpha;
		final boolean cap;
		final float gamma;
		final int[] hsl = new int[Rainbow.STEPS], colorAlpha = new int[Rainbow.STEPS], white = new int[Rainbow.STEPS];
		final boolean hasWhite;
		final boolean opaque;
		final long key;

		RainbowLayers(int alpha, boolean cap, float gamma)
		{
			this.alpha = alpha;
			this.cap = cap;
			this.gamma = gamma;
			int[] layers = new int[3];
			boolean anyWhite = false, allOpaque = true;
			for (int i = 0; i < Rainbow.STEPS; i++)
			{
				FlatModel.layers(Rainbow.color(i), alpha, cap, gamma, layers);
				hsl[i] = layers[0];
				colorAlpha[i] = layers[1];
				white[i] = layers[2];
				anyWhite |= layers[2] > 0;
				allOpaque &= layers[1] >= 255 && layers[2] <= 0;
			}
			hasWhite = anyWhite;
			opaque = allOpaque;
			key = (long) Float.floatToIntBits(gamma) << 32 | alpha << 1 | (cap ? 1 : 0);
		}
	}

	private boolean boxShown(Entry e, boolean opaque)
	{
		int minX = Math.floorDiv((int) Math.floor(e.minX / StrokeMesher.TILE), StrokeMesher.ZONE_TILES);
		int maxX = Math.floorDiv((int) Math.floor(e.maxX / StrokeMesher.TILE), StrokeMesher.ZONE_TILES);
		int minY = Math.floorDiv((int) Math.floor(e.minY / StrokeMesher.TILE), StrokeMesher.ZONE_TILES);
		int maxY = Math.floorDiv((int) Math.floor(e.maxY / StrokeMesher.TILE), StrokeMesher.ZONE_TILES);
		return shows(zoneReach(minX, minY), opaque) && shows(zoneReach(maxX, maxY), opaque);
	}

	void clear()
	{
		for (Zone zone : zones.values())
		{
			zone.retire();
		}
		zones.clear();
		shown.clear();
		lastShown.clear();
	}

	private static final class Carrier
	{
		final Model model;
		// FAR_RADIUS bounds, else RADIUS
		final boolean opaque;
		int usedFaces, usedVertices;
		int freedFrame;

		Carrier(Model model, boolean opaque)
		{
			this.model = model;
			this.opaque = opaque;
		}
	}

	// Null if none can be made now
	private Carrier obtain(int vertices, int faces, boolean opaque)
	{
		Carrier best = null;
		for (Carrier c : spares)
		{
			// Much larger models cost time on hidden faces
			if (c.opaque == opaque && frame - c.freedFrame >= SPARE_FRAMES && c.model.getVerticesCount() >= vertices && c.model.getFaceCount() >= faces
				&& c.model.getFaceCount() <= Math.max(faces * 4, 600)
				&& (best == null || c.model.getFaceCount() < best.model.getFaceCount()))
			{
				best = c;
			}
		}
		if (best != null)
		{
			spares.remove(best);
			return best;
		}
		// Room to grow while drawing
		int wantVertices = Math.min(CarrierModels.MAX_VERTICES - 6, Math.max(128, vertices * 3 / 2));
		int wantFaces = Math.min(CarrierModels.MAX_FACES, Math.max(96, faces * 3 / 2));
		if (!carriers.fits(wantVertices, wantFaces))
		{
			wantVertices = vertices;
			wantFaces = faces;
		}
		Model model = carriers.create(wantVertices, wantFaces, opaque ? FAR_RADIUS : RADIUS, !opaque);
		if (model == null)
		{
			return null;
		}
		// Normals up, so 117 HD lights like flat ground
		FlatModel.normals(model);
		Carrier carrier = new Carrier(model, opaque);
		// Six vertices fix the bounds
		carrier.usedVertices = 6;
		return carrier;
	}

	private void free(Carrier c)
	{
		if (c != null && spares.size() < MAX_SPARES)
		{
			c.freedFrame = frame;
			spares.addLast(c);
		}
	}

	private final class Zone
	{
		final int zoneX, zoneY;
		final boolean far;
		int frame;
		// Draw order
		StrokeMesher.Piece[] pieces = new StrokeMesher.Piece[16];
		float[] lifts = new float[16];
		int[] colors = new int[16], alphas = new int[16], whites = new int[16];
		RainbowLayers[] rainbows = new RainbowLayers[16];
		int items;
		long hash, writtenHash;
		final List<Part> parts = new ArrayList<>();

		Zone(int zoneX, int zoneY)
		{
			this.zoneX = zoneX;
			this.zoneY = zoneY;
			far = !isNear(zoneX, zoneY);
		}

		void add(int frame, StrokeMesher.Piece piece, int level, int color, int alpha, int white, RainbowLayers rainbow)
		{
			float lift = far ? FAR_BASE_LIFT + level * FAR_LIFT_STEP : BASE_LIFT + level * LIFT_STEP;
			if (this.frame != frame)
			{
				this.frame = frame;
				items = 0;
				hash = 17;
			}
			if (items == pieces.length)
			{
				int n = items * 2;
				pieces = java.util.Arrays.copyOf(pieces, n);
				lifts = java.util.Arrays.copyOf(lifts, n);
				colors = java.util.Arrays.copyOf(colors, n);
				alphas = java.util.Arrays.copyOf(alphas, n);
				whites = java.util.Arrays.copyOf(whites, n);
				rainbows = java.util.Arrays.copyOf(rainbows, n);
			}
			pieces[items] = piece;
			lifts[items] = lift;
			colors[items] = color;
			alphas[items] = alpha;
			whites[items] = white;
			rainbows[items] = rainbow;
			items++;
			hash = mix(hash, piece.id);
			hash = mix(hash, Float.floatToIntBits(lift));
			hash = mix(hash, rainbow != null ? rainbow.key : (long) color << 16 | alpha << 8 | white);
		}

		// Scratch for one model
		private float[] vx = new float[256], vy = new float[256], vz = new float[256];
		private int[] fa = new int[256], fb = new int[256], fc = new int[256], fAlpha = new int[256];
		private int[] fColorA = new int[256], fColorB = new int[256], fColorC = new int[256];
		private int vertices, faces;

		// False if a model cannot be made
		boolean write()
		{
			int part = 0;
			vertices = 0;
			faces = 0;
			float originX = (zoneX * StrokeMesher.ZONE_TILES - baseX) * (float) StrokeMesher.TILE;
			float originY = (zoneY * StrokeMesher.ZONE_TILES - baseY) * (float) StrokeMesher.TILE;
			for (int i = 0; i < items; i++)
			{
				StrokeMesher.Piece piece = pieces[i];
				RainbowLayers rainbow = rainbows[i];
				boolean white = rainbow != null ? rainbow.hasWhite : whites[i] > 0;
				int layerCount = white ? 2 : 1;
				int first = 0;
				for (int k = 0; k < piece.polygons; k++)
				{
					int n = piece.sizes[k];
					if (vertices > 0 && !carriers.fits(vertices + n * layerCount, faces + (n - 2) * layerCount))
					{
						if (!flush(part++))
						{
							return false;
						}
					}
					polygon(piece, first, n, originX, originY, lifts[i], colors[i], alphas[i], rainbow, false);
					if (white)
					{
						polygon(piece, first, n, originX, originY, lifts[i] + LIFT_STEP / 2, FlatModel.WHITE, whites[i], rainbow, true);
					}
					first += n;
				}
			}
			if (vertices > 0 && !flush(part++))
			{
				return false;
			}
			while (parts.size() > part)
			{
				parts.remove(parts.size() - 1).retire();
			}
			return true;
		}

		// Faces take the mean alpha of their rainbow vertices
		private void polygon(StrokeMesher.Piece piece, int first, int n, float originX, float originY, float lift, int color, int alpha,
			RainbowLayers rainbow, boolean whiteLayer)
		{
			ensure(vertices + n, faces + n - 2);
			double area = 0;
			for (int i = 0; i < n; i++)
			{
				int a = first + i, b = first + (i + 1) % n;
				area += piece.x[a] * piece.y[b] - piece.x[b] * piece.y[a];
				vx[vertices + i] = originX + piece.x[a];
				vy[vertices + i] = originY + piece.y[a];
				vz[vertices + i] = piece.z[a] - lift;
			}
			// Back faces are culled. GPU plugin draws (a - b) x (c - b) > 0 on the canvas (y down), as ground tiles wind
			boolean clockwise = area < 0;
			for (int j = 1; j < n - 1; j++)
			{
				int b = clockwise ? j + 1 : j;
				int c = clockwise ? j : j + 1;
				fa[faces] = vertices;
				fb[faces] = vertices + b;
				fc[faces] = vertices + c;
				if (rainbow == null)
				{
					fColorA[faces] = fColorB[faces] = fColorC[faces] = color;
					fAlpha[faces] = alpha;
				}
				else
				{
					int sa = Rainbow.step(piece.distances[first]);
					int sb = Rainbow.step(piece.distances[first + b]);
					int sc = Rainbow.step(piece.distances[first + c]);
					int[] alphas = whiteLayer ? rainbow.white : rainbow.colorAlpha;
					fColorA[faces] = whiteLayer ? FlatModel.WHITE : rainbow.hsl[sa];
					fColorB[faces] = whiteLayer ? FlatModel.WHITE : rainbow.hsl[sb];
					fColorC[faces] = whiteLayer ? FlatModel.WHITE : rainbow.hsl[sc];
					fAlpha[faces] = (alphas[sa] + alphas[sb] + alphas[sc] + 1) / 3;
				}
				faces++;
			}
			vertices += n;
		}

		private void ensure(int vertexCount, int faceCount)
		{
			if (vx.length < vertexCount)
			{
				int n = vertexCount * 2;
				vx = java.util.Arrays.copyOf(vx, n);
				vy = java.util.Arrays.copyOf(vy, n);
				vz = java.util.Arrays.copyOf(vz, n);
			}
			if (fa.length < faceCount)
			{
				int n = faceCount * 2;
				fa = java.util.Arrays.copyOf(fa, n);
				fb = java.util.Arrays.copyOf(fb, n);
				fc = java.util.Arrays.copyOf(fc, n);
				fColorA = java.util.Arrays.copyOf(fColorA, n);
				fColorB = java.util.Arrays.copyOf(fColorB, n);
				fColorC = java.util.Arrays.copyOf(fColorC, n);
				fAlpha = java.util.Arrays.copyOf(fAlpha, n);
			}
		}

		// Empties the scratch
		private boolean flush(int index)
		{
			if (parts.size() <= index)
			{
				parts.add(new Part());
			}
			Part part = parts.get(index);
			// Own tile per model, as the client draws only a few objects per tile
			int tileX = anchorTile(zoneX * StrokeMesher.ZONE_TILES - baseX, index % StrokeMesher.ZONE_TILES, sizeX, far);
			int tileY = anchorTile(zoneY * StrokeMesher.ZONE_TILES - baseY, index / StrokeMesher.ZONE_TILES % StrokeMesher.ZONE_TILES, sizeY, far);
			int anchorX = tileX * StrokeMesher.TILE + StrokeMesher.TILE / 2;
			int anchorY = tileY * StrokeMesher.TILE + StrokeMesher.TILE / 2;
			int radius = far ? FAR_RADIUS : RADIUS;
			double sum = 0;
			for (int i = 0; i < vertices; i++)
			{
				sum += vz[i];
			}
			int anchorZ = (int) Math.round(sum / vertices);
			for (int i = 0; i < vertices; i++)
			{
				if (Math.abs(vx[i] - anchorX) >= radius || Math.abs(vy[i] - anchorY) >= radius || Math.abs(vz[i] - anchorZ) >= radius)
				{
					// Not expected, maybe a bad height
					log.debug("Stroke geometry of zone {},{} is out of the model bounds", zoneX, zoneY);
					vertices = 0;
					faces = 0;
					return true;
				}
			}
			Carrier carrier = obtain(vertices, faces, far);
			if (carrier == null)
			{
				vertices = 0;
				faces = 0;
				return false;
			}
			Model m = carrier.model;
			float[] mx = m.getVerticesX(), my = m.getVerticesY(), mz = m.getVerticesZ();
			for (int i = 0; i < vertices; i++)
			{
				// Model axes: x east, y down, z north
				mx[i] = vx[i] - anchorX;
				my[i] = vz[i] - anchorZ;
				mz[i] = vy[i] - anchorY;
			}
			for (int i = vertices; i < carrier.usedVertices; i++)
			{
				mx[i] = 0;
				my[i] = 0;
				mz[i] = 0;
			}
			carrier.usedVertices = vertices;
			int[] i1 = m.getFaceIndices1(), i2 = m.getFaceIndices2(), i3 = m.getFaceIndices3();
			for (int f = 0; f < faces; f++)
			{
				i1[f] = fa[f];
				i2[f] = fb[f];
				i3[f] = fc[f];
				FlatModel.paint(m, f, fColorA[f], fColorB[f], fColorC[f], fAlpha[f]);
			}
			for (int f = faces; f < carrier.usedFaces; f++)
			{
				FlatModel.hide(m, f);
			}
			carrier.usedFaces = faces;
			FlatModel.bounds(m);

			RuneLiteObjectController object = part.object;
			object.setX(anchorX);
			object.setY(anchorY);
			object.setZ(anchorZ);
			object.setLevel(plane);
			object.setWorldView(worldView.getId());
			free(part.carrier);
			part.carrier = carrier;
			part.model = m;
			vertices = 0;
			faces = 0;
			return true;
		}

		void register()
		{
			for (Part part : parts)
			{
				if (part.model != null && !client.isRuneLiteObjectRegistered(part.object))
				{
					client.registerRuneLiteObject(part.object);
				}
			}
		}

		void retire()
		{
			for (Part part : parts)
			{
				part.retire();
			}
			parts.clear();
			writtenHash = 0;
		}
	}

	private final class Part
	{
		// Renderer may read on another thread
		volatile Model model;
		Carrier carrier;
		final RuneLiteObjectController object = new RuneLiteObjectController()
		{
			@Override
			public Model getModel()
			{
				return model;
			}
		};

		void retire()
		{
			if (client.isRuneLiteObjectRegistered(object))
			{
				client.removeRuneLiteObject(object);
			}
			model = null;
			free(carrier);
			carrier = null;
		}
	}

	// Zones past the edge use the edge tile, far zones step one tile in per zone out so a row does not share a tile
	static int anchorTile(int start, int offset, int size, boolean far)
	{
		int tile = start + offset;
		if (tile >= 0 && tile < size)
		{
			return tile;
		}
		if (!far)
		{
			return Math.max(0, Math.min(size - 1, tile));
		}
		// 1 for the first zone past the edge
		int out = start < 0 ? -Math.floorDiv(start, StrokeMesher.ZONE_TILES) : (start - size) / StrokeMesher.ZONE_TILES + 1;
		int in = Math.min(size - 1, out - 1);
		return start < 0 ? in : size - 1 - in;
	}

	private static long mix(long hash, long value)
	{
		hash ^= value + 0x9E3779B97F4A7C15L + (hash << 6) + (hash >>> 2);
		return hash * 0xBF58476D1CE4E5B9L;
	}

	private final class Entry
	{
		Stroke stroke;
		int alpha;
		int seenFrame;
		int version;

		private List<StrokePoint> pts;
		private int size;
		private double width;
		private LineStyle style;
		private boolean rainbow;
		private int generation = -1;
		private int changedFrame;

		// Complete when all points have parts
		private final List<StrokeMesher.Mesh> chunks = new ArrayList<>();
		private boolean complete;
		// Parts overlap at joins and darken when transparent. While drawing, covers only the first wholeSize points
		private StrokeMesher.Mesh whole;
		private int wholeSize;
		private int pendingSize;
		// Shown until new meshes are ready, to avoid flicker
		private List<StrokeMesher.Mesh> stale;
		private boolean staleTail;
		private boolean wantsWhole;
		private Future<StrokeMesher.Mesh> pending;
		double minX, minY, maxX, maxY;
		// Cache of FlatModel.layers
		private final int[] layers = new int[3];
		private Color layersColor;
		private int layersAlpha = -1;
		private boolean layersCap;
		private float layersGamma = Float.NaN;

		int[] layers(boolean cap, float gamma)
		{
			if (stroke.color != layersColor || alpha != layersAlpha || cap != layersCap || Float.compare(gamma, layersGamma) != 0)
			{
				FlatModel.layers(stroke.color, alpha, cap, gamma, layers);
				layersColor = stroke.color;
				layersAlpha = alpha;
				layersCap = cap;
				layersGamma = gamma;
			}
			return layers;
		}

		// True if the stroke has meshes to show
		boolean update(Stroke s, double width, LineStyle style, boolean rainbow, boolean wantsWhole, long deadline)
		{
			this.wantsWhole = wantsWhole;
			if (s.pts != pts || width != this.width || style != this.style || rainbow != this.rainbow
				|| generation != SceneStrokes.this.generation || s.pts.size() < size)
			{
				// Old meshes stay roughly right, so show them meanwhile
				boolean keep = generation == SceneStrokes.this.generation && (s.pts != pts || s.pts.size() == size);
				if (!keep)
				{
					stale = null;
				}
				else if (complete)
				{
					stale = new ArrayList<>(meshes());
					staleTail = hasTail();
				}
				pts = s.pts;
				this.width = width;
				this.style = style;
				this.rainbow = rainbow;
				generation = SceneStrokes.this.generation;
				size = 0;
				chunks.clear();
				changed();
			}
			int n = pts.size();
			if (n != size)
			{
				// Keep full parts whose points and neighbors existed
				int keep = 0;
				while (keep < chunks.size() && (keep + 1) * CHUNK_POINTS + 1 <= size - 1)
				{
					keep++;
				}
				while (chunks.size() > keep)
				{
					chunks.remove(chunks.size() - 1);
				}
				size = n;
				// Whole mesh still fits the older points
				complete = false;
				changedFrame = frame;
			}
			if (!complete)
			{
				while (true)
				{
					int from = chunks.size() * CHUNK_POINTS;
					if (n == 0 || (n == 1 ? !chunks.isEmpty() : from >= n - 1))
					{
						break;
					}
					if (System.nanoTime() > deadline)
					{
						return stale != null;
					}
					StrokeMesher.Mesh mesh = StrokeMesher.build(pts, from, Math.min(from + CHUNK_POINTS, n - 1), width, style, ground, rainbow);
					if (mesh == null)
					{
						stale = null;
						return false;
					}
					chunks.add(mesh);
				}
				complete = true;
				if (!waitsForWhole())
				{
					stale = null;
				}
				box(chunks);
				version++;
			}
			if (pending != null && pending.isDone())
			{
				try
				{
					StrokeMesher.Mesh made = pending.get();
					if (made != null)
					{
						whole = made;
						wholeSize = pendingSize;
						if (wholeSize >= size)
						{
							box(Collections.singletonList(whole));
							stale = null;
						}
						version++;
					}
				}
				catch (Exception ex)
				{
					log.debug("Cannot make the mesh of a stroke", ex);
				}
				pending = null;
			}
			return complete && n > 0;
		}

		private void changed()
		{
			complete = false;
			whole = null;
			if (pending != null)
			{
				pending.cancel(false);
				pending = null;
			}
			changedFrame = frame;
		}

		private void box(List<StrokeMesher.Mesh> meshes)
		{
			minX = minY = Double.MAX_VALUE;
			maxX = maxY = -Double.MAX_VALUE;
			for (StrokeMesher.Mesh m : meshes)
			{
				minX = Math.min(minX, m.minX);
				minY = Math.min(minY, m.minY);
				maxX = Math.max(maxX, m.maxX);
				maxY = Math.max(maxY, m.maxY);
			}
		}

		boolean overlaps(Entry o)
		{
			return minX <= o.maxX && o.minX <= maxX && minY <= o.maxY && o.minY <= maxY;
		}

		List<StrokeMesher.Mesh> meshes()
		{
			if (showsStale())
			{
				return stale;
			}
			if (!complete || whole == null)
			{
				return chunks;
			}
			if (!hasTail())
			{
				return Collections.singletonList(whole);
			}
			// Mesh of older points, then parts from its last segment
			List<StrokeMesher.Mesh> list = new ArrayList<>();
			list.add(whole);
			list.addAll(chunks.subList(Math.min(chunks.size() - 1, (wholeSize - 1) / CHUNK_POINTS), chunks.size()));
			return list;
		}

		// Older-points mesh plus newer parts one level higher
		boolean hasTail()
		{
			if (showsStale())
			{
				return staleTail;
			}
			return complete && whole != null && wholeSize < size && !chunks.isEmpty();
		}

		private boolean showsStale()
		{
			return stale != null && (!complete || waitsForWhole());
		}

		private boolean waitsForWhole()
		{
			return wantsWhole && chunks.size() > 1 && worker != null && (whole == null || wholeSize < size);
		}

		int levelCount()
		{
			return hasTail() ? 2 : 1;
		}

		// Worker builds once unchanged for STABLE_FRAMES, or repeatedly while drawing. Ground is copied, worker must not read the scene
		void wantWhole(boolean drawing)
		{
			// Stale meshes need the new one at once
			if (!complete || pending != null || chunks.size() <= 1 || worker == null || whole != null && wholeSize >= size
				|| !drawing && stale == null && frame - changedFrame < STABLE_FRAMES)
			{
				return;
			}
			pendingSize = size;
			List<StrokePoint> points = new ArrayList<>(pts.subList(0, size));
			double w = width;
			LineStyle st = style;
			boolean r = rainbow;
			StrokeMesher.Ground heights = new GroundCopy(ground,
				(int) Math.floor(minX / StrokeMesher.TILE) - 1, (int) Math.floor(minY / StrokeMesher.TILE) - 1,
				(int) Math.floor(maxX / StrokeMesher.TILE) + 1, (int) Math.floor(maxY / StrokeMesher.TILE) + 1);
			ExecutorService executor = worker;
			if (executor != null)
			{
				pending = executor.submit(() -> StrokeMesher.build(points, 0, points.size() - 1, w, st, heights, r));
			}
		}

		boolean forget()
		{
			if (pending != null)
			{
				pending.cancel(false);
			}
			return true;
		}
	}

	private static final class WorldGround implements StrokeMesher.Ground
	{
		private WorldView wv;
		private int plane;

		void set(WorldView wv, int plane)
		{
			this.wv = wv;
			this.plane = plane;
		}

		@Override
		public boolean corners(int worldX, int worldY, int[] out)
		{
			int sceneX = worldX - wv.getBaseX();
			int sceneY = worldY - wv.getBaseY();
			int[][][] heights;
			byte[][][] settings;
			int x, y;
			if (sceneX >= 0 && sceneY >= 0 && sceneX < wv.getSizeX() && sceneY < wv.getSizeY())
			{
				heights = wv.getTileHeights();
				settings = wv.getTileSettings();
				x = sceneX;
				y = sceneY;
			}
			else
			{
				// Extended scene, loaded tiles only
				Scene scene = wv.getScene();
				Tile[][][] tiles = scene != null ? scene.getExtendedTiles() : null;
				heights = scene != null ? scene.getTileHeights() : null;
				settings = scene != null ? scene.getExtendedTileSettings() : null;
				if (tiles == null || heights == null || heights[0].length <= wv.getSizeX() + 1)
				{
					return false;
				}
				int border = (heights[0].length - 1 - wv.getSizeX()) / 2;
				x = sceneX + border;
				y = sceneY + border;
				if (plane < 0 || plane >= tiles.length || x < 0 || y < 0 || x >= tiles[plane].length
					|| y >= tiles[plane][x].length || tiles[plane][x][y] == null)
				{
					return false;
				}
			}
			if (heights == null || plane < 0 || plane >= Constants.MAX_Z)
			{
				return false;
			}
			// Bridge tiles use ground one level up
			int level = plane;
			if (plane < 3 && settings != null && x < settings[1].length && y < settings[1][x].length && (settings[1][x][y] & 2) != 0)
			{
				level = plane + 1;
			}
			int[][] h = heights[level];
			if (x + 1 >= h.length || y + 1 >= h[x].length)
			{
				return false;
			}
			out[0] = h[x][y];
			out[1] = h[x + 1][y];
			out[2] = h[x + 1][y + 1];
			out[3] = h[x][y + 1];
			return true;
		}
	}

	private static final class GroundCopy implements StrokeMesher.Ground
	{
		private final int minX, minY, width, height;
		private final int[] corners;
		private final boolean[] loaded;

		GroundCopy(StrokeMesher.Ground ground, int minX, int minY, int maxX, int maxY)
		{
			this.minX = minX;
			this.minY = minY;
			width = maxX - minX + 1;
			height = maxY - minY + 1;
			corners = new int[width * height * 4];
			loaded = new boolean[width * height];
			int[] out = new int[4];
			for (int x = 0; x < width; x++)
			{
				for (int y = 0; y < height; y++)
				{
					int i = x * height + y;
					if (ground.corners(minX + x, minY + y, out))
					{
						loaded[i] = true;
						System.arraycopy(out, 0, corners, i * 4, 4);
					}
				}
			}
		}

		@Override
		public boolean corners(int worldX, int worldY, int[] out)
		{
			int x = worldX - minX;
			int y = worldY - minY;
			if (x < 0 || y < 0 || x >= width || y >= height || !loaded[x * height + y])
			{
				return false;
			}
			System.arraycopy(corners, (x * height + y) * 4, out, 0, 4);
			return true;
		}
	}
}

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
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/**
 * Draws the strokes as part of the game world, with the GPU plugin or 117 HD. The strokes are flat models on the
 * ground, so the renderer hides them behind everything that is nearer the camera: the player, other players, NPCs,
 * objects, walls and projectiles.
 *
 * <p>Each stroke becomes polygons on the ground (StrokeMesher), with the width in world units. The polygons of all
 * strokes in one zone (8 by 8 tiles) go into models of that zone, which are written again only when a stroke in the zone
 * changes. Newer strokes are a little higher than the older strokes that they cross, so they show on top.
 *
 * <p>Client thread only, except getModel of the scene objects.
 */
@Slf4j
@Singleton
class SceneStrokes
{
	/**
	 * A stroke with the stored width w is w * REFERENCE_DEPTH / REFERENCE_SCALE world units wide. Thus at the default zoom
	 * of fixed mode (scale 512), a stroke is w pixels wide where the ground is REFERENCE_DEPTH from the camera, which is
	 * about the distance of the player.
	 */
	static final float REFERENCE_DEPTH = 1200;
	static final float REFERENCE_SCALE = 512;

	/**
	 * A stroke is made in parts of this many points, so a new point makes only the last part again. A part that a new
	 * point cannot change is kept. Transparent strokes get one mesh of all points later (see Entry.whole).
	 */
	private static final int CHUNK_POINTS = 48;
	// Making meshes takes at most this much time in each frame. Strokes that are not made yet show over the game.
	// The stroke that the player draws is always made.
	private static final long BUILD_BUDGET_NANOS = 3_000_000;
	// A transparent stroke gets one mesh of all its points after it stays the same for this many frames.
	private static final int STABLE_FRAMES = 30;
	// The mesh of a stroke that did not show for this many frames is forgotten.
	private static final int FORGET_FRAMES = 600;

	/*
	 * The height of the strokes over the ground. The game draws a tile as two triangles, but some tiles have other
	 * shapes, so the strokes stay a little above the ground. Each stroke that crosses older strokes is LIFT_STEP higher
	 * than the highest of them, so the newer stroke shows on top. The white layer of 117 HD is half a step higher.
	 */
	private static final float BASE_LIFT = 6;
	private static final float LIFT_STEP = 0.25f;
	private static final int MAX_LEVEL = 120;

	/*
	 * The bounds radius of all models. The GPU plugin does not draw a transparent model with a diameter of 6000 or more,
	 * and the bounds are about 2.83 times the radius across. A zone is 1024 units across, and its models stand on a tile
	 * in the scene, also for the zones just past the scene edge.
	 */
	private static final int RADIUS = 2000;

	// The zones past each edge of the scene that get models. The models stand on the nearest scene tile, so a zone
	// further out is too far from them for the radius. Strokes there show over the game.
	private static final int OUTSIDE_ZONES = 1;

	// Spare models that are kept for later zones.
	private static final int MAX_SPARES = 64;
	// A model that a zone gave up is not written again for this many frames, because a renderer can still read it.
	private static final int SPARE_FRAMES = 2;

	private static final String HD_PLUGIN = "rs117.hd.HdPlugin";

	private final Client client;
	private final CarrierModels carriers;
	private final PluginManager pluginManager;
	private final ConfigManager configManager;

	private volatile ExecutorService worker;
	private int frame;
	// Changes when the scene loads, so the meshes are made again with the new ground heights.
	private int generation;
	private boolean active;
	// Whether 117 HD caps the lightness of colors, so light colors need a white layer. Null to read it again.
	private Boolean lightnessCap;

	private final Map<Stroke, Entry> entries = new IdentityHashMap<>();
	private final Map<Long, Zone> zones = new HashMap<>();
	private final Deque<Carrier> spares = new ArrayDeque<>();
	// The strokes that show in the world in this frame, and those that show only in the world.
	private final Set<Stroke> drawn = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<Stroke> drawnWhole = Collections.newSetFromMap(new IdentityHashMap<>());
	// The strokes in this frame that have meshes, in draw order, and their levels. The last frame's for levels().
	private List<Entry> shown = new ArrayList<>();
	private List<Entry> lastShown = new ArrayList<>();
	private int[] levels = new int[64];
	private int[] lastVersions = new int[64];

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

	/** Stops the worker thread. Any thread. Call clearAll on the client thread after it. */
	void shutDown()
	{
		ExecutorService w = worker;
		worker = null;
		if (w != null)
		{
			w.shutdownNow();
		}
	}

	/** Takes all models out of the scene and forgets all meshes and models. Client thread only. */
	void clearAll()
	{
		clear();
		active = false;
		entries.clear();
		spares.clear();
		carriers.reset();
	}

	/** Returns true if the strokes are in the world in this frame. If false, the overlay draws them all. */
	boolean isActive()
	{
		return active;
	}

	/** Returns true if the stroke shows in the world in this frame. Its parts on tiles that coversTile rejects do not. */
	boolean drew(Stroke stroke)
	{
		return active && drawn.contains(stroke);
	}

	/** Returns true if the stroke shows only in the world in this frame, so the overlay does not draw any part of it. */
	boolean drewWhole(Stroke stroke)
	{
		return active && drawnWhole.contains(stroke);
	}

	/** Returns true if the world tile is drawn in the world in this frame, for strokes for which drew is true. */
	boolean coversTile(int worldX, int worldY)
	{
		return active && zoneShown(Math.floorDiv(worldX, StrokeMesher.ZONE_TILES), Math.floorDiv(worldY, StrokeMesher.ZONE_TILES));
	}

	/** Returns the width in world units of a stroke with this stored width. */
	static double worldWidth(double width, boolean scaleWithZoom, int scale)
	{
		return width * REFERENCE_DEPTH / (scaleWithZoom || scale <= 0 ? REFERENCE_SCALE : scale);
	}

	/** The scene loads or changes, so the ground heights can be new. */
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

	/** Returns true if 117 HD is on and caps the lightness of colors, as HD World Markers finds it. */
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
			String shading = configManager.getConfiguration("hd", "experimentalShadingMode");
			lightnessCap = hd && (shading == null || "DEFAULT".equals(shading))
				&& !"true".equals(configManager.getConfiguration("hd", "legacyGreyColors"));
		}
		return lightnessCap;
	}

	/**
	 * Puts the strokes in the world for the next frame. The strokes are in draw order, from the oldest to the newest.
	 * Call it before the scene draws. Strokes that cannot show in the world in this frame show over the game.
	 */
	void update(WorldView wv, List<Stroke> strokes, Stroke current, double worldPerWidth, LineStyle style,
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
			if (!e.update(s, s.strokeWidth * worldPerWidth, style, s == current ? Long.MAX_VALUE : deadline))
			{
				continue;
			}
			int alpha = transparent ? Math.round(s.color.getAlpha() * opacity) : 255;
			if (alpha <= 0)
			{
				// Nothing to draw, in the world or over it.
				drawnWhole.add(s);
				continue;
			}
			if (alpha < 255)
			{
				e.wantWhole();
			}
			e.stroke = s;
			e.alpha = alpha;
			shown.add(e);
		}
		findLevels();
		boolean ok = assemble();
		if (!ok)
		{
			// The models cannot be made yet, for example just after login. The overlay draws all strokes.
			clear();
			drawn.clear();
			drawnWhole.clear();
			active = false;
			return;
		}
		active = true;
		if (frame % 64 == 0)
		{
			entries.values().removeIf(e -> frame - e.seenFrame > FORGET_FRAMES && e.forget());
		}
	}

	/** Shows no strokes in the world in the next frame, so the overlay draws them all. */
	void off()
	{
		frame++;
		drawn.clear();
		drawnWhole.clear();
		if (active || !zones.isEmpty())
		{
			clear();
		}
		active = false;
	}

	/**
	 * Gives each shown stroke a level: one more than the highest level of the older shown strokes that its box
	 * overlaps, or 0. Only the strokes from the first change since the last frame get new levels, so a stroke that
	 * the player draws costs one pass over the strokes.
	 */
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
				if (levels[k] >= level && shown.get(k).overlaps(e))
				{
					level = Math.min(MAX_LEVEL, levels[k] + 1);
				}
			}
			levels[j] = level;
			lastVersions[j] = e.version;
		}
	}

	private boolean zoneShown(int zoneX, int zoneY)
	{
		int sceneX = zoneX * StrokeMesher.ZONE_TILES - baseX;
		int sceneY = zoneY * StrokeMesher.ZONE_TILES - baseY;
		int outside = OUTSIDE_ZONES * StrokeMesher.ZONE_TILES;
		if (sceneX < -outside || sceneY < -outside || sceneX >= sizeX + outside || sceneY >= sizeY + outside)
		{
			return false;
		}
		if (renderDistance <= 0)
		{
			return true;
		}
		// Like the render distance of the overlay, a square around the camera tile. A zone shows if a tile of it is in the square.
		int minX = zoneX * StrokeMesher.ZONE_TILES, minY = zoneY * StrokeMesher.ZONE_TILES;
		int maxX = minX + StrokeMesher.ZONE_TILES - 1, maxY = minY + StrokeMesher.ZONE_TILES - 1;
		return maxX >= cameraTileX - renderDistance && minX <= cameraTileX + renderDistance
			&& maxY >= cameraTileY - renderDistance && minY <= cameraTileY + renderDistance;
	}

	/** Puts the pieces of the shown strokes in their zones, and writes the zones that changed. Returns false on failure. */
	private boolean assemble()
	{
		boolean cap = lightnessCap();
		for (int j = 0; j < shown.size(); j++)
		{
			Entry e = shown.get(j);
			float lift = BASE_LIFT + levels[j] * LIFT_STEP;
			int[] layers = e.layers(cap);
			boolean whole = true;
			for (StrokeMesher.Mesh mesh : e.meshes())
			{
				for (StrokeMesher.Piece piece : mesh.pieces)
				{
					if (!zoneShown(piece.zoneX, piece.zoneY))
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
					zone.add(frame, piece, lift, layers[0], layers[1], layers[2]);
				}
			}
			// Points on tiles past the zones with models show over the game. Points on tiles without ground show nowhere.
			whole = whole && boxShown(e);
			drawn.add(e.stroke);
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

	/** Returns true if all tiles in the box of the stroke are in zones that show. */
	private boolean boxShown(Entry e)
	{
		int minX = Math.floorDiv((int) Math.floor(e.minX / StrokeMesher.TILE), StrokeMesher.ZONE_TILES);
		int maxX = Math.floorDiv((int) Math.floor(e.maxX / StrokeMesher.TILE), StrokeMesher.ZONE_TILES);
		int minY = Math.floorDiv((int) Math.floor(e.minY / StrokeMesher.TILE), StrokeMesher.ZONE_TILES);
		int maxY = Math.floorDiv((int) Math.floor(e.maxY / StrokeMesher.TILE), StrokeMesher.ZONE_TILES);
		return zoneShown(minX, minY) && zoneShown(maxX, maxY);
	}

	/** Takes all models out of the scene. They stay as spares. */
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

	/** A model and how many of its faces show. */
	private static final class Carrier
	{
		final Model model;
		int usedFaces, usedVertices;
		int freedFrame;

		Carrier(Model model)
		{
			this.model = model;
		}
	}

	/** Returns a model with room for the vertices and faces, or null if none can be made now. */
	private Carrier obtain(int vertices, int faces)
	{
		Carrier best = null;
		for (Carrier c : spares)
		{
			// A much larger model costs time in each frame for its hidden faces.
			if (frame - c.freedFrame >= SPARE_FRAMES && c.model.getVerticesCount() >= vertices && c.model.getFaceCount() >= faces
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
		// Some room to grow, so a stroke that the player draws does not need a new model for each point.
		int wantVertices = Math.min(CarrierModels.MAX_VERTICES - 6, Math.max(128, vertices * 3 / 2));
		int wantFaces = Math.min(CarrierModels.MAX_FACES, Math.max(96, faces * 3 / 2));
		if (!carriers.fits(wantVertices, wantFaces))
		{
			wantVertices = vertices;
			wantFaces = faces;
		}
		Model model = carriers.create(wantVertices, wantFaces, RADIUS);
		if (model == null)
		{
			return null;
		}
		// The normals point up, so 117 HD lights the strokes like flat ground.
		FlatModel.normals(model);
		Carrier carrier = new Carrier(model);
		// Six vertices fixed the bounds.
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

	/** The pieces of the strokes in one zone, and the models that show them. */
	private final class Zone
	{
		final int zoneX, zoneY;
		int frame;
		// The pieces in draw order, with the lift, the color and the alpha of their stroke.
		StrokeMesher.Piece[] pieces = new StrokeMesher.Piece[16];
		float[] lifts = new float[16];
		int[] colors = new int[16], alphas = new int[16], whites = new int[16];
		int items;
		long hash, writtenHash;
		final List<Part> parts = new ArrayList<>();

		Zone(int zoneX, int zoneY)
		{
			this.zoneX = zoneX;
			this.zoneY = zoneY;
		}

		void add(int frame, StrokeMesher.Piece piece, float lift, int color, int alpha, int white)
		{
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
			}
			pieces[items] = piece;
			lifts[items] = lift;
			colors[items] = color;
			alphas[items] = alpha;
			whites[items] = white;
			items++;
			hash = mix(hash, piece.id);
			hash = mix(hash, Float.floatToIntBits(lift));
			hash = mix(hash, (long) color << 16 | alpha << 8 | white);
		}

		// Scratch for one model: vertices in local units, faces, and their colors.
		private float[] vx = new float[256], vy = new float[256], vz = new float[256];
		private int[] fa = new int[256], fb = new int[256], fc = new int[256], fColor = new int[256], fAlpha = new int[256];
		private int vertices, faces;

		/** Writes the pieces into the models of the zone. Returns false if a model cannot be made. */
		boolean write()
		{
			int part = 0;
			vertices = 0;
			faces = 0;
			// The southwest corner of the zone in local units.
			float originX = (zoneX * StrokeMesher.ZONE_TILES - baseX) * (float) StrokeMesher.TILE;
			float originY = (zoneY * StrokeMesher.ZONE_TILES - baseY) * (float) StrokeMesher.TILE;
			for (int i = 0; i < items; i++)
			{
				StrokeMesher.Piece piece = pieces[i];
				int layerCount = whites[i] > 0 ? 2 : 1;
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
					polygon(piece, first, n, originX, originY, lifts[i], colors[i], alphas[i]);
					if (whites[i] > 0)
					{
						polygon(piece, first, n, originX, originY, lifts[i] + LIFT_STEP / 2, FlatModel.WHITE, whites[i]);
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

		/** Adds the polygon as faces with their front side up. */
		private void polygon(StrokeMesher.Piece piece, int first, int n, float originX, float originY, float lift, int color, int alpha)
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
			// The renderers do not draw faces that turn away from the camera. The GPU plugin draws a face if
			// (a - b) x (c - b) > 0 on the canvas (y down), which is counterclockwise on the screen. Seen from above with
			// north up, that is counterclockwise on the ground too, as the GPU plugin winds the ground tiles.
			boolean clockwise = area < 0;
			for (int j = 1; j < n - 1; j++)
			{
				fa[faces] = vertices;
				fb[faces] = vertices + (clockwise ? j + 1 : j);
				fc[faces] = vertices + (clockwise ? j : j + 1);
				fColor[faces] = color;
				fAlpha[faces] = alpha;
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
				fColor = java.util.Arrays.copyOf(fColor, n);
				fAlpha = java.util.Arrays.copyOf(fAlpha, n);
			}
		}

		/** Writes the scratch faces into model index of the zone, and empties the scratch. */
		private boolean flush(int index)
		{
			if (parts.size() <= index)
			{
				parts.add(new Part());
			}
			Part part = parts.get(index);
			// Each model stands on its own tile of the zone, because the client draws only a few objects on one tile.
			// Zones past the scene edge stand on the nearest scene tile.
			int tileX = Math.max(0, Math.min(sizeX - 1, zoneX * StrokeMesher.ZONE_TILES - baseX + index % StrokeMesher.ZONE_TILES));
			int tileY = Math.max(0, Math.min(sizeY - 1, zoneY * StrokeMesher.ZONE_TILES - baseY + index / StrokeMesher.ZONE_TILES % StrokeMesher.ZONE_TILES));
			int anchorX = tileX * StrokeMesher.TILE + StrokeMesher.TILE / 2;
			int anchorY = tileY * StrokeMesher.TILE + StrokeMesher.TILE / 2;
			double sum = 0;
			for (int i = 0; i < vertices; i++)
			{
				sum += vz[i];
			}
			int anchorZ = (int) Math.round(sum / vertices);
			for (int i = 0; i < vertices; i++)
			{
				if (Math.abs(vx[i] - anchorX) >= RADIUS || Math.abs(vy[i] - anchorY) >= RADIUS || Math.abs(vz[i] - anchorZ) >= RADIUS)
				{
					// Out of the bounds of the model. This does not happen in a zone that shows, but a bad height could do it.
					log.debug("Stroke geometry of zone {},{} is out of the model bounds", zoneX, zoneY);
					vertices = 0;
					faces = 0;
					return true;
				}
			}
			Carrier carrier = obtain(vertices, faces);
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
				// Model x is east, model y is down, model z is north.
				mx[i] = vx[i] - anchorX;
				my[i] = vz[i] - anchorZ;
				mz[i] = vy[i] - anchorY;
			}
			// Vertices that no face uses go to the origin, in the bounds.
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
				FlatModel.paint(m, f, fColor[f], fAlpha[f]);
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

	/** One scene object of a zone. */
	private final class Part
	{
		// The renderer reads the model, maybe on another thread. A new model goes in at once, and the old model is not
		// written again for SPARE_FRAMES frames.
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

	private static long mix(long hash, long value)
	{
		hash ^= value + 0x9E3779B97F4A7C15L + (hash << 6) + (hash >>> 2);
		return hash * 0xBF58476D1CE4E5B9L;
	}

	/** The meshes of one stroke. */
	private final class Entry
	{
		Stroke stroke;
		int alpha;
		int seenFrame;
		// Changes when the meshes change.
		int version;

		// What the meshes are for.
		private List<StrokePoint> pts;
		private int size;
		private double width;
		private LineStyle style;
		private int generation = -1;
		private int changedFrame;

		// The parts of CHUNK_POINTS points. complete is true when all points have parts.
		private final List<StrokeMesher.Mesh> chunks = new ArrayList<>();
		private boolean complete;
		// One mesh of all points, for a transparent stroke: the parts overlap where they join and where the stroke
		// crosses itself, so a transparent stroke made of parts gets darker there.
		private StrokeMesher.Mesh whole;
		private Future<StrokeMesher.Mesh> pending;
		double minX, minY, maxX, maxY;
		// The color layers (FlatModel.layers) and what they are for, so they are not found again on each frame.
		private final int[] layers = new int[3];
		private Color layersColor;
		private int layersAlpha = -1;
		private boolean layersCap;

		int[] layers(boolean cap)
		{
			if (stroke.color != layersColor || alpha != layersAlpha || cap != layersCap)
			{
				FlatModel.layers(stroke.color, alpha, cap, layers);
				layersColor = stroke.color;
				layersAlpha = alpha;
				layersCap = cap;
			}
			return layers;
		}

		/** Makes the meshes that the stroke needs, until the deadline. Returns true if the stroke has meshes for all points. */
		boolean update(Stroke s, double width, LineStyle style, long deadline)
		{
			if (s.pts != pts || width != this.width || style != this.style || generation != SceneStrokes.this.generation
				|| s.pts.size() < size)
			{
				pts = s.pts;
				this.width = width;
				this.style = style;
				generation = SceneStrokes.this.generation;
				size = 0;
				chunks.clear();
				changed();
			}
			int n = pts.size();
			if (n != size)
			{
				// New points: keep the full parts whose points and neighbors were all there.
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
				changed();
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
						return false;
					}
					StrokeMesher.Mesh mesh = StrokeMesher.build(pts, from, Math.min(from + CHUNK_POINTS, n - 1), width, style, ground);
					if (mesh == null)
					{
						return false;
					}
					chunks.add(mesh);
				}
				complete = true;
				box(chunks);
				version++;
			}
			if (pending != null && pending.isDone())
			{
				try
				{
					whole = pending.get();
					if (whole != null)
					{
						box(Collections.singletonList(whole));
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
				pending.cancel(true);
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
			return whole != null ? Collections.singletonList(whole) : chunks;
		}

		/**
		 * A transparent stroke of more than one part gets one mesh of all points, made on the worker thread when the
		 * stroke stays the same for STABLE_FRAMES frames. The ground heights go with it, so the worker does not read
		 * the scene.
		 */
		void wantWhole()
		{
			if (whole != null || pending != null || chunks.size() <= 1 || frame - changedFrame < STABLE_FRAMES || worker == null)
			{
				return;
			}
			List<StrokePoint> points = new ArrayList<>(pts.subList(0, size));
			double w = width;
			LineStyle st = style;
			StrokeMesher.Ground heights = new GroundCopy(ground,
				(int) Math.floor(minX / StrokeMesher.TILE) - 1, (int) Math.floor(minY / StrokeMesher.TILE) - 1,
				(int) Math.floor(maxX / StrokeMesher.TILE) + 1, (int) Math.floor(maxY / StrokeMesher.TILE) + 1);
			ExecutorService executor = worker;
			if (executor != null)
			{
				pending = executor.submit(() -> StrokeMesher.build(points, 0, points.size() - 1, w, st, heights));
			}
		}

		/** Stops the work for the stroke before it is forgotten. Returns true. */
		boolean forget()
		{
			if (pending != null)
			{
				pending.cancel(true);
			}
			return true;
		}
	}

	/** The ground heights of the scene that shows. Unlike WorldView heights, this also has the extended scene. */
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
				// The extended scene of the GPU plugin, past the loading line. Only the tiles that the client loaded.
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
			// On a bridge tile the ground is one level up.
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

	/** A copy of the ground heights of a box of tiles, for the worker thread. */
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

package com.rspaint;

import com.google.gson.Gson;
import com.rspaint.RsPaintConfig.LineStyle;
import com.rspaint.RsPaintPlugin.Stroke;
import com.rspaint.RsPaintPlugin.StrokeClock;
import com.rspaint.RsPaintPlugin.StrokePoint;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ColorUtil;

@Slf4j
@Singleton
public class BGMIOverlay extends Overlay implements MouseListener, KeyListener
{
	@Inject Client client;
	@Inject private RsPaintConfig config;
	@Inject private ConfigManager configManager;
	@Inject private Gson gson;
	@Inject private ColorPickerManager colorPickerManager;
	@Inject private ClientThread clientThread;
	@Inject private PartyCanvasSync partySync;
	@Inject private StrokeClock clock;
	@Inject private StrokeStorage storage;
	@Inject private StrokeBackups backups;
	@Inject private SceneStrokes sceneStrokes;

	@Getter @Setter private volatile boolean showOwnStrokes = true;

	static class pf extends Point2D.Float { pf(float x, float y) { super(x, y);} }
	static pf pf(float x, float y) { return new pf(x, y); }

	// Search order around selected tile
	private static final int[] TILE_X_OFFSETS = {0, 1, 1, 0, -1, -1, -1, 0, 1,
		2, 2, 2, 1, 0, -1, -2, -2, -2, -2, -2, -1, 0, 1, 2, 2};
	private static final int[] TILE_Y_OFFSETS = {0, 0, 1, 1, 1, 0, -1, -1, -1,
		0, 1, 2, 2, 2, 2, 2, 1, 0, -1, -2, -2, -2, -2, -2, -1};

	static final Color DEFAULT_COLOR = Color.LIGHT_GRAY;

	// Two columns of color slots
	static final int SLOTS_PER_COLUMN = 10;
	static final int COLOR_SLOTS = SLOTS_PER_COLUMN * 2;
	static final Color[] DEFAULT_PALETTE = {
		new Color(0x000000), // Black
		new Color(0xFFFFFF), // White
		new Color(0x7F7F7F), // Gray
		new Color(0xED1C24), // Red
		new Color(0xFF7F27), // Orange
		new Color(0xFFF200), // Yellow
		new Color(0x22B14C), // Green
		new Color(0x00A2E8), // Turquoise
		new Color(0x3F48CC), // Indigo
		new Color(0xA349A4), // Purple
	};

	@Inject
	public BGMIOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	Stroke currentStroke = null;

	// Fixed, so all clients store points alike
	private static final float MIN_POINT_SPACING_PX = 3f;

	// Stored on release, so the stroke ends at the mouse
	private StrokePoint skippedPoint = null;

	private void addPoint(StrokePoint point)
	{
		currentStroke.pts.add(point);
		partySync.onPointAdded(currentStroke, point);
		skippedPoint = null;
	}

	private void startStroke(StrokePoint point, int plane)
	{
		int region = new WorldPoint(point.getX(), point.getY(), 0).getRegionID();
		List<Stroke> strokes = regionStrokes(region);
		int saveRegion = saveRegion(point, plane);
		if (saveRegion == -1)
		{
			// Instance tile without a template chunk
			return;
		}
		if (unreadableRegions.contains(saveRegion))
		{
			// New stroke would be lost on next load
			warnUnreadableRegion(saveRegion);
			return;
		}
		currentStroke = new Stroke();
		currentStroke.rainbow = isRainbowSlot(selectedColor);
		currentStroke.color = currentStroke.rainbow ? Rainbow.FIRST : selectedColor();
		currentStroke.strokeWidth = strokeSize;
		currentStroke.order = clock.next();
		currentStroke.plane = plane;
		strokes.add(currentStroke);
		addToHistory(new CanvasChange(true, List.of(currentStroke)));
		addPoint(point);
	}

	private final Set<Integer> warnedRegions = new HashSet<>();

	private void warnUnreadableRegion(int region)
	{
		if (warnedRegions.add(region))
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"RsPaint can't read the saved strokes for this area, so drawing is turned off here.", null);
		}
	}

	private boolean isFarFromLastPoint(pf ground)
	{
		List<StrokePoint> pts = currentStroke.pts;
		if (pts.isEmpty())
		{
			return true;
		}
		// Current camera keeps spacing right
		pf last = toScreen(client.getTopLevelWorldView(), pts.get(pts.size() - 1));
		return last == null || last.distanceSq(ground) >= MIN_POINT_SPACING_PX * MIN_POINT_SPACING_PX;
	}

	private void finishStroke()
	{
		if (currentStroke != null && skippedPoint != null)
		{
			List<StrokePoint> pts = currentStroke.pts;
			if (pts.isEmpty() || !pts.get(pts.size() - 1).equals(skippedPoint))
			{
				addPoint(skippedPoint);
			}
		}
		if (currentStroke != null)
		{
			int oldRegion = currentStroke.primaryRegion();
			int oldSaveRegion = saveRegion(currentStroke.pts.get(0), currentStroke.plane);
			List<StrokePoint> rawPts = currentStroke.pts;
			currentStroke.pts = StrokeCodec.simplify(rawPts);
			// Rounding can move the first point to another region or template chunk
			int newRegion = currentStroke.primaryRegion();
			if (newRegion != oldRegion || saveRegion(currentStroke.pts.get(0), currentStroke.plane) != oldSaveRegion)
			{
				regionStrokes(newRegion);
				int newSaveRegion = saveRegion(currentStroke.pts.get(0), currentStroke.plane);
				if (newSaveRegion == -1 || unreadableRegions.contains(newSaveRegion))
				{
					// Unsaveable region, keep raw points
					currentStroke.pts = rawPts;
					newRegion = oldRegion;
				}
			}
			if (newRegion != oldRegion)
			{
				List<Stroke> oldStrokes = strokesByRegion.get(oldRegion);
				if (oldStrokes != null && oldStrokes.remove(currentStroke))
				{
					regionStrokes(newRegion).add(currentStroke);
					saveStrokes(oldRegion);
				}
			}
			saveStrokes(newRegion);
		}
		currentStroke = null;
		skippedPoint = null;
	}

	private static final int MAX_UNDO = 100;

	// One draw, or one erase drag
	@RequiredArgsConstructor
	private static class CanvasChange
	{
		final boolean drawn;
		final List<Stroke> strokes;
	}

	// Newest first. Client thread only
	private final Deque<CanvasChange> undoStack = new ArrayDeque<>();
	private final Deque<CanvasChange> redoStack = new ArrayDeque<>();
	// Undone as one change
	private CanvasChange currentErase = null;

	private void addToHistory(CanvasChange change)
	{
		undoStack.push(change);
		if (undoStack.size() > MAX_UNDO)
		{
			undoStack.removeLast();
		}
		redoStack.clear();
	}

	private void recordErase(List<Stroke> erased)
	{
		if (currentErase == null)
		{
			currentErase = new CanvasChange(false, new ArrayList<>());
			addToHistory(currentErase);
		}
		currentErase.strokes.addAll(erased);
	}

	// Client thread only
	void undo()
	{
		CanvasChange change = takeChange(undoStack);
		if (change == null)
		{
			return;
		}
		if (change.drawn)
		{
			removeStrokes(change.strokes);
		}
		else
		{
			restoreStrokes(change.strokes);
		}
		redoStack.push(change);
	}

	// Client thread only
	void redo()
	{
		CanvasChange change = takeChange(redoStack);
		if (change == null)
		{
			return;
		}
		if (change.drawn)
		{
			restoreStrokes(change.strokes);
		}
		else
		{
			removeStrokes(change.strokes);
		}
		undoStack.push(change);
	}

	// Null if empty or drag in progress
	private CanvasChange takeChange(Deque<CanvasChange> stack)
	{
		if (drawing || erasing)
		{
			return null;
		}
		// Mouse can be up before the next frame ends the stroke
		finishStroke();
		currentErase = null;
		return stack.poll();
	}

	private void removeStrokes(List<Stroke> strokes)
	{
		Set<Integer> changedRegions = new LinkedHashSet<>();
		for (Stroke stroke : strokes)
		{
			// By id, region can reload with new objects
			if (regionStrokes(stroke.primaryRegion()).removeIf(s -> s.id.equals(stroke.id)))
			{
				changedRegions.add(stroke.primaryRegion());
			}
		}
		changedRegions.forEach(this::saveStrokes);
		partySync.onStrokesErased(strokes);
	}

	private void restoreStrokes(List<Stroke> strokes)
	{
		Set<Integer> changedRegions = new LinkedHashSet<>();
		for (Stroke stroke : strokes)
		{
			regionStrokes(stroke.primaryRegion()).add(stroke);
			changedRegions.add(stroke.primaryRegion());
		}
		changedRegions.forEach(this::saveStrokes);
		partySync.onStrokesRestored(strokes);
	}

	// Null if off screen
	private pf toScreen(WorldView worldView, StrokePoint p)
	{
		LocalPoint lp = loadedLocalPoint(worldView, p.getX(), p.getY(), worldView.getPlane());
		Polygon poly = lp != null ? Perspective.getCanvasTilePoly(client, lp) : null;
		return poly != null ? projectToScreen(p.getSubx(), p.getSuby(), poly) : null;
	}

	// GPU plugin extended scene, up to 40 tiles per side of the top-level scene
	private int extendedBorder(WorldView worldView)
	{
		if (!worldView.isTopLevel() || client.getExpandedMapLoading() <= 0)
		{
			return 0;
		}
		return (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
	}

	// Tiles the GPU plugin loads, can be less than extendedBorder
	// Region range only, the tile array uses extendedBorder
	private int loadedBorder(WorldView worldView)
	{
		return Math.min(extendedBorder(worldView), client.getExpandedMapLoading() * Constants.CHUNK_SIZE);
	}

	// Null if not loaded. Unlike LocalPoint.fromWorld, also covers extended tiles past the loading line
	private LocalPoint loadedLocalPoint(WorldView worldView, int worldX, int worldY, int plane)
	{
		int sceneX = worldX - worldView.getBaseX();
		int sceneY = worldY - worldView.getBaseY();
		if (sceneX >= 0 && sceneY >= 0 && sceneX < worldView.getSizeX() && sceneY < worldView.getSizeY())
		{
			return LocalPoint.fromScene(sceneX, sceneY, worldView);
		}

		int border = extendedBorder(worldView);
		if (border == 0 || plane < 0 || plane >= Constants.MAX_Z)
		{
			return null;
		}
		int extendedX = sceneX + border;
		int extendedY = sceneY + border;
		Tile[][][] extendedTiles = worldView.getScene().getExtendedTiles();
		if (extendedTiles == null || extendedX < 0 || extendedY < 0
			|| extendedX >= extendedTiles[plane].length || extendedY >= extendedTiles[plane][extendedX].length
			|| extendedTiles[plane][extendedX][extendedY] == null)
		{
			return null;
		}
		return LocalPoint.fromScene(sceneX, sceneY, worldView);
	}

	int strokeSize = Stroke.MIN_WIDTH;
	List<Color> colors = new ArrayList<>();
	// Rainbow slots hold Rainbow.FIRST in colors
	boolean[] rainbowSlots = new boolean[0];
	int selectedColor = 0;

	// Camera scale where stored width equals screen pixels (fixed mode default zoom)
	private static final float REFERENCE_SCALE = 512f;

	// Set each frame
	private float widthScale = 1f;

	// Set each frame
	private boolean transparentStrokes = true;

	// Render distance in tiles, 0 is no limit. Set each frame
	private int renderDistance = 0;
	private int cameraTileX, cameraTileY;

	// Square around camera tile, like GPU draw distance
	private boolean isInRenderDistance(WorldView worldView, int worldX, int worldY)
	{
		return renderDistance == 0
			|| Math.abs(worldX - worldView.getBaseX() - cameraTileX) <= renderDistance
			&& Math.abs(worldY - worldView.getBaseY() - cameraTileY) <= renderDistance;
	}

	// Minimum 1 pixel
	private float screenWidth(double width)
	{
		return Math.max(1f, (float) width * widthScale);
	}

	private void changeStrokeSize(int change)
	{
		strokeSize = Math.max(Stroke.MIN_WIDTH, Math.min(Stroke.MAX_WIDTH, strokeSize + change));
		configManager.setConfiguration(RsPaintConfig.GROUP, "strokeSize", strokeSize);
	}

	// Held size button repeats, speeding up to one per client tick
	private static final long HOLD_DELAY_MS = 400;
	private static final long HOLD_FIRST_INTERVAL_MS = 80;
	private static final long HOLD_MIN_INTERVAL_MS = 20;

	// Written on the AWT thread, -1 if none held
	private volatile int heldSizeButton = -1;
	private volatile long heldSince;
	// Client thread only
	private long repeatPressStart;
	private long nextRepeatAt;
	private long repeatInterval;

	private boolean isSizeButton(int index)
	{
		return index == SIZE_BUTTONS_START || index == SIZE_BUTTONS_START + 1;
	}

	private void repeatHeldSizeButton(int mx, int my)
	{
		int held = heldSizeButton;
		if (held == -1)
		{
			return;
		}

		long pressStart = heldSince;
		if (pressStart != repeatPressStart)
		{
			// Click already changed size once
			repeatPressStart = pressStart;
			nextRepeatAt = pressStart + HOLD_DELAY_MS;
			repeatInterval = HOLD_FIRST_INTERVAL_MS;
		}

		long now = System.currentTimeMillis();
		// Pauses while mouse is off the button
		if (now < nextRepeatAt || !toolbarButtonClickbox(held).contains(mx, my))
		{
			return;
		}
		changeStrokeSize(held == SIZE_BUTTONS_START ? -1 : 1);
		repeatInterval = Math.max(HOLD_MIN_INTERVAL_MS, repeatInterval * 9 / 10);
		nextRepeatAt = now + repeatInterval;
	}

	private Color selectedColor()
	{
		Color color = colors.get(selectedColor);
		return color != null ? color : DEFAULT_COLOR;
	}

	private boolean isRainbowSlot(int index)
	{
		return index < rainbowSlots.length && rainbowSlots[index] && colors.get(index) != null;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		widthScale = config.scaleWidthWithZoom() && client.getScale() > 0 ? client.getScale() / REFERENCE_SCALE : 1f;

		Point mouse = client.getMouseCanvasPosition();
		int mx = mouse.getX();
		int my = mouse.getY();

		if (!drawing)
		{
			finishStroke();
		}
		if (!erasing)
		{
			currentErase = null;
		}

		boolean showUi = eraseMode || drawMode || drawing || erasing;
		boolean overUi = showUi && getMousedOverToolbarButton(mx, my) != -1;

		Tile tile = drawMode || drawing ? client.getTopLevelWorldView().getSelectedSceneTile() : null;
		pf ground = null;

		if (tile != null)
		{
			Polygon poly = null;
			int tilex = -1;
			int tiley = -1;
			for (int i = 0; i < TILE_X_OFFSETS.length; i++)
			{
				LocalPoint plus = tile.getLocalLocation().plus(TILE_X_OFFSETS[i] * 128, TILE_Y_OFFSETS[i] * 128);
				Polygon test = Perspective.getCanvasTilePoly(client, plus);
				if (test != null && test.contains(mx, my))
				{
					poly = test;
					tilex = tile.getWorldLocation().getX() + TILE_X_OFFSETS[i];
					tiley = tile.getWorldLocation().getY() + TILE_Y_OFFSETS[i];
					break;
				}
			}

			if (poly != null)
			{
				cursorTileX = tilex;
				cursorTileY = tiley;
				int
					x0 = poly.xpoints[0],
					y0 = poly.ypoints[0],
					x1 = poly.xpoints[1],
					y1 = poly.ypoints[1],
					x2 = poly.xpoints[2],
					y2 = poly.ypoints[2],
					x3 = poly.xpoints[3],
					y3 = poly.ypoints[3];

				pf sub = getBarycentre(mx, my, x0, y0, x1, y1, x2, y2, x3, y3);
				cursorSub = sub;
				ground = projectToScreen(sub.x, sub.y, poly);

				if (drawing) {
					int plane = client.getTopLevelWorldView().getPlane();
					if (currentStroke != null && currentStroke.plane != plane) {
						// Floor changed, new stroke
						finishStroke();
					}
					StrokePoint point = new StrokePoint(tilex, tiley, sub.x, sub.y);
					if (currentStroke == null) {
						startStroke(point, plane);
					} else if (isFarFromLastPoint(ground)) {
						addPoint(point);
					} else {
						skippedPoint = point;
					}
				}
			}
		}

		drawStrokes(graphics, mx, my);

		if (showUi) {
			if (!overUi) {
				if (eraseMode || erasing) {
					graphics.setColor(Color.PINK);
					graphics.draw(new Ellipse2D.Float(mx - 10, my - 10, 20, 20));
				} else if ((drawMode || drawing) && ground != null) {
					graphics.setColor(brushColor());
					float size = screenWidth(strokeSize);
					if (size <= 2) {
						graphics.drawRect((int) ground.x - 1, (int) ground.y - 1, 2, 2);
					} else if (sceneStrokes.isActive()) {
						// Ground-width brush circle
						drawGroundCircle(graphics, cursorTileX, cursorTileY, cursorSub,
							SceneStrokes.worldWidth(strokeSize, config.scaleWidthWithZoom(), client.getScale()) / 2);
					} else {
						graphics.draw(new Ellipse2D.Float(ground.x - size / 2, ground.y - size / 2, size, size));
					}
				}
			}

			for (int i = 0; i < buttons.size(); i++)
			{
				Rectangle rectangle = toolbarButtonClickbox(i);
				boolean mouseOver = rectangle.contains(mx, my);
				buttons.get(i).draw(graphics, toolbarButtonVisibleBounds(i), mouseOver);
			}
		}

		return null;
	}

	List<ToolbarButton> buttons = new ArrayList<>();
	{
		buttons.add(new ToolbarButton() {
			@Override public void draw(Graphics2D g, Rectangle r, boolean mouseOver) {
				g.setColor(Color.LIGHT_GRAY);
				g.drawLine(r.x + r.width / 2, r.y + 3, r.x + r.width / 2, r.y + r.height - 3);
				g.drawLine(r.x + 3, r.y + r.height / 2, r.x + r.width - 3, r.y + r.height / 2);
				super.draw(g, r, mouseOver);
			}
			@Override public void addMenuEntries() {
				// Last entry is on top, so "New color" stays the left-click option
				client.getMenu().createMenuEntry(-1).setOption("New rainbow").onClick(me -> addSlotAtStart(Rainbow.FIRST, true));
				client.getMenu().createMenuEntry(-1).setOption("New color").onClick(me -> pickColor(-1));
			}
		});
		// Reset palette
		buttons.add(new ToolbarButton() {
			@Override public void draw(Graphics2D g, Rectangle r, boolean mouseOver) {
				g.setColor(Color.LIGHT_GRAY);
				int cx = r.x + r.width / 2;
				int top = r.y + 3;
				g.drawArc(r.x + 3, top, r.width - 6, r.height - 6, 90, -270);
				g.drawLine(cx - 1, top, cx + 2, top - 2);
				g.drawLine(cx - 1, top, cx + 2, top + 2);
				super.draw(g, r, mouseOver);
			}
			@Override public void addMenuEntries() {
				client.getMenu().createMenuEntry(-1).setOption("Reset").setTarget("palette").onClick(me -> useDefaultPalette());
			}
		});
		for (int i = 0; i < COLOR_SLOTS; i++) {
			buttons.add(new ColorToolbarButton(i));
		}
		// Decrease size
		buttons.add(new ToolbarButton() {
			@Override public void draw(Graphics2D g, Rectangle r, boolean mouseOver) {
				g.setColor(strokeSize > Stroke.MIN_WIDTH ? Color.LIGHT_GRAY : Color.DARK_GRAY);
				g.drawLine(r.x + 3, r.y + r.height / 2, r.x + r.width - 3, r.y + r.height / 2);
				super.draw(g, r, mouseOver);
			}
			@Override public void addMenuEntries() {
				client.getMenu().createMenuEntry(-1).setOption("Decrease by 10").setTarget("stroke size").onClick(me -> changeStrokeSize(-10));
				client.getMenu().createMenuEntry(-1).setOption("Decrease").setTarget("stroke size").onClick(me -> changeStrokeSize(-1));
			}
		});
		// Increase size
		buttons.add(new ToolbarButton() {
			@Override public void draw(Graphics2D g, Rectangle r, boolean mouseOver) {
				g.setColor(strokeSize < Stroke.MAX_WIDTH ? Color.LIGHT_GRAY : Color.DARK_GRAY);
				g.drawLine(r.x + r.width / 2, r.y + 3, r.x + r.width / 2, r.y + r.height - 3);
				g.drawLine(r.x + 3, r.y + r.height / 2, r.x + r.width - 3, r.y + r.height / 2);
				super.draw(g, r, mouseOver);
			}
			@Override public void addMenuEntries() {
				client.getMenu().createMenuEntry(-1).setOption("Increase by 10").setTarget("stroke size").onClick(me -> changeStrokeSize(10));
				client.getMenu().createMenuEntry(-1).setOption("Increase").setTarget("stroke size").onClick(me -> changeStrokeSize(1));
			}
		});
		// Stroke size number
		buttons.add(new ToolbarButton() {
			@Override public void draw(Graphics2D g, Rectangle r, boolean mouseOver) {
				String text = Integer.toString(strokeSize);
				FontMetrics metrics = g.getFontMetrics();
				int textX = r.x + (r.width - metrics.stringWidth(text)) / 2;
				int textY = r.y + (r.height - metrics.getHeight()) / 2 + metrics.getAscent();
				g.setColor(Color.LIGHT_GRAY);
				g.drawString(text, textX, textY);
				g.setColor(Color.GRAY);
				g.draw(r);
			}
		});
	}

	// Do not change the rectangles
	private final Rectangle[] clickboxes = layoutClickboxes(buttons.size());
	// Reused, client thread only
	private final Rectangle drawBounds = new Rectangle();

	class ToolbarButton {
		public void draw(Graphics2D g, Rectangle r, boolean mouseOver) {
			g.setColor(mouseOver ? Color.WHITE : Color.LIGHT_GRAY);
			g.draw(r);
		}

		public void addMenuEntries() { }
		public void onDrag(ToolbarButton b) { }
	}

	@RequiredArgsConstructor
	class ColorToolbarButton extends ToolbarButton {
		final int colorIndex;
		@Override public void draw(Graphics2D g, Rectangle r, boolean mouseOver) {
			Color color = colorIndex < colors.size() ? colors.get(colorIndex) : null;
			if (colorIndex == selectedColor) r.grow(2, 2);
			if (isRainbowSlot(colorIndex)) {
				// One rainbow loop, left to right
				for (int x = 0; x < r.width; x++) {
					g.setColor(Rainbow.color(x * Rainbow.STEPS / r.width));
					g.fillRect(r.x + x, r.y, 1, r.height);
				}
			} else if (color != null) {
				g.setColor(color);
				g.fill(r);
			}
			g.setColor((mouseOver || colorIndex == selectedColor) ? Color.WHITE : Color.GRAY);
			g.draw(r);
		}

		@Override public void addMenuEntries() {
			Color color = colors.get(colorIndex);
			if (color != null) {
				String option = isRainbowSlot(colorIndex) ? "Select rainbow" : "Select color";
				client.getMenu().createMenuEntry(1).setOption(ColorUtil.wrapWithColorTag(option, color)).onClick(me -> selectedColor = colorIndex);
			}
			client.getMenu().createMenuEntry(1).setOption("Choose ").setTarget("new color").onClick(me -> pickColor(colorIndex));
			// Index 1 puts the entry below the earlier ones
			client.getMenu().createMenuEntry(1).setOption("Choose ").setTarget("rainbow").onClick(me -> setSlot(colorIndex, Rainbow.FIRST, true));
		}

		@Override public void onDrag(ToolbarButton b) {
			if (b instanceof ColorToolbarButton) {
				int otherIndex = ((ColorToolbarButton) b).colorIndex;
				Color mine = colors.get(colorIndex);
				Color other = colors.get(otherIndex);
				colors.set(colorIndex, other);
				colors.set(otherIndex, mine);
				boolean myRainbow = rainbowSlots[colorIndex];
				rainbowSlots[colorIndex] = rainbowSlots[otherIndex];
				rainbowSlots[otherIndex] = myRainbow;
				saveColors();
			}
		}
	}

	// Index -1 adds a new slot at the start
	public void pickColor(int index) {
		Color color = colors.get(index == -1 ? selectedColor : index);
		SwingUtilities.invokeLater(() ->
		{
			// The picker also shows opacity, which strokes keep
			RuneliteColorPicker colorPicker = colorPickerManager.create(client,
				color != null ? color : Color.WHITE, "Paint color", false);
			colorPicker.setOnClose(c -> {
				if (index == -1) {
					addSlotAtStart(c, false);
					return;
				}
				setSlot(index, c, false);
			});
			colorPicker.setVisible(true);
		});
	}

	private void setSlot(int index, Color color, boolean rainbow) {
		colors.set(index, color);
		rainbowSlots[index] = rainbow;
		saveColors();
	}

	private void addSlotAtStart(Color color, boolean rainbow) {
		for (int i = colors.size() - 1; i > 0; i--) {
			colors.set(i, colors.get(i - 1));
			rainbowSlots[i] = rainbowSlots[i - 1];
		}
		colors.set(0, color);
		rainbowSlots[0] = rainbow;
		selectedColor = 0;
		saveColors();
	}

	private void saveColors()
	{
		configManager.setConfiguration(RsPaintConfig.GROUP, "drawColors", gson.toJson(colors));
		// Own key, so older versions still read colors
		configManager.setConfiguration(RsPaintConfig.GROUP, "rainbowSlots", gson.toJson(rainbowSlots));
	}

	// Rainbow stroke end color, else selected
	private Color brushColor()
	{
		if (currentStroke != null && currentStroke.rainbow && !currentStroke.pts.isEmpty())
		{
			float[] distances = currentStroke.distances();
			return Rainbow.colorAt(distances[currentStroke.pts.size() - 1]);
		}
		return selectedColor();
	}

	@Subscribe public void onClientTick(ClientTick e) {
		partySync.flush(strokesByRegion);

		if (!drawMode && !eraseMode && !drawing && !erasing || client.isMenuOpen()) return;

		Point mouse = client.getMouseCanvasPosition();
		repeatHeldSizeButton(mouse.getX(), mouse.getY());
		int button = getMousedOverToolbarButton(mouse.getX(), mouse.getY());
		if (button != -1) {
			client.getMenu().setMenuEntries(Arrays.stream(client.getMenu().getMenuEntries()).filter(me -> me.getType() == MenuAction.CANCEL).toArray(MenuEntry[]::new));
			buttons.get(button).addMenuEntries();
			return;
		}
		if ((drawMode || eraseMode) && Arrays.stream(client.getMenu().getMenuEntries()).anyMatch(entry -> entry.getType() == MenuAction.WALK || entry.getType() == MenuAction.SET_HEADING)) {
			client.getMenu().createMenuEntry(-1).setOption(drawMode ? ColorUtil.wrapWithColorTag("Draw", selectedColor()) : "Erase").setType(MenuAction.CC_OP);
			menuAllowsDraw = true;
		} else {
			menuAllowsDraw = false;
		}
	}

	private static pf projectToScreen(float subx, float suby, Polygon poly)
	{
		return projectToScreen(subx, suby, poly, pf(0, 0));
	}

	// Writes into out
	private static pf projectToScreen(float subx, float suby, Polygon poly, pf out)
	{
		int[] xs = poly.xpoints;
		int[] ys = poly.ypoints;
		if (subx + suby <= 1)
		{
			out.setLocation(
				xs[0] + (xs[1] - xs[0]) * subx + (xs[3] - xs[0]) * suby,
				ys[0] + (ys[1] - ys[0]) * subx + (ys[3] - ys[0]) * suby);
			return out;
		}
		subx = 1 - subx;
		suby = 1 - suby;
		out.setLocation(
			xs[2] + (xs[3] - xs[2]) * subx + (xs[1] - xs[2]) * suby,
			ys[2] + (ys[3] - ys[2]) * subx + (ys[1] - ys[2]) * suby);
		return out;
	}

	private pf getBarycentre(int mx, int my, int x0, int y0, int x1, int y1, int x2, int y2, int x3, int y3)
	{
		// Triangle 0, 1, 3 first, then 1, 2, 3
		float
			area13p = triangleArea(x1, y1, x3, y3, mx, my),
			area03p = triangleArea(x0, y0, x3, y3, mx, my),
			area01p = triangleArea(x0, y0, x1, y1, mx, my),
			area013 = triangleArea(x0, y0, x1, y1, x3, y3);
		float
			swb0 = area13p / area013,
			swb1 = area03p / area013,
			swb3 = area01p / area013;
		if (swb0 + swb1 + swb3 > 1f)
		{
			float
				area23p = triangleArea(x2, y2, x3, y3, mx, my),
				area12p = triangleArea(x1, y1, x2, y2, mx, my),
				area123 = triangleArea(x1, y1, x2, y2, x3, y3);
			return pf(1 - area12p / area123, 1 - area23p / area123);
		}
		return pf(swb1, swb3);
	}

	@Subscribe public void onGameStateChanged(GameStateChanged e) {
		if (e.getGameState() == GameState.LOADING) {
			// Ground heights changed
			sceneStrokes.sceneChanged();
		}
		// Scene base is final only after the scene loads
		if (e.getGameState() != GameState.LOGGED_IN) return;

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView != null) loadScene(worldView);
	}

	// Extra regions around scene and border, for strokes starting just outside
	private static final int NEARBY_REGIONS = 1;

	// By region of first point. In an instance: instance coordinates, saved under template regions
	Map<Integer, List<Stroke>> strokesByRegion = new HashMap<>();

	// Null outside an instance
	private InstanceMap instanceMap = null;
	// Template coordinates. Unreadable regions absent
	private final Map<Integer, List<PartyCanvasSync.SyncedStroke>> templateStrokes = new HashMap<>();

	// Region of the config key, -1 if unsaveable
	// In an instance, the template tile region, as Ground Markers saves
	private int saveRegion(StrokePoint first, int plane)
	{
		if (instanceMap != null)
		{
			return instanceMap.templateRegion(first, plane);
		}
		return new WorldPoint(first.getX(), first.getY(), 0).getRegionID();
	}

	private void loadScene(WorldView worldView)
	{
		if (worldView.isInstance())
		{
			loadInstance(worldView);
			return;
		}
		if (instanceMap != null)
		{
			// Left the instance
			finishStroke();
			instanceMap = null;
			templateStrokes.clear();
			strokesByRegion.clear();
			unreadableRegions.clear();
		}
		// Same regions drawing reads, so drawing never hits config
		loadStrokes(sceneRegions(worldView));
	}

	// One copy per chunk with that template. Unchanged chunks keep loaded Strokes for undo, redo, party sync
	private void loadInstance(WorldView worldView)
	{
		int[][][] chunks = worldView.getInstanceTemplateChunks();
		int baseX = worldView.getBaseX();
		int baseY = worldView.getBaseY();
		if (instanceMap == null || !instanceMap.isSame(chunks, baseX, baseY))
		{
			// Save stroke in progress before its list goes
			finishStroke();
			instanceMap = new InstanceMap(chunks, baseX, baseY);
			templateStrokes.clear();
			strokesByRegion.clear();
			unreadableRegions.clear();

			List<Stroke> loaded = new ArrayList<>();
			for (int region : instanceMap.templateRegions())
			{
				List<PartyCanvasSync.SyncedStroke> saved = readTemplate(region);
				if (saved == null)
				{
					continue;
				}
				for (PartyCanvasSync.SyncedStroke template : saved)
				{
					boolean first = true;
					for (InstanceMap.Placement placement : instanceMap.placements(template))
					{
						Stroke copy = instanceCopy(template, placement, first);
						first = false;
						strokesByRegion.computeIfAbsent(copy.primaryRegion(), k -> new ArrayList<>()).add(copy);
						loaded.add(copy);
					}
				}
			}
			if (!loaded.isEmpty())
			{
				// Party shares instance coordinates
				partySync.onStrokesRestored(loaded);
			}
		}
		// Saves add copies without changing map keys
		for (int region : sceneRegions(worldView))
		{
			strokesByRegion.computeIfAbsent(region, k -> new ArrayList<>());
		}
	}

	// In instance coordinates. First copy keeps the template id, others derive a stable one
	// so reloads give no party duplicates
	private Stroke instanceCopy(PartyCanvasSync.SyncedStroke template, InstanceMap.Placement placement, boolean first)
	{
		Stroke stroke = PartyCanvasSync.fromSynced(template);
		stroke.id = first ? template.getId() : template.getId() + "~" + placement.key();
		stroke.templateId = template.getId();
		stroke.plane = placement.getPlane();
		stroke.pts.clear();
		stroke.pts.addAll(instanceMap.toInstance(template, placement));
		return stroke;
	}

	// Null if unreadable, the region is then in unreadableRegions
	private List<PartyCanvasSync.SyncedStroke> readTemplate(int region)
	{
		String key = "strokes_" + region;
		String conf = configManager.getConfiguration(RsPaintConfig.GROUP, key);
		List<PartyCanvasSync.SyncedStroke> strokes = new ArrayList<>();
		if (conf != null)
		{
			StrokeStorage.Loaded loaded;
			try
			{
				loaded = storage.read(conf);
			}
			catch (StrokeStorage.UnreadableStrokesException e)
			{
				log.warn("Cannot load the strokes of region {}: {}", region, e.getMessage());
				unreadableRegions.add(region);
				return null;
			}
			for (PartyCanvasSync.SyncedStroke stroke : loaded.getStrokes())
			{
				if (stroke.getId() == null)
				{
					// Copies match by id on save
					stroke = new PartyCanvasSync.SyncedStroke(Stroke.newId(), stroke.getColor(), stroke.getStrokeWidth(),
						stroke.getOrder(), stroke.getPlane(), stroke.getPoints(), stroke.getRainbow());
				}
				clock.advancePast(stroke.getOrder());
				strokes.add(stroke);
			}
			if (loaded.getVersion() < StrokeStorage.FORMAT_VERSION)
			{
				migrate(key, loaded, conf);
			}
		}
		templateStrokes.put(region, strokes);
		return strokes;
	}

	// Saved stroke that lost a copy is erased everywhere, one with its chunk outside the scene stays
	// New stroke gets copies at the other places of its chunk
	private void saveTemplates(Set<Integer> regions)
	{
		if (regions.isEmpty())
		{
			return;
		}

		// By template region of first point
		Map<Integer, List<Stroke>> shownByRegion = new HashMap<>();
		for (List<Stroke> strokes : strokesByRegion.values())
		{
			for (Stroke stroke : strokes)
			{
				int region = instanceMap.templateRegion(stroke);
				if (regions.contains(region))
				{
					shownByRegion.computeIfAbsent(region, k -> new ArrayList<>()).add(stroke);
				}
			}
		}

		Set<Stroke> erased = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Stroke> added = new ArrayList<>();
		for (int region : regions)
		{
			List<PartyCanvasSync.SyncedStroke> saved = templateStrokes.get(region);
			if (saved == null || unreadableRegions.contains(region))
			{
				continue;
			}
			List<Stroke> shown = shownByRegion.getOrDefault(region, Collections.emptyList());
			Map<String, List<Stroke>> copiesById = new HashMap<>();
			for (Stroke stroke : shown)
			{
				if (stroke.templateId != null)
				{
					copiesById.computeIfAbsent(stroke.templateId, k -> new ArrayList<>()).add(stroke);
				}
			}

			List<PartyCanvasSync.SyncedStroke> result = new ArrayList<>();
			Set<String> keptIds = new HashSet<>();
			for (PartyCanvasSync.SyncedStroke template : saved)
			{
				int places = instanceMap.placements(template).size();
				List<Stroke> copies = copiesById.getOrDefault(template.getId(), Collections.emptyList());
				if (places == 0 || copies.size() >= places)
				{
					result.add(template);
					keptIds.add(template.getId());
				}
				else
				{
					erased.addAll(copies);
				}
			}

			for (Stroke stroke : shown)
			{
				// Undo or redo can restore a stroke whose id was erased since, so it counts as new
				if (stroke.templateId != null && keptIds.contains(stroke.templateId) || erased.contains(stroke))
				{
					continue;
				}
				PartyCanvasSync.SyncedStroke template = instanceMap.toTemplate(stroke);
				if (template == null)
				{
					continue;
				}
				result.add(template);
				stroke.templateId = stroke.id;
				InstanceMap.Placement own = instanceMap.placementOf(stroke);
				for (InstanceMap.Placement placement : instanceMap.placements(template))
				{
					if (!placement.equals(own))
					{
						added.add(instanceCopy(template, placement, false));
					}
				}
			}

			templateStrokes.put(region, result);
			configManager.setConfiguration(RsPaintConfig.GROUP, "strokes_" + region, storage.write(result));
		}

		if (!erased.isEmpty())
		{
			for (List<Stroke> strokes : strokesByRegion.values())
			{
				strokes.removeIf(erased::contains);
			}
			partySync.onStrokesErased(erased);
		}
		if (!added.isEmpty())
		{
			for (Stroke stroke : added)
			{
				strokesByRegion.computeIfAbsent(stroke.primaryRegion(), k -> new ArrayList<>()).add(stroke);
			}
			partySync.onStrokesRestored(added);
		}
	}

	void loadStrokes(int[] regions) {
		Set<Integer> loadedRegions = strokesByRegion.keySet();
		Set<Integer> keptRegions = new HashSet<>();
		for (int region : regions)
		{
			keptRegions.add(region);
		}
		List<Integer> regionsToUnload = new ArrayList<>(loadedRegions);
		regionsToUnload.removeAll(keptRegions);
		for (Integer region : regionsToUnload)
		{
			strokesByRegion.remove(region);
		}
		List<Integer> regionsToLoad = new ArrayList<>(keptRegions);
		// Same Stroke objects for undo, redo, party sync
		regionsToLoad.removeAll(loadedRegions);
		for (Integer region : regionsToLoad)
		{
			strokesByRegion.put(region, readStrokes(region));
		}
	}

	List<Stroke> regionStrokes(int regionId) {
		return strokesByRegion.computeIfAbsent(regionId, this::readStrokes);
	}

	// Unreadable saves, e.g. from a newer version. Never saved, so they stay readable there
	private final Set<Integer> unreadableRegions = new HashSet<>();

	private List<Stroke> readStrokes(int regionId) {
		if (instanceMap != null)
		{
			// Instance region keys unused, loadInstance loads templates
			return new ArrayList<>();
		}
		String key = "strokes_" + regionId;
		String conf = configManager.getConfiguration(RsPaintConfig.GROUP, key);
		unreadableRegions.remove(regionId);
		if (conf == null) return new ArrayList<>();

		StrokeStorage.Loaded loaded;
		try
		{
			loaded = storage.read(conf);
		}
		catch (StrokeStorage.UnreadableStrokesException e)
		{
			log.warn("Cannot load the strokes of region {}: {}", regionId, e.getMessage());
			unreadableRegions.add(regionId);
			return new ArrayList<>();
		}

		List<Stroke> strokes = new ArrayList<>();
		for (PartyCanvasSync.SyncedStroke synced : loaded.getStrokes())
		{
			Stroke stroke = PartyCanvasSync.fromSynced(synced);
			clock.advancePast(stroke.order);
			strokes.add(stroke);
		}
		if (loaded.getVersion() < StrokeStorage.FORMAT_VERSION)
		{
			migrate(key, loaded, conf);
		}
		if (!strokes.isEmpty())
		{
			// Party has the other strokes already
			partySync.onStrokesRestored(strokes);
		}
		return strokes;
	}

	// Avoids backing up twice on region reload
	private final Map<String, String> backupsInProgress = new HashMap<>();

	// Backs up old text in the background, then saves in the current format
	// On write failure the old text stays and the next load retries
	private void migrate(String key, StrokeStorage.Loaded loaded, String oldText)
	{
		if (oldText.equals(backupsInProgress.get(key)))
		{
			return;
		}
		backupsInProgress.put(key, oldText);
		String newText = storage.write(loaded.getStrokes());
		backups.save(key, loaded.getVersion(), oldText).whenComplete((ignored, error) -> clientThread.invoke(() ->
		{
			backupsInProgress.remove(key, oldText);
			if (error != null)
			{
				log.warn("Cannot save a backup of {}, so it stays in the old format", key, error);
				return;
			}
			// Do not overwrite a newer save or profile change
			if (oldText.equals(configManager.getConfiguration(RsPaintConfig.GROUP, key)))
			{
				configManager.setConfiguration(RsPaintConfig.GROUP, key, newText);
			}
		}));
	}

	void saveStrokes(int regionId) {
		if (instanceMap != null)
		{
			// Save to template regions of its chunks
			saveTemplates(instanceMap.templateRegionsIn(regionId));
			return;
		}
		List<Stroke> strokes = strokesByRegion.get(regionId);
		if (strokes == null || unreadableRegions.contains(regionId)) return;
		List<PartyCanvasSync.SyncedStroke> synced = new ArrayList<>();
		for (Stroke stroke : strokes)
		{
			if (!stroke.pts.isEmpty())
			{
				synced.add(PartyCanvasSync.toSynced(stroke, stroke.pts));
			}
		}
		configManager.setConfiguration(RsPaintConfig.GROUP, "strokes_" + regionId, storage.write(synced));
	}

	// Loaded regions only, other regions stay saved
	public void clearCanvas()
	{
		currentStroke = null;
		skippedPoint = null;
		// Copy, instance saves can add a region list
		for (Map.Entry<Integer, List<Stroke>> entry : new ArrayList<>(strokesByRegion.entrySet()))
		{
			if (!entry.getValue().isEmpty())
			{
				entry.getValue().clear();
				saveStrokes(entry.getKey());
			}
		}
		// A reset cannot be undone
		undoStack.clear();
		redoStack.clear();
		currentErase = null;
		partySync.onCanvasCleared();
	}

	// Client thread only
	List<PartyCanvasSync.SyncedStroke> ownStrokes()
	{
		List<PartyCanvasSync.SyncedStroke> result = new ArrayList<>();
		for (List<Stroke> strokes : strokesByRegion.values())
		{
			for (Stroke stroke : strokes)
			{
				if (!stroke.pts.isEmpty())
				{
					result.add(PartyCanvasSync.toSynced(stroke, stroke.pts));
				}
			}
		}
		return result;
	}

	// New ids, over existing strokes in list order. Client thread only
	void importStrokes(List<PartyCanvasSync.SyncedStroke> strokes)
	{
		Set<Integer> changedRegions = new LinkedHashSet<>();
		List<Stroke> imported = new ArrayList<>();
		for (PartyCanvasSync.SyncedStroke synced : strokes)
		{
			if (synced.getPoints() == null || synced.getPoints().isEmpty())
			{
				continue;
			}
			Stroke stroke = new Stroke();
			stroke.color = new Color(synced.getColor(), true);
			stroke.strokeWidth = synced.getStrokeWidth();
			stroke.order = clock.next();
			stroke.plane = synced.getPlane();
			stroke.rainbow = synced.isRainbow();
			stroke.pts.addAll(StrokeCodec.simplify(synced.getPoints()));
			regionStrokes(stroke.primaryRegion()).add(stroke);
			changedRegions.add(stroke.primaryRegion());
			imported.add(stroke);
		}
		changedRegions.forEach(this::saveStrokes);
		partySync.onStrokesRestored(imported);
	}

	private void drawStrokes(Graphics2D graphics, int mx, int my)
	{
		WorldView worldView = client.getTopLevelWorldView();
		int[] regions = sceneRegions(worldView);
		if (regions.length == 0)
		{
			return;
		}

		// Toolbar stays sharp, so restore after
		Object oldAntialiasing = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		Object oldStrokeControl = graphics.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL);
		java.awt.Stroke oldStroke = graphics.getStroke();
		Composite oldComposite = graphics.getComposite();
		try
		{
			// One path per stroke, so crossings do not darken
			renderDistance = Math.max(0, config.renderDistance());
			cameraTileX = (int) Math.floor(client.getCameraFpX() / Perspective.LOCAL_TILE_SIZE);
			cameraTileY = (int) Math.floor(client.getCameraFpY() / Perspective.LOCAL_TILE_SIZE);
			transparentStrokes = config.transparentStrokes();
			if (transparentStrokes)
			{
				float opacity = Math.max(0f, Math.min(1f, config.strokeOpacity() / 100f));
				graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
			}
			if (config.antialiasing())
			{
				graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				// Default snaps line ends to pixel centers
				graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			}
			else
			{
				graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			}
			drawStrokes(graphics, worldView, regions, mx, my, config.lineStyle());
		}
		finally
		{
			if (oldAntialiasing != null)
			{
				graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAntialiasing);
			}
			if (oldStrokeControl != null)
			{
				graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, oldStrokeControl);
			}
			graphics.setStroke(oldStroke);
			graphics.setComposite(oldComposite);
		}
	}

	// Rebuilt only when scene base, size or border change
	private int[] sceneRegions = new int[0];
	private int sceneRegionsBaseX, sceneRegionsBaseY, sceneRegionsSizeX, sceneRegionsSizeY, sceneRegionsBorder;

	// Region ids of scene tiles plus NEARBY_REGIONS. Do not change the array
	// Instance stroke regions are in instance coordinates but getMapRegions() gives templates, so ids come from base and size
	private int[] sceneRegions(WorldView worldView)
	{
		int baseX = worldView.getBaseX();
		int baseY = worldView.getBaseY();
		int sizeX = worldView.getSizeX();
		int sizeY = worldView.getSizeY();
		int margin = NEARBY_REGIONS;
		// Extended scene regions too
		int border = loadedBorder(worldView);
		if (baseX == sceneRegionsBaseX && baseY == sceneRegionsBaseY && sizeX == sceneRegionsSizeX && sizeY == sceneRegionsSizeY
			&& border == sceneRegionsBorder)
		{
			return sceneRegions;
		}
		sceneRegionsBaseX = baseX;
		sceneRegionsBaseY = baseY;
		sceneRegionsSizeX = sizeX;
		sceneRegionsSizeY = sizeY;
		sceneRegionsBorder = border;

		if (sizeX <= 0 || sizeY <= 0)
		{
			sceneRegions = new int[0];
			return sceneRegions;
		}
		// Same formula as WorldPoint.getRegionID(). A region is 64 by 64 tiles, x and y 0 to 255
		int minRegionX = Math.max(0, ((baseX - border) >> 6) - margin);
		int maxRegionX = Math.min(0xFF, ((baseX + sizeX + border - 1) >> 6) + margin);
		int minRegionY = Math.max(0, ((baseY - border) >> 6) - margin);
		int maxRegionY = Math.min(0xFF, ((baseY + sizeY + border - 1) >> 6) + margin);
		int[] regions = new int[(maxRegionX - minRegionX + 1) * (maxRegionY - minRegionY + 1)];
		int i = 0;
		for (int regionX = minRegionX; regionX <= maxRegionX; regionX++)
		{
			for (int regionY = minRegionY; regionY <= maxRegionY; regionY++)
			{
				regions[i++] = regionX << 8 | regionY;
			}
		}
		sceneRegions = regions;
		return regions;
	}

	// Reused
	private final List<Stroke> visibleStrokes = new ArrayList<>();
	// Oldest strokes drop first. Members keep 100,000 points each, but a big party can pass this
	private static final int MAX_DRAWN_POINTS = 200_000;

	private void drawStrokes(Graphics2D graphics, WorldView worldView, int[] regions, int mx, int my, LineStyle style)
	{
		if (erasing)
		{
			eraseOwnStrokes(worldView, regions, pf(mx, my), style);
		}

		collectStrokes(worldView, regions, visibleStrokes);
		try
		{
			for (Stroke s : visibleStrokes)
			{
				// Renderer draws world strokes, only parts past it draw here
				if (!sceneStrokes.drewWhole(s))
				{
					drawStroke(graphics, worldView, s, style, sceneStrokes.drew(s));
				}
			}
		}
		finally
		{
			visibleStrokes.clear();
		}
	}

	// Oldest first, so new strokes are on top. Over MAX_DRAWN_POINTS the oldest drop
	private void collectStrokes(WorldView worldView, int[] regions, List<Stroke> out)
	{
		int plane = worldView.getPlane();
		for (int region : regions)
		{
			for (Stroke s : regionStrokes(region))
			{
				if ((showOwnStrokes || s == currentStroke) && s.plane == plane)
				{
					out.add(s);
				}
			}
		}
		partySync.forEachVisibleStroke(regions, plane, out::add);

		out.sort(Stroke.DRAW_ORDER);
		// Count from newest
		int first = out.size();
		int points = 0;
		while (first > 0 && points + out.get(first - 1).pts.size() <= MAX_DRAWN_POINTS)
		{
			first--;
			points += out.get(first).pts.size();
		}
		out.subList(0, first).clear();
	}

	// Reused
	private final List<Stroke> worldStrokes = new ArrayList<>();

	@Subscribe public void onBeforeRender(BeforeRender e)
	{
		WorldView worldView = client.getTopLevelWorldView();
		if (!config.drawInWorld() || client.getGameState() != GameState.LOGGED_IN || worldView == null)
		{
			sceneStrokes.off();
			return;
		}
		int[] regions = sceneRegions(worldView);
		collectStrokes(worldView, regions, worldStrokes);
		try
		{
			float opacity = Math.max(0f, Math.min(1f, config.strokeOpacity() / 100f));
			sceneStrokes.update(worldView, worldStrokes, currentStroke, config.scaleWidthWithZoom(), client.getScale(),
				config.lineStyle(), config.transparentStrokes(), opacity, Math.max(0, config.renderDistance()));
		}
		finally
		{
			worldStrokes.clear();
		}
	}

	// Set in render
	private int cursorTileX, cursorTileY;
	private pf cursorSub = pf(0, 0);

	private static final int CIRCLE_POINTS = 32;
	private final Path2D.Float circle = new Path2D.Float();

	// Radius in world units
	private void drawGroundCircle(Graphics2D graphics, int tileX, int tileY, pf sub, double radius)
	{
		WorldView worldView = client.getTopLevelWorldView();
		LocalPoint center = loadedLocalPoint(worldView, tileX, tileY, worldView.getPlane());
		if (center == null)
		{
			return;
		}
		double cx = center.getX() - Perspective.LOCAL_HALF_TILE_SIZE + sub.x * Perspective.LOCAL_TILE_SIZE;
		double cy = center.getY() - Perspective.LOCAL_HALF_TILE_SIZE + sub.y * Perspective.LOCAL_TILE_SIZE;
		circle.reset();
		boolean started = false;
		for (int i = 0; i <= CIRCLE_POINTS; i++)
		{
			double angle = 2 * Math.PI * i / CIRCLE_POINTS;
			LocalPoint lp = new LocalPoint((int) Math.round(cx + Math.cos(angle) * radius),
				(int) Math.round(cy + Math.sin(angle) * radius), worldView);
			Point p = Perspective.localToCanvas(client, lp, worldView.getPlane());
			if (p == null)
			{
				started = false;
				continue;
			}
			if (started)
			{
				circle.lineTo(p.getX(), p.getY());
			}
			else
			{
				circle.moveTo(p.getX(), p.getY());
				started = true;
			}
		}
		graphics.draw(circle);
	}

	private void eraseOwnStrokes(WorldView worldView, int[] regions, pf eraseAt, LineStyle style)
	{
		// Eraser skips other floors
		int plane = worldView.getPlane();
		for (int region : regions)
		{
			List<Stroke> strokes = regionStrokes(region);
			List<Stroke> toErase = null;
			for (Stroke s : strokes)
			{
				if ((showOwnStrokes || s == currentStroke) && s.plane == plane && buildPath(worldView, s, eraseAt, style, false))
				{
					if (toErase == null)
					{
						toErase = new ArrayList<>();
					}
					toErase.add(s);
				}
			}

			if (toErase != null)
			{
				strokes.removeAll(toErase);
				saveStrokes(region);
				partySync.onStrokesErased(toErase);
				recordErase(toErase);
			}
		}
	}

	private BasicStroke basicStroke = newBasicStroke(1f);

	private static BasicStroke newBasicStroke(float width)
	{
		return new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	}

	private BasicStroke basicStroke(double width)
	{
		if (basicStroke.getLineWidth() != (float) width)
		{
			basicStroke = newBasicStroke((float) width);
		}
		return basicStroke;
	}

	// Reused
	private final Path2D.Float path = new Path2D.Float();
	private final pf projected = pf(0, 0);
	// Run: consecutive on-screen points
	private float[] runX = new float[64];
	private float[] runY = new float[64];
	// Rainbow only, world units
	private float[] runDistances = new float[64];
	private int runLength = 0;
	// Rainbow only, endRun draws into this, not path
	private Graphics2D rainbowTo = null;

	private void addToRun(float x, float y, float distance)
	{
		if (runLength == runX.length)
		{
			runX = Arrays.copyOf(runX, runLength * 2);
			runY = Arrays.copyOf(runY, runLength * 2);
			runDistances = Arrays.copyOf(runDistances, runLength * 2);
		}
		runX[runLength] = x;
		runY[runLength] = y;
		runDistances[runLength] = distance;
		runLength++;
	}

	private void endRun(LineStyle style)
	{
		int n = runLength;
		runLength = 0;
		if (n == 0)
		{
			return;
		}

		if (rainbowTo != null)
		{
			drawRainbowRun(style, n);
			return;
		}

		float[] x = runX;
		float[] y = runY;
		path.moveTo(x[0], y[0]);
		if (n == 1)
		{
			// A very short round-capped line draws a dot
			path.lineTo(x[0] + 0.01f, y[0]);
		}
		else if (n == 2 || style == LineStyle.STRAIGHT)
		{
			for (int i = 1; i < n; i++)
			{
				path.lineTo(x[i], y[i]);
			}
		}
		else if (style == LineStyle.SMOOTH)
		{
			// Quadratic curves between segment midpoints
			path.lineTo((x[0] + x[1]) / 2, (y[0] + y[1]) / 2);
			for (int i = 1; i < n - 1; i++)
			{
				path.quadTo(x[i], y[i], (x[i] + x[i + 1]) / 2, (y[i] + y[i + 1]) / 2);
			}
			path.lineTo(x[n - 1], y[n - 1]);
		}
		else
		{
			// Catmull-Rom spline as cubic Beziers. End points repeat as their own neighbors
			for (int i = 0; i < n - 1; i++)
			{
				int prev = Math.max(i - 1, 0);
				int next = Math.min(i + 2, n - 1);
				path.curveTo(
					x[i] + (x[i + 1] - x[prev]) / 6, y[i] + (y[i + 1] - y[prev]) / 6,
					x[i + 1] - (x[next] - x[i]) / 6, y[i + 1] - (y[next] - y[i]) / 6,
					x[i + 1], y[i + 1]);
			}
		}
	}

	private void drawStroke(Graphics2D graphics, WorldView worldView, Stroke s, LineStyle style, boolean skipCovered)
	{
		graphics.setStroke(basicStroke(screenWidth(s.strokeWidth)));
		if (s.rainbow)
		{
			rainbowTo = graphics;
			try
			{
				buildPath(worldView, s, null, style, skipCovered);
			}
			finally
			{
				rainbowTo = null;
			}
			return;
		}
		buildPath(worldView, s, null, style, skipCovered);
		graphics.setColor(transparentStrokes ? s.color : s.opaqueColor());
		graphics.draw(path);
	}

	// Max piece length, keeps curves round
	private static final float RAINBOW_PIECE_PX = 3;
	// -1 if empty. Path draws when step changes
	private int rainbowStep = -1;
	private final Path2D.Float rainbowPath = new Path2D.Float();
	private float rainbowLastX, rainbowLastY;

	// Draws the run in rainbowTo with the endRun curves, color changing between end distances
	private void drawRainbowRun(LineStyle style, int n)
	{
		float[] x = runX;
		float[] y = runY;
		float[] d = runDistances;
		rainbowStep = -1;
		if (n == 1)
		{
			// A very short round-capped line draws a dot
			rainbowLine(x[0], y[0], x[0] + 0.01f, y[0], d[0], d[0]);
		}
		else if (n == 2 || style == LineStyle.STRAIGHT)
		{
			for (int i = 0; i < n - 1; i++)
			{
				rainbowLine(x[i], y[i], x[i + 1], y[i + 1], d[i], d[i + 1]);
			}
		}
		else if (style == LineStyle.SMOOTH)
		{
			// Midpoint distances
			rainbowLine(x[0], y[0], (x[0] + x[1]) / 2, (y[0] + y[1]) / 2, d[0], (d[0] + d[1]) / 2);
			for (int i = 1; i < n - 1; i++)
			{
				float x0 = (x[i - 1] + x[i]) / 2, y0 = (y[i - 1] + y[i]) / 2;
				float x1 = (x[i] + x[i + 1]) / 2, y1 = (y[i] + y[i + 1]) / 2;
				// Quadratic as cubic
				rainbowCurve(x0, y0, x0 + (x[i] - x0) * 2 / 3, y0 + (y[i] - y0) * 2 / 3,
					x1 + (x[i] - x1) * 2 / 3, y1 + (y[i] - y1) * 2 / 3, x1, y1,
					(d[i - 1] + d[i]) / 2, (d[i] + d[i + 1]) / 2);
			}
			rainbowLine((x[n - 2] + x[n - 1]) / 2, (y[n - 2] + y[n - 1]) / 2, x[n - 1], y[n - 1], (d[n - 2] + d[n - 1]) / 2, d[n - 1]);
		}
		else
		{
			for (int i = 0; i < n - 1; i++)
			{
				int prev = Math.max(i - 1, 0);
				int next = Math.min(i + 2, n - 1);
				rainbowCurve(x[i], y[i],
					x[i] + (x[i + 1] - x[prev]) / 6, y[i] + (y[i + 1] - y[prev]) / 6,
					x[i + 1] - (x[next] - x[i]) / 6, y[i + 1] - (y[next] - y[i]) / 6,
					x[i + 1], y[i + 1], d[i], d[i + 1]);
			}
		}
		flushRainbow();
	}

	private void rainbowLine(float x0, float y0, float x1, float y1, float d0, float d1)
	{
		rainbowCurve(x0, y0, x0 + (x1 - x0) / 3, y0 + (y1 - y0) / 3, x1 - (x1 - x0) / 3, y1 - (y1 - y0) / 3, x1, y1, d0, d1);
	}

	// Short pieces, colored by distance at the middle
	private void rainbowCurve(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, float d0, float d1)
	{
		// Control polygon is at least as long as the curve
		double length = Math.hypot(x1 - x0, y1 - y0) + Math.hypot(x2 - x1, y2 - y1) + Math.hypot(x3 - x2, y3 - y2);
		double steps = Math.abs(d1 - d0) / Rainbow.LOOP * Rainbow.STEPS;
		int pieces = (int) Math.min(1024, Math.max(1, Math.ceil(Math.max(length / RAINBOW_PIECE_PX, steps))));
		if (rainbowStep == -1)
		{
			rainbowLastX = x0;
			rainbowLastY = y0;
		}
		for (int j = 1; j <= pieces; j++)
		{
			float t = (float) j / pieces, u = 1 - t;
			float px = u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3;
			float py = u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3;
			int step = Rainbow.step(d0 + (d1 - d0) * (j - 0.5) / pieces);
			if (step != rainbowStep)
			{
				// New color, flush and continue
				flushRainbow();
				rainbowStep = step;
				rainbowPath.moveTo(rainbowLastX, rainbowLastY);
			}
			rainbowPath.lineTo(px, py);
			rainbowLastX = px;
			rainbowLastY = py;
		}
	}

	private void flushRainbow()
	{
		if (rainbowStep != -1)
		{
			rainbowTo.setColor(Rainbow.color(rainbowStep));
			rainbowTo.draw(rainbowPath);
		}
		rainbowPath.reset();
		rainbowStep = -1;
	}

	// True if near eraseAt, when set. skipCovered leaves out tiles the renderer draws in the world
	private boolean buildPath(WorldView worldView, Stroke s, pf eraseAt, LineStyle style, boolean skipCovered)
	{
		boolean hit = false;
		path.reset();
		runLength = 0;

		// Project each tile once
		int tileX = Integer.MIN_VALUE, tileY = Integer.MIN_VALUE;
		Polygon poly = null;
		float[] distances = rainbowTo != null ? s.distances() : null;
		List<StrokePoint> pts = s.pts;
		for (int i = 0; i < pts.size(); i++)
		{
			StrokePoint p = pts.get(i);
			if (p.getX() != tileX || p.getY() != tileY)
			{
				tileX = p.getX();
				tileY = p.getY();
				LocalPoint lp = isInRenderDistance(worldView, tileX, tileY) && !(skipCovered && sceneStrokes.coversTile(s, tileX, tileY))
					? loadedLocalPoint(worldView, tileX, tileY, s.plane) : null;
				poly = lp != null ? Perspective.getCanvasTilePoly(client, lp) : null;
			}

			if (poly == null)
			{
				// Ends the run, or a re-entering stroke would join across the gap
				endRun(style);
				continue;
			}
			pf screen = projectToScreen(p.getSubx(), p.getSuby(), poly, projected);
			// Radius 10 px, hits a wide stroke at its edge
			// First run point has no line before it, so test the point alone (one-dot strokes)
			if (eraseAt != null && !hit)
			{
				float fromX = runLength > 0 ? runX[runLength - 1] : screen.x;
				float fromY = runLength > 0 ? runY[runLength - 1] : screen.y;
				hit = Line2D.ptSegDist(fromX, fromY, screen.x, screen.y, eraseAt.x, eraseAt.y) < 10 + screenWidth(s.strokeWidth) / 2;
			}
			addToRun(screen.x, screen.y, distances != null ? distances[i] : 0);
		}
		endRun(style);
		return hit;
	}

	// Shared rectangle, use before the next call
	private Rectangle toolbarButtonVisibleBounds(int i) {
		drawBounds.setBounds(clickboxes[i]);
		drawBounds.grow(-1, -1);
		return drawBounds;
	}

	// New color, Reset palette, color slots, Decrease, Increase, then the size number
	private static final int TOP_ROW_BUTTONS = 2;
	private static final int SIZE_BUTTONS_START = TOP_ROW_BUTTONS + COLOR_SLOTS;
	private static final int SIZE_ROW = SLOTS_PER_COLUMN + 1;

	// Do not change the rectangle
	private Rectangle toolbarButtonClickbox(int i) {
		return clickboxes[i];
	}

	private static Rectangle[] layoutClickboxes(int count) {
		Rectangle[] boxes = new Rectangle[count];
		for (int i = 0; i < count; i++) {
			boxes[i] = layoutClickbox(i);
		}
		return boxes;
	}

	private static Rectangle layoutClickbox(int i) {
		int column, row;
		if (i < TOP_ROW_BUTTONS) {
			column = i;
			row = 0;
		} else if (i < SIZE_BUTTONS_START) {
			column = (i - TOP_ROW_BUTTONS) / SLOTS_PER_COLUMN;
			row = (i - TOP_ROW_BUTTONS) % SLOTS_PER_COLUMN + 1;
		} else if (i < SIZE_BUTTONS_START + 2) {
			column = i - SIZE_BUTTONS_START;
			row = SIZE_ROW;
		} else {
			// Size number spans both buttons above
			return new Rectangle(2, 40 + 20 * (SIZE_ROW + 1), 36, 16);
		}
		return new Rectangle(2 + 20 * column, 40 + 20 * row, 16, 16);
	}

	private int getMousedOverToolbarButton(int mx, int my) {
		for (int i = 0; i < buttons.size(); i++)
		{
			if (toolbarButtonClickbox(i).contains(mx, my)) {
				return i;
			}
		}
		return -1;
	}

	boolean drawMode = false;
	boolean drawing = false;
	boolean eraseMode = false;
	boolean erasing = false;
	boolean menuAllowsDraw = false;

	// Key repeat resends presses, so toggleModes toggles on the first
	private boolean drawKeyHeld = false;
	private boolean eraseKeyHeld = false;
	// Also consume the typed character
	private boolean consumeTyped = false;

	// Same test as render
	private boolean isToolbarShown()
	{
		return eraseMode || drawMode || drawing || erasing;
	}

	int draggedButtonIndex = -1;

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		if (e.getButton() != MouseEvent.BUTTON1) return e;

		// A press on an open menu picks an entry, not a drag
		draggedButtonIndex = -1;
		if (client.isMenuOpen()) return e;

		// Hidden toolbar takes no clicks
		if (isToolbarShown()) {
			draggedButtonIndex = getMousedOverToolbarButton(e.getX(), e.getY());
		}
		if (draggedButtonIndex != -1) {
			if (isSizeButton(draggedButtonIndex)) {
				// Time first, the client thread reads button then time
				heldSince = System.currentTimeMillis();
				heldSizeButton = draggedButtonIndex;
			}
			return e;
		}

		if (menuAllowsDraw) {
			if (drawMode) drawing = true;
			else if (eraseMode) erasing = true;
		}

		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		if (e.getButton() != MouseEvent.BUTTON1) return e;

		// A release on an open menu or hidden toolbar drops on no button
		int i = !client.isMenuOpen() && isToolbarShown() ? getMousedOverToolbarButton(e.getX(), e.getY()) : -1;
		if (i != -1 && draggedButtonIndex != -1) {
			buttons.get(i).onDrag(buttons.get(draggedButtonIndex));
		}

		// Always end drag, even if a menu opened
		draggedButtonIndex = -1;
		drawing = false;
		erasing = false;
		heldSizeButton = -1;
		return e;
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (config.drawKeybind().matches(e)) {
			if (!config.toggleModes()) drawMode = true;
			else if (!drawKeyHeld) setMode(!drawMode, false);
			drawKeyHeld = true;
		} else if (config.eraseKeybind().matches(e)) {
			if (!config.toggleModes()) eraseMode = true;
			else if (!eraseKeyHeld) setMode(false, !eraseMode);
			eraseKeyHeld = true;
		} else if (drawMode || eraseMode) {
			// Undo and redo only in draw or erase mode, else the keys go to the game
			if (config.undoKeybind().matches(e)) {
				e.consume();
				consumeTyped = true;
				clientThread.invoke(this::undo);
			} else if (config.redoKeybind().matches(e)) {
				e.consume();
				consumeTyped = true;
				clientThread.invoke(this::redo);
			}
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		if (config.drawKeybind().matches(e)) {
			drawKeyHeld = false;
			if (!config.toggleModes()) drawMode = false;
		} else if (config.eraseKeybind().matches(e)) {
			eraseKeyHeld = false;
			if (!config.toggleModes()) eraseMode = false;
		}
		// Keys like F5 or arrows type nothing, so a stale flag would eat the next character
		// Typed character comes before the release
		consumeTyped = false;
	}

	private void setMode(boolean draw, boolean erase)
	{
		drawMode = draw;
		eraseMode = erase;
		drawing = false;
		erasing = false;
	}

	@Subscribe public void onFocusChanged(FocusChanged e) {
		if (!e.isFocused()) {
			// Keys and mouse button can be released outside the client
			if (!config.toggleModes()) {
				drawMode = false;
				eraseMode = false;
			}
			drawKeyHeld = false;
			eraseKeyHeld = false;
			consumeTyped = false;
			drawing = false;
			erasing = false;
			heldSizeButton = -1;
		}
	}

	@Override public void keyTyped(KeyEvent e) {
		// Typed character must not reach the game
		if (consumeTyped) {
			consumeTyped = false;
			e.consume();
		}
	}
	@Override public MouseEvent mouseClicked(MouseEvent e) { return e; }
	@Override public MouseEvent mouseEntered(MouseEvent e) { return e; }
	@Override public MouseEvent mouseExited(MouseEvent e) { return e; }
	@Override public MouseEvent mouseDragged(MouseEvent e) { return e; }
	@Override public MouseEvent mouseMoved(MouseEvent e) { return e; }

	public void startUp() {
		showOwnStrokes = true;
		resetCanvas();

		Integer savedSize = configManager.getConfiguration(RsPaintConfig.GROUP, "strokeSize", Integer.class);
		strokeSize = savedSize != null ? Math.max(Stroke.MIN_WIDTH, Math.min(Stroke.MAX_WIDTH, savedSize)) : Stroke.MIN_WIDTH;

		String configuration = configManager.getConfiguration(RsPaintConfig.GROUP, "drawColors");
		Color[] savedArray = configuration != null ? gson.fromJson(configuration, Color[].class) : null;
		List<Color> saved = savedArray != null ? new ArrayList<>(Arrays.asList(savedArray)) : null;
		if (saved == null || isOldDefaultPalette(saved)) {
			useDefaultPalette();
			return;
		}
		while (saved.size() < COLOR_SLOTS) {
			saved.add(null);
		}
		colors = saved;
		rainbowSlots = loadRainbowSlots(colors.size());
	}

	private boolean[] loadRainbowSlots(int slots) {
		boolean[] loaded = new boolean[slots];
		String configuration = configManager.getConfiguration(RsPaintConfig.GROUP, "rainbowSlots");
		try {
			boolean[] saved = configuration != null ? gson.fromJson(configuration, boolean[].class) : null;
			if (saved != null) {
				System.arraycopy(saved, 0, loaded, 0, Math.min(slots, saved.length));
			}
		} catch (RuntimeException e) {
			log.warn("Cannot read the saved rainbow slots", e);
		}
		return loaded;
	}

	public void shutDown() {
		clientThread.invokeLater(this::resetCanvas);
	}

	// Singleton, so old profile or session strokes would be saved over the stored ones
	private void resetCanvas() {
		strokesByRegion.clear();
		instanceMap = null;
		templateStrokes.clear();
		unreadableRegions.clear();
		warnedRegions.clear();
		undoStack.clear();
		redoStack.clear();
		currentStroke = null;
		skippedPoint = null;
		currentErase = null;
	}

	@Subscribe public void onProfileChanged(ProfileChanged e) {
		clientThread.invoke(() -> {
			// New profile has own strokes, colors, size
			startUp();
			// Party members still have the old profile strokes
			partySync.onCanvasCleared();
			WorldView worldView = client.getTopLevelWorldView();
			if (client.getGameState() == GameState.LOGGED_IN && worldView != null) {
				loadScene(worldView);
			}
		});
	}

	private void useDefaultPalette() {
		colors = new ArrayList<>(Arrays.asList(DEFAULT_PALETTE));
		while (colors.size() < COLOR_SLOTS) {
			colors.add(null);
		}
		rainbowSlots = new boolean[colors.size()];
		saveColors();
	}

	// Saved palette is the old one-color default, so no color was chosen
	private static boolean isOldDefaultPalette(List<Color> saved) {
		for (int i = 0; i < saved.size(); i++) {
			Color color = saved.get(i);
			if (color != null && !(i == 0 && color.equals(DEFAULT_COLOR))) {
				return false;
			}
		}
		return true;
	}

	static float triangleArea(int x1, int y1, int x2, int y2, int x3, int y3)
	{
		// Float division keeps the half-pixel areas
		return Math.abs((x1 * (y2 - y3) + x2 * (y3 - y1) + x3 * (y1 - y2)) / 2f);
	}
}

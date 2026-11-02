package com.rspaint;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
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
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
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

	@Getter @Setter private volatile boolean showOwnStrokes = true;

	static class pf extends Point2D.Float { pf(float x, float y) { super(x, y);} }
	static pf pf(float x, float y) { return new pf(x, y); }

	private static final Type COLOR_LIST_TYPE = new TypeToken<List<Color>>(){}.getType();

	// Search order around the selected scene tile for the tile under the mouse.
	private static final int[] TILE_X_OFFSETS = {0, 1, 1, 0, -1, -1, -1, 0, 1,
		2, 2, 2, 1, 0, -1, -2, -2, -2, -2, -2, -1, 0, -1, 2, 2};
	private static final int[] TILE_Y_OFFSETS = {0, 0, 1, 1, 1, 0, -1, -1, -1,
		0, 1, 2, 2, 2, 2, 2, 1, 0, -1, -2, -2, -2, -2, -2, -1};

	static final Color DEFAULT_COLOR = Color.LIGHT_GRAY;

	// Two columns of color slots. The first column starts with the Paint palette.
	static final int SLOTS_PER_COLUMN = 10;
	static final int COLOR_SLOTS = SLOTS_PER_COLUMN * 2;
	static final Color[] DEFAULT_PALETTE = {
		new Color(0x000000), // black
		new Color(0xFFFFFF), // white
		new Color(0x7F7F7F), // gray
		new Color(0xED1C24), // red
		new Color(0xFF7F27), // orange
		new Color(0xFFF200), // yellow
		new Color(0x22B14C), // green
		new Color(0x00A2E8), // turquoise
		new Color(0x3F48CC), // indigo
		new Color(0xA349A4), // purple
	};

	@Inject
	public BGMIOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

//	List<Stroke> strokes = new ArrayList<>();
	Stroke currentStroke = null;

	// A new point is stored only when it is at least this many screen pixels from the last stored point.
	// This removes the one-pixel steps of a slow mouse. It is a constant, not a setting,
	// so that all clients store points with the same rule.
	private static final float MIN_POINT_SPACING_PX = 3f;

	// The newest point that was too near the last stored point. It is stored when the stroke ends,
	// so the stroke ends where the mouse button was released.
	private StrokePoint skippedPoint = null;

	private void addPoint(StrokePoint point)
	{
		currentStroke.pts.add(point);
		partySync.onPointAdded(currentStroke, point);
		skippedPoint = null;
	}

	private boolean isFarFromLastPoint(pf ground)
	{
		List<StrokePoint> pts = currentStroke.pts;
		if (pts.isEmpty())
		{
			return true;
		}
		// Project the last point with the current camera, so the spacing stays correct if the camera moves.
		pf last = toScreen(client.getTopLevelWorldView(), pts.get(pts.size() - 1));
		return last == null || last.distanceSq(ground) >= MIN_POINT_SPACING_PX * MIN_POINT_SPACING_PX;
	}

	/** Ends the current stroke, simplifies it and saves it. */
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
			// The stroke is complete. Fewer points make saves, full syncs, exports and drawing faster.
			int oldRegion = currentStroke.primaryRegion();
			currentStroke.pts = StrokeCodec.simplify(currentStroke.pts);
			// Simplify rounds the points, so the first point can move onto a tile in the next region.
			// Strokes are saved and found by their primary region, so the stroke must be in the list of that region.
			int newRegion = currentStroke.primaryRegion();
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

	// The most changes that undo can go back.
	private static final int MAX_UNDO = 100;

	/** One draw or one erase drag of the local player. A draw has one stroke. An erase has all strokes that one drag erased. */
	@RequiredArgsConstructor
	private static class CanvasChange
	{
		final boolean drawn;
		final List<Stroke> strokes;
	}

	// The newest change is first. Client thread only.
	private final Deque<CanvasChange> undoStack = new ArrayDeque<>();
	private final Deque<CanvasChange> redoStack = new ArrayDeque<>();
	// The change of the erase drag in progress, so that one drag undoes as one change.
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

	/** Undoes the newest draw or erase, and tells party members. Client thread only. */
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

	/** Does the newest undone change again, and tells party members. Client thread only. */
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

	/** Takes the newest change from the stack. Returns null if the stack is empty or a drag is in progress. */
	private CanvasChange takeChange(Deque<CanvasChange> stack)
	{
		if (drawing || erasing)
		{
			return null;
		}
		// The mouse button can be up before the next frame ends the stroke.
		finishStroke();
		currentErase = null;
		return stack.poll();
	}

	private void removeStrokes(List<Stroke> strokes)
	{
		Set<Integer> changedRegions = new LinkedHashSet<>();
		for (Stroke stroke : strokes)
		{
			// Find the stroke by its id. The region can be loaded again since the change, with new Stroke objects.
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
			// The stroke keeps its draw order, so it goes back to the same layer.
			regionStrokes(stroke.primaryRegion()).add(stroke);
			changedRegions.add(stroke.primaryRegion());
		}
		changedRegions.forEach(this::saveStrokes);
		partySync.onStrokesRestored(strokes);
	}

	/** Returns the screen position of the point with the current camera, or null if its tile is not on the screen. */
	private pf toScreen(WorldView worldView, StrokePoint p)
	{
		LocalPoint lp = LocalPoint.fromWorld(worldView, p.getX(), p.getY());
		Polygon poly = lp != null ? Perspective.getCanvasTilePoly(client, lp) : null;
		return poly != null ? projectToScreen(p.getSubx(), p.getSuby(), poly) : null;
	}

	// The stored width of new strokes. See screenWidth for the width on the screen.
	int strokeSize = Stroke.MIN_WIDTH;
	List<Color> colors = new ArrayList<>();
	int selectedColor = 0;

	// If "Scale width with zoom" is on, a stroke has its stored width in screen pixels at this camera scale.
	// 512 is the scale of the default zoom in fixed mode.
	private static final float REFERENCE_SCALE = 512f;

	// Multiplies the stored width of each stroke to get its width on the screen. Set at the start of each frame.
	private float widthScale = 1f;

	// If false, strokes are drawn fully opaque, also when their color is transparent. Set at the start of each frame.
	private boolean transparentStrokes = true;

	/** Returns the width on the screen of a stroke with this stored width. It is never less than 1 pixel. */
	private float screenWidth(double width)
	{
		return Math.max(1f, (float) width * widthScale);
	}

	private void changeStrokeSize(int change)
	{
		strokeSize = Math.max(Stroke.MIN_WIDTH, Math.min(Stroke.MAX_WIDTH, strokeSize + change));
		configManager.setConfiguration(RsPaintConfig.GROUP, "strokeSize", strokeSize);
	}

	// When the player holds down a stroke size button, the size starts to repeat after HOLD_DELAY_MS.
	// Each repeat comes sooner than the one before it, until one repeat on each client tick.
	private static final long HOLD_DELAY_MS = 400;
	private static final long HOLD_FIRST_INTERVAL_MS = 80;
	private static final long HOLD_MIN_INTERVAL_MS = 20;

	// The held stroke size button and when the press started, or -1. Written on the AWT thread.
	private volatile int heldSizeButton = -1;
	private volatile long heldSince;
	// Client thread only.
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
			// A new press. The click itself already changed the size once.
			repeatPressStart = pressStart;
			nextRepeatAt = pressStart + HOLD_DELAY_MS;
			repeatInterval = HOLD_FIRST_INTERVAL_MS;
		}

		long now = System.currentTimeMillis();
		// The repeat stops while the mouse is off the button, and starts again when it comes back.
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

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// The camera scale changes when the player zooms. The ground tiles on the screen get larger and smaller with it.
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

		boolean showUi = eDown || dDown || drawing || erasing;
		boolean overUi = showUi && getMousedOverToolbarButton(mx, my) != -1;

		Tile tile = dDown || drawing ? client.getTopLevelWorldView().getSelectedSceneTile() : null;
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
				ground = projectToScreen(sub.x, sub.y, poly);

				if (drawing) {
					int plane = client.getTopLevelWorldView().getPlane();
					if (currentStroke != null && currentStroke.plane != plane) {
						// The player went up or down a floor during the stroke. The rest of the line is a new stroke on the new floor.
						finishStroke();
					}
					StrokePoint point = new StrokePoint(tilex, tiley, sub.x, sub.y);
					if (currentStroke == null) {
						currentStroke = new Stroke();
						currentStroke.color = selectedColor();
						currentStroke.strokeWidth = strokeSize;
						currentStroke.order = clock.next();
						currentStroke.plane = plane;
						regionStrokes(new WorldPoint(tilex, tiley, 0).getRegionID()).add(currentStroke);
						addToHistory(new CanvasChange(true, List.of(currentStroke)));
						addPoint(point);
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
				if (eDown || erasing) {
					graphics.setColor(Color.PINK);
					graphics.draw(new Ellipse2D.Float(mx - 10, my - 10, 20, 20));
				} else if ((dDown || drawing) && ground != null) {
					graphics.setColor(selectedColor());
					float size = screenWidth(strokeSize);
					if (size <= 2) {
						graphics.drawRect((int) ground.x - 1, (int) ground.y - 1, 2, 2);
					} else {
						// A circle with the size of the brush on the screen.
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
				client.getMenu().createMenuEntry(-1).setOption("New color").onClick(me -> pickColor(-1));
			}
		});
		// Reset palette: a circular arrow.
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
		// Decrease stroke size: a minus sign.
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
		// Increase stroke size: a plus sign.
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
		// The current stroke size, as a number.
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

	// The click box of each toolbar button. The layout does not change, so the boxes are made once, not on each frame.
	// Do not change these rectangles.
	private final Rectangle[] clickboxes = layoutClickboxes(buttons.size());
	// The box that a button draws in. Reused for each button, because a button can change it. Client thread only.
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
			if (color != null) {
				g.setColor(color);
				g.fill(r);
			}
			g.setColor((mouseOver || colorIndex == selectedColor) ? Color.WHITE : Color.GRAY);
			g.draw(r);
		}

		@Override public void addMenuEntries() {
			Color color = colors.get(colorIndex);
			if (color != null) {
				client.getMenu().createMenuEntry(1).setOption(ColorUtil.wrapWithColorTag("Select color", color)).onClick(me -> selectedColor = colorIndex);
			}
			client.getMenu().createMenuEntry(1).setOption("Choose ").setTarget("new color").onClick(me -> pickColor(colorIndex));
		}

		@Override public void onDrag(ToolbarButton b) {
			if (b instanceof ColorToolbarButton) {
				int otherIndex = ((ColorToolbarButton) b).colorIndex;
				Color mine = colors.get(colorIndex);
				Color other = colors.get(otherIndex);
				colors.set(colorIndex, other);
				colors.set(otherIndex, mine);
				saveColors();
			}
		}
	}

	/** If index is -1, the new color goes in slot 0, the other colors move down one slot, and slot 0 becomes the selected color. */
	public void pickColor(int index) {
		Color color = colors.get(index == -1 ? selectedColor : index);
		SwingUtilities.invokeLater(() ->
		{
			// The picker shows the opacity of the color too. Strokes keep the opacity of their color.
			RuneliteColorPicker colorPicker = colorPickerManager.create(client,
				color != null ? color : Color.WHITE, "Paint color", false);
			colorPicker.setOnClose(c -> {
				if (index == -1) {
					for (int i = colors.size() - 1; i > 0; i--) {
						colors.set(i, colors.get(i - 1));
					}
					// The colors moved, so the old selected slot now has a different color. Select the new color.
					selectedColor = 0;
				}
				colors.set(index == -1 ? 0 : index, c);
				saveColors();
			});
			colorPicker.setVisible(true);
		});
	}

	private void saveColors()
	{
		configManager.setConfiguration(RsPaintConfig.GROUP, "drawColors", gson.toJson(colors));
	}

	@Subscribe public void onClientTick(ClientTick e) {
		partySync.flush(strokesByRegion);

		if (!dDown && !eDown && !drawing && !erasing || client.isMenuOpen()) return;

		Point mouse = client.getMouseCanvasPosition();
		repeatHeldSizeButton(mouse.getX(), mouse.getY());
		int button = getMousedOverToolbarButton(mouse.getX(), mouse.getY());
		if (button != -1) {
			client.getMenu().setMenuEntries(Arrays.stream(client.getMenu().getMenuEntries()).filter(me -> me.getType() == MenuAction.CANCEL).toArray(MenuEntry[]::new));
			buttons.get(button).addMenuEntries();
			return;
		}
		if ((dDown || eDown) && Arrays.stream(client.getMenu().getMenuEntries()).anyMatch(entry -> entry.getType() == MenuAction.WALK)) {
			client.getMenu().createMenuEntry(-1).setOption(dDown ? ColorUtil.wrapWithColorTag("Draw", selectedColor()) : "Erase").setType(MenuAction.CC_OP);
			drawable = true;
		} else {
			drawable = false;
		}
	}

	/** Returns the screen position of a sub-tile position, with sub-pixel precision, in a new pf. */
	private static pf projectToScreen(float subx, float suby, Polygon poly)
	{
		return projectToScreen(subx, suby, poly, pf(0, 0));
	}

	/** Puts the screen position of a sub-tile position in out, with sub-pixel precision, and returns out. */
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
		// Try the triangle made by corners 0, 1, 3 first, then the one made by corners 1, 2, 3.
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
		if (e.getGameState() == GameState.LOGGED_IN) return;

		int[] mapRegions = client.getMapRegions();
		if (mapRegions != null) loadStrokes(mapRegions);
	}

	Map<Integer, List<Stroke>> strokesByRegion = new HashMap<>();

	void loadStrokes(int[] regions) {
		Set<Integer> loadedRegions = strokesByRegion.keySet();
		Set<Integer> clientRegions = Arrays.stream(regions).boxed().collect(Collectors.toSet());
		List<Integer> regionsToUnload = new ArrayList<>(loadedRegions);
		regionsToUnload.removeAll(clientRegions);
		for (Integer region : regionsToUnload)
		{
			strokesByRegion.remove(region);
		}
		List<Integer> regionsToLoad = new ArrayList<>(clientRegions);
		// Keep the regions that are loaded already, so undo, redo and the party sync keep the same Stroke objects.
		regionsToLoad.removeAll(loadedRegions);
		for (Integer region : regionsToLoad)
		{
			strokesByRegion.put(region, readStrokes(region));
		}
	}

	/** Returns the strokes of the region. If the region is not loaded, loads it from the config first. */
	List<Stroke> regionStrokes(int regionId) {
		return strokesByRegion.computeIfAbsent(regionId, this::readStrokes);
	}

	// Regions whose saved strokes cannot be read, for example because a newer version of the plugin saved them.
	// These regions are not saved, so the saved strokes stay for the version that can read them.
	private final Set<Integer> unreadableRegions = new HashSet<>();

	private List<Stroke> readStrokes(int regionId) {
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

		List<Stroke> strokes = loaded.getStrokes();
		for (int i = 0; i < strokes.size(); i++)
		{
			// Strokes saved before the draw order existed have order 0. They keep their saved order, under all newer strokes.
			if (strokes.get(i).order <= 0) strokes.get(i).order = i + 1;
		}
		if (loaded.getVersion() < StrokeStorage.FORMAT_VERSION)
		{
			// Save the strokes again in the current format, so the old format is read only once.
			configManager.setConfiguration(RsPaintConfig.GROUP, key, storage.write(strokes));
		}
		if (!strokes.isEmpty())
		{
			// Party members get the strokes of the area that the player is in.
			partySync.requestFullSync();
		}
		return strokes;
	}

	void saveStrokes(int regionId) {
		List<Stroke> strokes = strokesByRegion.get(regionId);
		if (strokes == null || unreadableRegions.contains(regionId)) return;
		configManager.setConfiguration(RsPaintConfig.GROUP, "strokes_" + regionId, storage.write(strokes));
	}

	/**
	 * Deletes the local player's strokes in the loaded regions, the area around the player, and tells party members.
	 * Strokes in other regions stay saved.
	 */
	public void clearCanvas()
	{
		currentStroke = null;
		skippedPoint = null;
		for (Map.Entry<Integer, List<Stroke>> entry : strokesByRegion.entrySet())
		{
			if (!entry.getValue().isEmpty())
			{
				entry.getValue().clear();
				saveStrokes(entry.getKey());
			}
		}
		// A reset cannot be undone, so the history goes too.
		undoStack.clear();
		redoStack.clear();
		currentErase = null;
		partySync.onCanvasCleared();
	}

	/** Returns a copy of the local player's loaded strokes. Client thread only. */
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

	/**
	 * Adds the strokes to the local player's canvas with new ids, saves them, and sends them to the party. Client thread only.
	 * The strokes draw over all existing strokes, in the order of the list.
	 */
	void importStrokes(List<PartyCanvasSync.SyncedStroke> strokes)
	{
		Set<Integer> changedRegions = new LinkedHashSet<>();
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
			stroke.pts.addAll(StrokeCodec.simplify(synced.getPoints()));
			regionStrokes(stroke.primaryRegion()).add(stroke);
			changedRegions.add(stroke.primaryRegion());
		}
		changedRegions.forEach(this::saveStrokes);
		partySync.requestFullSync();
	}

	private void drawStrokes(Graphics2D graphics, int mx, int my)
	{
		WorldView worldView = client.getTopLevelWorldView();
		int[] regions = sceneRegions(worldView);
		if (regions.length == 0)
		{
			return;
		}

		// Turn on antialiasing and transparency only for the strokes, and restore the old state, so the toolbar stays sharp.
		Object oldAntialiasing = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		Object oldStrokeControl = graphics.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL);
		java.awt.Stroke oldStroke = graphics.getStroke();
		Composite oldComposite = graphics.getComposite();
		try
		{
			// Each stroke is one path that is drawn one time, so the parts of a stroke that cross do not get darker.
			// Each new stroke is drawn over the older strokes, so the colors of different strokes mix.
			// This is true for the opacity of the stroke color and for the opacity of all strokes.
			transparentStrokes = config.transparentStrokes();
			if (transparentStrokes)
			{
				float opacity = Math.max(0f, Math.min(1f, config.strokeOpacity() / 100f));
				graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
			}
			if (config.antialiasing())
			{
				graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				// Keep the float positions. The default moves line ends to pixel centers.
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

	// The ids of the regions in the scene, and the scene base and size they are for.
	// Made again only when the scene moves, so drawing does not make a new array on each frame.
	private int[] sceneRegions = new int[0];
	private int sceneRegionsBaseX, sceneRegionsBaseY, sceneRegionsSizeX, sceneRegionsSizeY;

	/**
	 * Returns the ids of all regions that have tiles in the scene. Do not change the array.
	 * A stroke is stored under the region of its world tile. Inside an instance, that is a region of the instance
	 * coordinates, but getMapRegions() gives the template regions. So the ids come from the scene base and size.
	 * Outside an instance, they are the same regions as getMapRegions() gives.
	 */
	private int[] sceneRegions(WorldView worldView)
	{
		int baseX = worldView.getBaseX();
		int baseY = worldView.getBaseY();
		int sizeX = worldView.getSizeX();
		int sizeY = worldView.getSizeY();
		if (baseX == sceneRegionsBaseX && baseY == sceneRegionsBaseY && sizeX == sceneRegionsSizeX && sizeY == sceneRegionsSizeY)
		{
			return sceneRegions;
		}
		sceneRegionsBaseX = baseX;
		sceneRegionsBaseY = baseY;
		sceneRegionsSizeX = sizeX;
		sceneRegionsSizeY = sizeY;

		if (sizeX <= 0 || sizeY <= 0)
		{
			sceneRegions = new int[0];
			return sceneRegions;
		}
		// The same formula as WorldPoint.getRegionID(). A region is 64 by 64 tiles.
		int minRegionX = baseX >> 6;
		int maxRegionX = (baseX + sizeX - 1) >> 6;
		int minRegionY = baseY >> 6;
		int maxRegionY = (baseY + sizeY - 1) >> 6;
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

	// The strokes to draw in this frame. Reused, so drawing does not make a new list on each frame.
	private final List<Stroke> visibleStrokes = new ArrayList<>();

	private void drawStrokes(Graphics2D graphics, WorldView worldView, int[] regions, int mx, int my, LineStyle style)
	{
		if (erasing)
		{
			eraseOwnStrokes(worldView, regions, pf(mx, my), style);
		}

		// Only the strokes on the player's floor show.
		int plane = worldView.getPlane();
		for (int region : regions)
		{
			for (Stroke s : regionStrokes(region))
			{
				if ((showOwnStrokes || s == currentStroke) && s.plane == plane)
				{
					visibleStrokes.add(s);
				}
			}
		}
		partySync.forEachVisibleStroke(regions, plane, visibleStrokes::add);

		// Draw the strokes of all players together, from the oldest to the newest, so that a new stroke is on top.
		// The strokes are mostly in order already, so the sort is fast.
		visibleStrokes.sort(Stroke.DRAW_ORDER);
		try
		{
			for (Stroke s : visibleStrokes)
			{
				drawStroke(graphics, worldView, s, style);
			}
		}
		finally
		{
			visibleStrokes.clear();
		}
	}

	/** Removes and saves the local player's shown strokes that are near the point, and tells party members. */
	private void eraseOwnStrokes(WorldView worldView, int[] regions, pf eraseAt, LineStyle style)
	{
		// Strokes on other floors do not show, so the eraser does not touch them.
		int plane = worldView.getPlane();
		for (int region : regions)
		{
			List<Stroke> strokes = regionStrokes(region);
			List<Stroke> toErase = null;
			for (Stroke s : strokes)
			{
				if ((showOwnStrokes || s == currentStroke) && s.plane == plane && buildPath(worldView, s, eraseAt, style))
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

	// Reused for each stroke, so drawing does not make new objects on each frame.
	private final Path2D.Float path = new Path2D.Float();
	// The screen position of one point in buildPath. Reused for each point.
	private final pf projected = pf(0, 0);
	// The screen positions of a run: points in a row that are all on the screen.
	private float[] runX = new float[64];
	private float[] runY = new float[64];
	private int runLength = 0;

	private void addToRun(float x, float y)
	{
		if (runLength == runX.length)
		{
			runX = Arrays.copyOf(runX, runLength * 2);
			runY = Arrays.copyOf(runY, runLength * 2);
		}
		runX[runLength] = x;
		runY[runLength] = y;
		runLength++;
	}

	/** Adds the run to the path with the line style, then empties the run. */
	private void endRun(LineStyle style)
	{
		int n = runLength;
		runLength = 0;
		if (n == 0)
		{
			return;
		}

		float[] x = runX;
		float[] y = runY;
		path.moveTo(x[0], y[0]);
		if (n == 1)
		{
			// A very short line with round caps draws a dot with the width of the stroke.
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
			// Each point is the control point of a quadratic curve between the midpoints of its two segments.
			path.lineTo((x[0] + x[1]) / 2, (y[0] + y[1]) / 2);
			for (int i = 1; i < n - 1; i++)
			{
				path.quadTo(x[i], y[i], (x[i] + x[i + 1]) / 2, (y[i] + y[i + 1]) / 2);
			}
			path.lineTo(x[n - 1], y[n - 1]);
		}
		else
		{
			// A Catmull-Rom spline, as cubic Bezier curves. The first and last points are used again as their own neighbors.
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

	private void drawStroke(Graphics2D graphics, WorldView worldView, Stroke s, LineStyle style)
	{
		buildPath(worldView, s, null, style);
		graphics.setColor(transparentStrokes ? s.color : s.opaqueColor());
		graphics.setStroke(basicStroke(screenWidth(s.strokeWidth)));
		graphics.draw(path);
	}

	/** Puts the stroke with the current camera in path. If eraseAt is not null, returns true when the stroke is near that point. */
	private boolean buildPath(WorldView worldView, Stroke s, pf eraseAt, LineStyle style)
	{
		boolean hit = false;
		path.reset();
		runLength = 0;

		// Consecutive points are often on the same tile, so project each tile once.
		int tileX = Integer.MIN_VALUE, tileY = Integer.MIN_VALUE;
		boolean inScene = false;
		Polygon poly = null;
		for (StrokePoint p : s.pts)
		{
			if (p.getX() != tileX || p.getY() != tileY)
			{
				tileX = p.getX();
				tileY = p.getY();
				LocalPoint lp = LocalPoint.fromWorld(worldView, tileX, tileY);
				inScene = lp != null;
				poly = inScene ? Perspective.getCanvasTilePoly(client, lp) : null;
			}

			if (!inScene)
			{
				continue;
			}
			if (poly == null)
			{
				// The tile is not on the screen, so the line breaks here.
				endRun(style);
				continue;
			}
			pf screen = projectToScreen(p.getSubx(), p.getSuby(), poly, projected);
			// The eraser has a radius of 10 pixels, so it hits a wide stroke at its edge.
			// The first point of a run has no line before it, so the test uses the point alone.
			// Thus the eraser also hits a stroke that is one dot.
			if (eraseAt != null && !hit)
			{
				float fromX = runLength > 0 ? runX[runLength - 1] : screen.x;
				float fromY = runLength > 0 ? runY[runLength - 1] : screen.y;
				hit = Line2D.ptSegDist(fromX, fromY, screen.x, screen.y, eraseAt.x, eraseAt.y) < 10 + screenWidth(s.strokeWidth) / 2;
			}
			addToRun(screen.x, screen.y);
		}
		endRun(style);
		return hit;
	}

	/** Returns the box that button i draws in. It is the same rectangle for each button, so use it before the next call. */
	private Rectangle toolbarButtonVisibleBounds(int i) {
		drawBounds.setBounds(clickboxes[i]);
		drawBounds.grow(-1, -1);
		return drawBounds;
	}

	// "New color" and "Reset palette" make the top row. The color slots go below them.
	// Under the color slots, "Decrease" and "Increase" stroke size make one row, and the size number is under them.
	private static final int TOP_ROW_BUTTONS = 2;
	private static final int SIZE_BUTTONS_START = TOP_ROW_BUTTONS + COLOR_SLOTS;
	private static final int SIZE_ROW = SLOTS_PER_COLUMN + 1;

	/** Returns the click box of button i. Do not change the rectangle. */
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
			// The size number is as wide as the two buttons above it.
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

	boolean dDown = false; // draw keybind is held, or draw mode is on if toggleModes is on.
	boolean drawing = false; // mouse is down and drawing.
	boolean eDown = false; // erase keybind is held, or erase mode is on if toggleModes is on.
	boolean erasing = false; // mouse is down and erasing.
	boolean drawable = false; // last menu entries contained WALK.

	// The draw and erase keys are down. Key repeat sends more presses, so toggleModes toggles only on the first one.
	private boolean drawKeyHeld = false;
	private boolean eraseKeyHeld = false;
	// Undo or redo consumed a key press, so its typed character must not reach the game either.
	private boolean consumeTyped = false;

	/** True if the toolbar is on the screen. This is the same test that render() uses. */
	private boolean isToolbarShown()
	{
		return eDown || dDown || drawing || erasing;
	}

	int toolbarButtonBeingDraggedIndex = -1;

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		if (e.getButton() != MouseEvent.BUTTON1) return e;

		// A press on an open menu picks a menu entry. It does not start a drag.
		toolbarButtonBeingDraggedIndex = -1;
		if (client.isMenuOpen()) return e;

		// The hidden toolbar takes no clicks.
		if (isToolbarShown()) {
			toolbarButtonBeingDraggedIndex = getMousedOverToolbarButton(e.getX(), e.getY());
		}
		if (toolbarButtonBeingDraggedIndex != -1) {
			if (isSizeButton(toolbarButtonBeingDraggedIndex)) {
				// Write the time first. The client thread reads the button first, then the time.
				heldSince = System.currentTimeMillis();
				heldSizeButton = toolbarButtonBeingDraggedIndex;
			}
			return e;
		}

		if (drawable) {
			if (dDown) drawing = true;
			else if (eDown) erasing = true;
		}

		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		if (e.getButton() != MouseEvent.BUTTON1) return e;

		// A release on an open menu or on the hidden toolbar does not drop on a button.
		int i = !client.isMenuOpen() && isToolbarShown() ? getMousedOverToolbarButton(e.getX(), e.getY()) : -1;
		if (i != -1 && toolbarButtonBeingDraggedIndex != -1) {
			buttons.get(i).onDrag(buttons.get(toolbarButtonBeingDraggedIndex));
		}

		// Always end the drag and the held size button, also if a menu opened during the press.
		toolbarButtonBeingDraggedIndex = -1;
		drawing = false;
		erasing = false;
		heldSizeButton = -1;
		return e;
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (config.drawKeybind().matches(e)) {
			if (!config.toggleModes()) dDown = true;
			else if (!drawKeyHeld) setMode(!dDown, false);
			drawKeyHeld = true;
		} else if (config.eraseKeybind().matches(e)) {
			if (!config.toggleModes()) eDown = true;
			else if (!eraseKeyHeld) setMode(false, !eDown);
			eraseKeyHeld = true;
		} else if (dDown || eDown) {
			// Undo and redo work only in draw mode or erase mode. In other modes the keys go to the game.
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
			if (!config.toggleModes()) dDown = false;
		} else if (config.eraseKeybind().matches(e)) {
			eraseKeyHeld = false;
			if (!config.toggleModes()) eDown = false;
		}
		// Some keys, for example F5 or the arrow keys, make no typed character. If the flag stays on,
		// keyTyped eats the next character that the player types. The typed character comes before the release.
		consumeTyped = false;
	}

	private void setMode(boolean draw, boolean erase)
	{
		dDown = draw;
		eDown = erase;
		drawing = false;
		erasing = false;
	}

	@Subscribe public void onFocusChanged(FocusChanged e) {
		if (!e.isFocused()) {
			// The keys and the mouse button can be released outside the client.
			if (!config.toggleModes()) {
				dDown = false;
				eDown = false;
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
		// Most key presses also make a typed character. It must not reach the game either.
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

		Integer savedSize = configManager.getConfiguration(RsPaintConfig.GROUP, "strokeSize", Integer.class);
		strokeSize = savedSize != null ? Math.max(Stroke.MIN_WIDTH, Math.min(Stroke.MAX_WIDTH, savedSize)) : Stroke.MIN_WIDTH;

		String configuration = configManager.getConfiguration(RsPaintConfig.GROUP, "drawColors");
		List<Color> saved = configuration != null ? gson.fromJson(configuration, COLOR_LIST_TYPE) : null;
		if (saved == null || isOldDefaultPalette(saved)) {
			useDefaultPalette();
			return;
		}
		while (saved.size() < COLOR_SLOTS) {
			saved.add(null);
		}
		colors = saved;
	}

	private void useDefaultPalette() {
		colors = new ArrayList<>(Arrays.asList(DEFAULT_PALETTE));
		while (colors.size() < COLOR_SLOTS) {
			colors.add(null);
		}
		saveColors();
	}

	/** True if the saved palette is the old one-color default, so the player never chose a color. */
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
		// Divide as a float. An integer division drops the half-pixel areas.
		return Math.abs((x1 * (y2 - y3) + x2 * (y3 - y1) + x3 * (y1 - y2)) / 2f);
	}
}

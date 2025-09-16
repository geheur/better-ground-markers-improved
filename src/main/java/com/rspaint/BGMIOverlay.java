package com.rspaint;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rspaint.RsPaintPlugin.Stroke;
import com.rspaint.RsPaintPlugin.StrokePoint;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ColorUtil;

public class BGMIOverlay extends Overlay implements MouseListener, KeyListener
{
	@Inject Client client;
	@Inject RsPaintPlugin plugin;
	@Inject private RsPaintConfig config;
	@Inject private ConfigManager configManager;
	@Inject private Gson gson;
	@Inject private ColorPickerManager colorPickerManager;

	static class pf extends Point2D.Float { pf(float x, float y) { super(x, y);} }
	static pf pf(float x, float y) { return new pf(x, y); }
	static pf pf(Point p) { return new pf(p.getX(), p.getY()); }

	@Inject
	public BGMIOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

//	List<Stroke> strokes = new ArrayList<>();
	Stroke currentStroke = null;

	int strokeWidth = 10; // in tenths.
	List<Color> colors = new ArrayList<>();
	int selectedColor = 0;

	Graphics2D graphics = null;

	Point lastMouseCanvasPosition = null;

	@Override
	public Dimension render(Graphics2D graphics)
	{
		this.graphics = graphics;
		boolean debug = false;

		int mx = client.getMouseCanvasPosition().getX();
		int my = client.getMouseCanvasPosition().getY();

		// Ui hit test.
		boolean showUi = eDown || dDown || drawing || erasing;
		boolean overUi = false;
		if (showUi) {
			for (int i = 0; i < buttons.size(); i++) {
				Rectangle r = toolbarButtonClickbox(i);
				if (r.contains(mx, my)) {
					overUi = true;
					break;
				}
			}
		}

		// get tile.
		Tile tile = client.getTopLevelWorldView().getSelectedSceneTile();
		pf ground = null;

		if (tile != null)
		{
			int[] xdiffs = new int[]{0, 1, 1, 0, -1, -1, -1, 0, 1,
				2, 2, 2, 1, 0, -1, -2, -2, -2, -2, -2, -1, 0, -1, 2, 2};
			int[] ydiffs = new int[]{0, 0, 1, 1, 1, 0, -1, -1, -1,
				0, 1, 2, 2, 2, 2, 2, 1, 0, -1, -2, -2, -2, -2, -2, -1};
			Polygon poly = null;
			int tilex = -1;
			int tiley = -1;
			for (int i = 0; i < xdiffs.length; i++)
			{
				LocalPoint plus = tile.getLocalLocation().plus(xdiffs[i] * 128, ydiffs[i] * 128);
//			System.out.println(plus);
				Polygon test = Perspective.getCanvasTilePoly(client, plus);
				if (!overUi && debug && showUi) graphics.draw(test);
				if (test != null && test.contains(mx, my))
				{
//				System.out.println("found matching polygon after " + i + " polygons");
					poly = test;
					tilex = tile.getWorldLocation().getX() + xdiffs[i];
					tiley = tile.getWorldLocation().getY() + ydiffs[i];
					break;
				}
			}

			// barycenter calculations
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
				ground = projectToScreen(sub.x, sub.y, x0, y0, x1, y1, x2, y2, x3, y3);

//			if (sub.x > 1.05 || sub.x < -0.05 || sub.y > 1.05 || sub.y < -0.05) System.out.println("here " + sub.x + " " + sub.y);

				if (drawing) {
					if (currentStroke == null) {
						currentStroke = new Stroke();
						currentStroke.color = colors.get(selectedColor);
						currentStroke.strokeWidth = 1.0f;//config.drawStrokeWidth();
						strokesByRegion.computeIfAbsent(new WorldPoint(tilex, tiley, 0).getRegionID(), k -> new ArrayList<>()).add(currentStroke);
					}
					currentStroke.pts.add(new StrokePoint(tilex, tiley, sub.x, sub.y));
				} else {
					if (currentStroke != null) saveStrokes(currentStroke.primaryRegion());
					currentStroke = null;
				}
			}
		}

		drawStrokes(graphics);

		if (showUi) {
			if (!overUi) {
				if (eDown || erasing) {
					graphics.setColor(Color.PINK);
					graphics.draw(new Ellipse2D.Float(mx - 10, my - 10, 20, 20));
				} else if ((dDown || drawing) && ground != null) {
					graphics.setColor(colors.get(selectedColor));
					graphics.drawRect((int) ground.x - 1, (int) ground.y - 1, 2, 2);
				}
			}

			for (int i = 0; i < buttons.size(); i++)
			{
				Rectangle rectangle = toolbarButtonClickbox(i);
				boolean mouseOver = rectangle.contains(mx, my);
				buttons.get(i).draw(graphics, toolbarButtonVisibleBounds(i), mouseOver);
			}
		}

		lastMouseCanvasPosition = client.getMouseCanvasPosition();
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
				client.createMenuEntry(-1).setOption("New color").onClick(me -> pickColor(-1));
			}
		});
		for (int i = 0; i < 10; i++) {
			buttons.add(new ColorToolbarButton(i));
		}
	}

	class ToolbarButton {
		public void draw(Graphics2D g, Rectangle r, boolean mouseOver) {
			if (mouseOver) {
				g.setColor(Color.WHITE);
				Rectangle r2 = new Rectangle(r);
				r2.grow(1, 1);
				g.draw(r);
			} else {
				g.setColor(Color.LIGHT_GRAY);
			}
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
				client.createMenuEntry(1).setOption(ColorUtil.wrapWithColorTag("Select color", color)).onClick(me -> {
					selectedColor = colorIndex;
				});
			}
			client.createMenuEntry(1).setOption("Choose ").setTarget("new color").onClick(me -> {
				pickColor(colorIndex);
			});
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

	/** index == -1 means put it at index 0 and shift all others down. */
	public void pickColor(int index) {
		Color color = colors.get(index == -1 ? selectedColor : index);
		SwingUtilities.invokeLater(() ->
		{
			RuneliteColorPicker colorPicker = colorPickerManager.create(client,
				color != null ? color : Color.decode("#FFFFFF"), "Item color", true);
			colorPicker.setOnClose(c -> {
				if (index == -1) {
					for (int i = 9; i > 0; i--) {
						colors.set(i, colors.get(i - 1));
					}
				}
				colors.set(index == -1 ? 0 : index, c);
				saveColors();
			});
			colorPicker.setVisible(true);
		});
	}

	private void saveColors()
	{
		configManager.setConfiguration("rspaint", "drawColors", gson.toJson(colors));
	}

	@Subscribe public void onClientTick(ClientTick e) {
		if (!dDown && !eDown && !drawing && !erasing || client.isMenuOpen()) return;

		int x = client.getMouseCanvasPosition().getX();
		int y = client.getMouseCanvasPosition().getY();
		for (int i = 0; i < buttons.size(); i++)
		{
			Rectangle r = toolbarButtonClickbox(i);
			if (r.contains(x, y)) {
				client.setMenuEntries(Arrays.stream(client.getMenuEntries()).filter(me -> me.getType() == MenuAction.CANCEL).collect(Collectors.toList()).toArray(new MenuEntry[]{}));
				buttons.get(i).addMenuEntries();
				return;
			}
		}
		if ((dDown || eDown) && Arrays.stream(client.getMenuEntries()).anyMatch(entry -> entry.getType() == MenuAction.WALK)) {
			client.createMenuEntry(-1).setOption(dDown ? ColorUtil.wrapWithColorTag("Draw", colors.get(selectedColor)) : "Erase").setType(MenuAction.CC_OP);
			drawable = true;
		} else {
			drawable = false;
		}
//		System.out.println(Arrays.stream(client.getMapRegions()).boxed().collect(Collectors.toList()));
	}

	private pf projectToScreen(float subx, float suby, int x0, int y0, int x1, int y1, int x2, int y2, int x3, int y3)
	{
		float screenx, screeny;
		if (subx + suby <= 1)
		{
			screenx = (int) (x0 + (x1 - x0) * subx + (x3 - x0) * suby);
			screeny = (int) (y0 + (y1 - y0) * subx + (y3 - y0) * suby);
		}
		else
		{
			subx = 1 - subx;
			suby = 1 - suby;
			screenx = (int) (x2 + (x3 - x2) * subx + (x1 - x2) * suby);
			screeny = (int) (y2 + (y3 - y2) * subx + (y1 - y2) * suby);
		}
		return pf(screenx, screeny);
	}

	private pf getBarycentre(int mx, int my, int x0, int y0, int x1, int y1, int x2, int y2, int x3, int y3)
	{
		float tilesuby;
		float tilesubx;
		pf sub;
		// triangle 013
		float
			area13p = triangleArea(x1, y1, x3, y3, mx, my),
			area03p = triangleArea(x0, y0, x3, y3, mx, my),
			area01p = triangleArea(x0, y0, x1, y1, mx, my),
			area013 = triangleArea(x0, y0, x1, y1, x3, y3);
		float
			swb0 = area13p / area013,
			swb1 = area03p / area013,
			swb3 = area01p / area013,
			swbSum = swb0 + swb1 + swb3;
//			System.out.println("sw triangle barycentric: " + swb0 + " " + swb1 + " " + swb3 + " (" + swbSum + ")");
		if (swbSum > 1f)
		{
			// try triangle 123
			float
				area23p = triangleArea(x2, y2, x3, y3, mx, my),
				area31p = triangleArea(x3, y3, x1, y1, mx, my),
				area12p = triangleArea(x1, y1, x2, y2, mx, my),
				area123 = triangleArea(x1, y1, x2, y2, x3, y3);
			float
				neb1 = area23p / area123,
				neb2 = area31p / area123,
				neb3 = area12p / area123,
				nebSum = neb1 + neb2 + neb3;
//				System.out.println("ne triangle barycentric: " + neb1 + " " + neb2 + " " + neb3 + " (" + nebSum + ")");
			tilesubx = 1 - neb3;
			tilesuby = 1 - neb1;
		}
		else
		{
			tilesubx = swb1;
			tilesuby = swb3;
		}
		sub = pf(tilesubx, tilesuby);
		return sub;
	}

//	@RequiredArgsConstructor
//	static class RegionStrokes {
//		final List<Stroke> strokes;
//		final Collection<Integer> bonusRegions;
//	}
//
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
		regionsToUnload.removeAll(loadedRegions);
		for (Integer region : regionsToLoad)
		{
			String conf = configManager.getConfiguration("rspaint", "strokes_" + region);
			List<Stroke> strokes = conf == null ? new ArrayList<>() : gson.fromJson(conf, new TypeToken<List<Stroke>>() {}.getType());
			strokesByRegion.put(region, strokes);
		}
	}

	void saveStrokes(int regionId) {
		List<Stroke> strokes = strokesByRegion.get(regionId);
		if (strokes == null) return;
		configManager.setConfiguration("rspaint", "strokes_" + regionId, gson.toJson(strokes));
	}

	private void drawStrokes(Graphics2D graphics)
	{
		List<Stroke> toErase = new ArrayList<>();
		int lastx = -1, lasty = -1;
		int count = 0;
		for (Map.Entry<Integer, List<Stroke>> entry : strokesByRegion.entrySet())
		{
			List<Stroke> strokes = entry.getValue();
			for (Stroke s : strokes)
			{
				graphics.setColor(s.color);
				graphics.setStroke(new BasicStroke((float) s.strokeWidth));
				lastx = lasty = -1;
				for (StrokePoint p : s.pts)
				{
					count++;
					LocalPoint lp = LocalPoint.fromWorld(client, p.getX(), p.getY());
					if (lp == null)
					{
						continue;
					}

					Polygon poly = Perspective.getCanvasTilePoly(client, lp); // TODO how can this be null?
					if (poly == null)
					{
						lastx = lasty = -1;
						continue;
					}
					int
						x0 = poly.xpoints[0],
						y0 = poly.ypoints[0],
						x1 = poly.xpoints[1],
						y1 = poly.ypoints[1],
						x2 = poly.xpoints[2],
						y2 = poly.ypoints[2],
						x3 = poly.xpoints[3],
						y3 = poly.ypoints[3];
					float
						sx = p.getSubx() * 128 / 128,
						sy = p.getSuby() * 128 / 128;
	//					sx = ((int) (p.getSubx() * 128f)) / 128f,
	//					sy = ((int) (p.getSuby() * 128f)) / 128f;
					int screenx, screeny;
					if (sx + sy <= 1)
					{
						screenx = (int) (x0 + (x1 - x0) * sx + (x3 - x0) * sy);
						screeny = (int) (y0 + (y1 - y0) * sx + (y3 - y0) * sy);
					}
					else
					{
						sx = 1 - sx;
						sy = 1 - sy;
						screenx = (int) (x2 + (x3 - x2) * sx + (x1 - x2) * sy);
						screeny = (int) (y2 + (y3 - y2) * sx + (y1 - y2) * sy);
					}
					if (lastx != -1)
					{
						if (erasing)
						{
							if (erasing && closestDistance(pf(lastx, lasty), pf(screenx, screeny), pf(client.getMouseCanvasPosition())) < 10)
							{
								toErase.add(s);
							}
						}
						graphics.drawLine(lastx, lasty, screenx, screeny);
					}
					lastx = screenx;
					lasty = screeny;
				}
			}

			if (!toErase.isEmpty()) {
				strokes.removeAll(toErase);
				saveStrokes(entry.getKey());
			}
		}
	}

	public double closestDistance(pf A, pf B, pf C) {
		if (A.equals(B)) return A.distance(C);
		// Compute vectors AC and AB
		pf AC = sub(C, A);
		pf AB = sub(B, A);

		// Get point D by taking the projection of AC onto AB then adding the offset of A
		pf D = add(proj(AC, AB), A);
//		graphics.drawRect((int) A.x, (int) A.y, 2, 2);
//		graphics.drawRect((int) B.x, (int) B.y, 2, 2);
//		graphics.drawRect((int) C.x, (int) C.y, 2, 2);
//		graphics.drawRect((int) D.x, (int) D.y, 2, 2);

		pf AD = sub(D, A);
		// D might not be on AB so calculate k of D down AB (aka solve AD = k * AB)
		// We can use either component, but choose larger value to reduce the chance of dividing by zero
		float k = Math.abs(AB.x) > Math.abs(AB.y) ? AD.x / AB.x : AD.y / AB.y;

		// Check if D is off either end of the line segment
		if (k <= 0.0) {
			return Math.sqrt(hypot2(C, A));
		} else if (k >= 1.0) {
			return Math.sqrt(hypot2(C, B));
		}

//		graphics.drawLine((int) A.x, (int) A.y, (int) B.x, (int) B.y);
//		graphics.drawLine((int) C.x, (int) C.y, (int) D.x, (int) D.y);
		return Math.sqrt(hypot2(C, D));
	}

	private pf add(pf a, pf b)
	{
		return pf(a.x + b.x, a.y + b.y);
	}

	private pf sub(pf a, pf b)
	{
		return pf(a.x - b.x, a.y - b.y);
	}

	private pf proj(pf a, pf b)
	{
		float k = ((float) dot(a, b)) / dot(b, b);
		return pf((int) (k * b.x), (int) (k * b.y));
	}

	private float dot(pf a, pf b)
	{
		return a.x * b.x + a.y * b.y;
	}

	private float hypot2(pf a, pf b)
	{
		return dot(sub(a, b), sub(a, b));
	}

	private Rectangle toolbarButtonVisibleBounds(int i) {
		Rectangle r = toolbarButtonClickbox(i);
		r.grow(-1, -1);
		return r;
	}

	private Rectangle toolbarButtonClickbox(int i) {
		return new Rectangle(2, 40 + 20 * i, 16, 16);
	}

	private int getMousedOverToolbarButton(int mx, int my) {
		for (int i = 0; i < 10; i++)
		{
			Rectangle rectangle = toolbarButtonClickbox(i);
			if (rectangle.contains(mx, my)) {
				return i;
			}
		}
		return -1;
	}

	boolean dDown = false; // draw keybind is held.
	boolean drawing = false; // mouse is down and drawing.
	boolean eDown = false; // erase keybind is held.
	boolean erasing = false; // mouse is down and erasing.
	boolean drawable = false; // last menu entries contained WALK.

	int toolbarButtonBeingDraggedIndex = -1;

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		if (e.getButton() != MouseEvent.BUTTON1 || client.isMenuOpen()) return e;

		toolbarButtonBeingDraggedIndex = -1;
		for (int i = 0; i < buttons.size(); i++) {
			if (toolbarButtonClickbox(i).contains(e.getX(), e.getY())) {
				toolbarButtonBeingDraggedIndex = i;
				return e;
			}
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

		int i = getMousedOverToolbarButton(e.getX(), e.getY());
		if (i != -1 && toolbarButtonBeingDraggedIndex != -1) {
			buttons.get(i).onDrag(buttons.get(toolbarButtonBeingDraggedIndex));
		}

		drawing = false;
		erasing = false;
		return e;
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (config.drawKeybind().matches(e)) {
			dDown = true;
		} else if (config.eraseKeybind().matches(e)) {
			eDown = true;
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		if (config.drawKeybind().matches(e)) {
			dDown = false;
		} else if (config.eraseKeybind().matches(e)) {
			eDown = false;
		}
	}

	@Subscribe public void onFocusChanged(FocusChanged e) {
		if (!e.isFocused()) {
			dDown = false;
			eDown = false;
			drawing = false;
			erasing = false;
		}
	}

	@Override public void keyTyped(KeyEvent e) { }
	@Override public MouseEvent mouseClicked(MouseEvent e) { return e; }
	@Override public MouseEvent mouseEntered(MouseEvent e) { return e; }
	@Override public MouseEvent mouseExited(MouseEvent e) { return e; }
	@Override public MouseEvent mouseDragged(MouseEvent e) { return e; }
	@Override public MouseEvent mouseMoved(MouseEvent e) { return e; }

	public void startUp() {
		String configuration = configManager.getConfiguration("rspaint", "drawColors");
		if (configuration != null)
		{
			List<Color> colorsFromConfig = gson.fromJson(configuration, new TypeToken<List<Color>>() {}.getType());
			for (int i = colorsFromConfig.size(); i < 10; i++) {
				colorsFromConfig.add(null);
			}
			this.colors = colorsFromConfig;
		} else {
			colors = new ArrayList<>();
			colors.add(Color.LIGHT_GRAY);
			for (int i = 0; i < 9; i++) {
				colors.add(null);
			}
			saveColors();
		}
	}

	static float triangleArea(int x1, int y1, int x2, int y2, int x3, int y3)
	{
		return Math.abs((x1 * (y2 - y3) + x2 * (y3 - y1) + x3 * (y1 - y2)) / 2);
	}
}

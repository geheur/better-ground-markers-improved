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
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;

import com.rspaint.ui.ColorToolbarButton;
import com.rspaint.ui.NewColorToolbarButton;
import com.rspaint.ui.ToolbarButton;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
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

    // State variables
    boolean dDown = false;      // draw keybind is held.
    boolean drawing = false;    // mouse is down and drawing.
    boolean eDown = false;      // erase keybind is held.
    boolean erasing = false;    // mouse is down and erasing.
    boolean drawable = false;   // last menu entries contained WALK.
    Stroke currentStroke = null;

    // Stroke size (in tenths)
    int strokeWidth = 10;
    // Designated "New Color" button
    NewColorToolbarButton newColorToolbarButton = new NewColorToolbarButton(0);
    // List of colors in our arsenal
    List<ColorToolbarButton> colorPalette = new ArrayList<>();

    Graphics2D graphics = null;
    Point lastMouseCanvasPosition = null;

    Map<Integer, List<Stroke>> strokesByRegion = new HashMap<>();

    // Unused but required overrides
    @Override public void keyReleased(KeyEvent e) { }
    @Override public void keyTyped(KeyEvent e) { }
    @Override public MouseEvent mouseClicked(MouseEvent e) { return e; }
    @Override public MouseEvent mouseEntered(MouseEvent e) { return e; }
    @Override public MouseEvent mouseExited(MouseEvent e) { return e; }
    @Override public MouseEvent mouseDragged(MouseEvent e) { return e; }
    @Override public MouseEvent mouseMoved(MouseEvent e) { return e; }

    @Inject
	public BGMIOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

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
            if (newColorToolbarButton.mouseOver(client)){
                overUi = true;
            }
            for (ColorToolbarButton button : colorPalette){
                if (button.mouseOver(client)){
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
				Polygon test = Perspective.getCanvasTilePoly(client, plus);
				if (!overUi && debug && showUi) graphics.draw(test);
				if (test != null && test.contains(mx, my))
				{
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

				if (drawing) {
					if (currentStroke == null) {
						currentStroke = new Stroke();
                        currentStroke.color = null;
                        for (ColorToolbarButton button : colorPalette){
                            if (button.isSelected()){
                                currentStroke.color = button.getColor();
                            }
                        }
						currentStroke.strokeWidth = 1.0f;
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
                    // Draw the eraser circle
					graphics.setColor(Color.PINK);
					graphics.draw(new Ellipse2D.Float(mx - 10, my - 10, 20, 20));
				} else if ((dDown || drawing) && ground != null) {
                    // Draw brush point
                    for (ColorToolbarButton button : colorPalette){
                        if (button.isSelected()){
                            graphics.setColor(button.getColor());
                        }
                    }
					graphics.drawRect((int) ground.x - 1, (int) ground.y - 1, 2, 2);
				}
			}

            // Draw overlay buttons
            newColorToolbarButton.draw(graphics, client);
            drawPalette();

		}

		lastMouseCanvasPosition = client.getMouseCanvasPosition();
		return null;
	}


    // Save the colors to the file
	private void saveColors()
	{
        ArrayList<Color> colors = new ArrayList<>();
        for (ColorToolbarButton button : colorPalette){
            if (button.getColor() != null) {
                colors.add(button.getColor());
            }
        }
		configManager.setConfiguration("rspaint", "drawColors", gson.toJson(colors));
	}

    // Do this every tick
	@Subscribe public void onClientTick(ClientTick e) {
		if (!dDown && !eDown && !drawing && !erasing || client.isMenuOpen()) return;

        Color selectedColor = Color.WHITE;

        // If a new color has been requested, add it to the list and make it active
        if (newColorToolbarButton.getNewColor() != null){
            colorPalette.remove(colorPalette.size() - 1);
            for (ColorToolbarButton button : colorPalette){
                button.setIndex(button.getIndex() + 1);
                button.setSelected(false);
            }
            ColorToolbarButton buttonToAdd = new ColorToolbarButton(1, newColorToolbarButton.getNewColor());
            buttonToAdd.setSelected(true);
            colorPalette.add(0, buttonToAdd);
            newColorToolbarButton.setNewColor(null);
        }

        // Add menu entries
        if (newColorToolbarButton.mouseOver(client)) {
            client.createMenuEntry(-1).setOption("New color").onClick(me -> pickColor(newColorToolbarButton));

        }

        // For each of the buttons
        for (ColorToolbarButton button : colorPalette){
            if (button.mouseOver(client)){
                client.setMenuEntries(
                        Arrays.stream(
                                client.getMenuEntries()
                        ).filter(
                                me -> me.getType() == MenuAction.CANCEL
                        ).collect(Collectors.toList()
                        ).toArray(new MenuEntry[]{})
                );

                client.createMenuEntry(1).setOption(ColorUtil.wrapWithColorTag("Select color", button.getColor())).onClick(me -> {
                    button.setSelected(true);
                });
            }
            selectedColor = button.isSelected() ? button.getColor() : Color.WHITE;
        }

		if ((dDown || eDown) && Arrays.stream(client.getMenuEntries()).anyMatch(entry -> entry.getType() == MenuAction.WALK)) {
			client.createMenuEntry(-1).setOption(dDown ? ColorUtil.wrapWithColorTag("Draw", selectedColor) : "Erase").setType(MenuAction.CC_OP);
			this.drawable = true;
		} else {
			this.drawable = false;
		}
	}

    public void pickColor(NewColorToolbarButton button){
        SwingUtilities.invokeLater(() ->
        {
            RuneliteColorPicker colorPicker = colorPickerManager.create(client, Color.decode("#FFFFFF"),
                    "Select color", true);
            colorPicker.setOnClose(button::setNewColor);
            colorPicker.setVisible(true);
        });
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

	@Subscribe public void onGameStateChanged(GameStateChanged e) {
		if (e.getGameState() == GameState.LOGGED_IN) return;

		int[] mapRegions = client.getMapRegions();
		if (mapRegions != null) loadStrokes(mapRegions);
	}

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

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		if (e.getButton() != MouseEvent.BUTTON1 || client.isMenuOpen()) return e;

        if (newColorToolbarButton.mouseOver(client)){
            pickColor(newColorToolbarButton);
        }

        boolean anyButtonMouseover = false;
        // Update selection state
        for (ColorToolbarButton button : colorPalette){
            if (button.mouseOver(client)){
                anyButtonMouseover = true;
            }
        }
        for (ColorToolbarButton button : colorPalette){
            if (button.mouseOver(client)){
                button.setSelected(true);
            }
            else{
                if (anyButtonMouseover) {
                    button.setSelected(false);
                }
            }
        }

		if (this.drawable) {
			if (dDown) drawing = true;
			else if (eDown) erasing = true;
		}

		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		if (e.getButton() != MouseEvent.BUTTON1) return e;

        // Get the currently selected button
        ColorToolbarButton selectedButton = null;
        for  (ColorToolbarButton button : colorPalette){
            if (button.isSelected()) {
                selectedButton = button;
            }
        }
        // If you've dragged the mouse onto an unselected color, reorder them
        for  (ColorToolbarButton button : colorPalette){
            if (selectedButton != null && button.mouseOver(client)) {
                if (!button.isSelected()){
                    button.onDrag(selectedButton);
                }
            }
        }

        // Stop drawing
		drawing = false;
		erasing = false;
		return e;
	}

    // Toggle draw mode
	@Override
	public void keyPressed(KeyEvent e)
	{
		if (config.drawKeybind().matches(e)) {
            dDown = dDown ?  false : true;
            eDown = false;
		} else if (config.eraseKeybind().matches(e)) {
			eDown = eDown ? false : true;
            dDown = false;
		}
	}

    // Draws the color palette
    public void drawPalette(){
        for (ColorToolbarButton button : colorPalette){
            button.draw(graphics, client);
        }
    }

    // Initialize buttons and get config info
	public void startUp() {
		String configuration = configManager.getConfiguration("rspaint", "drawColors");

        List<Color> colorsFromConfig;

		if (configuration != null)
		{
            // Init colors from config
			colorsFromConfig = gson.fromJson(configuration, new TypeToken<List<Color>>() {}.getType());

            // Fill in any empties
			for (int i = colorsFromConfig.size(); i < 10; i++) {
				colorsFromConfig.add(null);
			}

            // Assign colors to buttons
            for (int i = 0; i < colorsFromConfig.size(); i++){
                colorPalette.add(new ColorToolbarButton(i + 1, colorsFromConfig.get(i)));
            }

		} else {
            // No config available? Make one.
            colorsFromConfig = new ArrayList<Color>();
            colorsFromConfig.add(Color.LIGHT_GRAY);
            for (int i = 0; i < 9; i++) {
                colorsFromConfig.add(null);
            }
            // Assign colors to buttons
            for (int i = 0; i < colorsFromConfig.size(); i++){
                colorPalette.add(new ColorToolbarButton(i + 1, colorsFromConfig.get(i)));
            }
			saveColors();
		}
	}

	static float triangleArea(int x1, int y1, int x2, int y2, int x3, int y3)
	{
		return Math.abs((x1 * (y2 - y3) + x2 * (y3 - y1) + x3 * (y1 - y2)) / 2);
	}
}

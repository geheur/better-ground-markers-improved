package com.rspaint;

import com.google.inject.Provides;
import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.api.Constants;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Filepath;

@PluginDescriptor(
	name = "RsPaint",
	description = "Draw on the ground.",
	tags = {"paint", "draw", "ground", "marker", "tile", "color", "art"},
	internalName = "rspaint"
)
public class RsPaintPlugin extends Plugin
{
	@Provides RsPaintConfig provideConfig(ConfigManager configManager) { return configManager.getConfig(RsPaintConfig.class); }
	@Inject KeyManager keyManager;
	@Inject MouseManager mouseManager;
	@Inject BGMIOverlay bgmiOverlay;
	@Inject EventBus eventBus;
	@Inject OverlayManager overlayManager;
	@Inject PartyCanvasSync partySync;
	@Inject RsPaintPanel panel;
	@Inject ClientToolbar clientToolbar;
	@Inject SceneStrokes sceneStrokes;
	@Inject ClientThread clientThread;

	private NavigationButton navigationButton;

	/** Returns the data folder of the plugin, .runelite/plugin-data/rspaint. Do not call it on the client thread. */
	Filepath dataDirectory() throws IOException
	{
		return getPluginDirectory();
	}

	@Override
	protected void startUp() throws Exception
	{
		sceneStrokes.startUp();
		eventBus.register(sceneStrokes);
		bgmiOverlay.startUp();
		overlayManager.add(bgmiOverlay);
		mouseManager.registerMouseListener(bgmiOverlay);
		keyManager.registerKeyListener(bgmiOverlay);
		eventBus.register(bgmiOverlay);

		partySync.startUp();
		eventBus.register(partySync);

		eventBus.register(panel);
		navigationButton = NavigationButton.builder()
			.tooltip("RsPaint")
			.icon(RsPaintPanel.createIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		panel.refresh();
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(bgmiOverlay);
		mouseManager.unregisterMouseListener(bgmiOverlay);
		keyManager.unregisterKeyListener(bgmiOverlay);
		eventBus.unregister(bgmiOverlay);
		bgmiOverlay.shutDown();

		// The strokes in the world are scene objects, which only the client thread can take out of the scene.
		eventBus.unregister(sceneStrokes);
		sceneStrokes.shutDown();
		clientThread.invokeLater(sceneStrokes::clearAll);

		eventBus.unregister(partySync);
		partySync.shutDown();

		// All members show again on the next start, so the panel must rebuild its switches.
		eventBus.unregister(panel);
		panel.reset();
		clientToolbar.removeNavigation(navigationButton);
	}

	@Value
	static class StrokePoint {
		int x, y;
		float subx, suby;
	}

	static class Stroke {
		// The stored width is in screen pixels. If "Scale width with zoom" is on, the overlay changes it with the zoom.
		static final int MIN_WIDTH = 1;
		static final int MAX_WIDTH = 100;

		/** Returns the width, or 1 if the width is not a number from MIN_WIDTH to MAX_WIDTH. */
		static double validWidth(double width)
		{
			return width >= MIN_WIDTH && width <= MAX_WIDTH ? width : 1;
		}

		static boolean isValidPlane(int plane)
		{
			return plane >= 0 && plane < Constants.MAX_Z;
		}

		/** From the stroke that draws first, at the bottom, to the stroke that draws last, on top. */
		static final Comparator<Stroke> DRAW_ORDER = Comparator.comparingLong((Stroke s) -> s.order).thenComparing(s -> s.id);

		// A random 63-bit number in base 36. It is shorter than a UUID, and only one member's strokes must not collide.
		// Strokes saved before ids existed get a new id when they load.
		String id = Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, Character.MAX_RADIX);
		List<StrokePoint> pts = new ArrayList<>();
		Color color;
		double strokeWidth;
		// The draw order from StrokeClock. It is 0 for strokes saved before the draw order existed.
		long order;
		// The plane (floor) that the stroke was drawn on. The stroke shows only while the player is on this plane.
		// Strokes saved before the plane existed load on plane 0.
		int plane;
		// The color without its opacity. Made when first needed, so drawing does not make a new Color on each frame.
		// Transient, so it is not saved with the stroke.
		private transient Color opaqueColor;

		/** Returns the color with full opacity. The color of a stroke does not change after the stroke is made. */
		Color opaqueColor()
		{
			if (opaqueColor == null)
			{
				opaqueColor = color.getAlpha() == 255 ? color : new Color(color.getRGB(), false);
			}
			return opaqueColor;
		}

		public int primaryRegion()
		{
			return new WorldPoint(pts.get(0).getX(), pts.get(0).getY(), 0).getRegionID();
		}
	}

	/**
	 * Gives each stroke a draw order. A stroke with a higher order draws over a stroke with a lower order.
	 *
	 * <p>The order of a new stroke is the current time in milliseconds, but always higher than the order of
	 * all strokes that this client already has. Thus a new stroke draws over all strokes that the player can see,
	 * also when the clocks of party members are not the same. Party members send the order with each stroke,
	 * so all clients draw the strokes in the same order.
	 *
	 * <p>Client thread only.
	 */
	@Singleton
	static class StrokeClock
	{
		// Larger orders are not accepted from party members, so a bad value cannot make the clock overflow.
		private static final long MAX_ORDER = 1L << 53;

		private long last = 0;

		/** Returns the order for a new stroke. */
		long next()
		{
			last = Math.max(last + 1, System.currentTimeMillis());
			return last;
		}

		/**
		 * Returns the order to use for a stroke from a party member, and moves the clock past it.
		 * If the order is not valid, for example because the member has an old version of the plugin, returns a new order.
		 */
		long receive(long order)
		{
			if (order <= 0 || order >= MAX_ORDER)
			{
				return next();
			}
			last = Math.max(last, order);
			return order;
		}

		/**
		 * Moves the clock past the order of a saved stroke, so new strokes draw over it. A saved order can be in the future,
		 * for example because a party member with a fast clock moved the clock forward in an earlier session.
		 * Orders that are not valid do not change the clock, and the stroke keeps its order.
		 */
		void advancePast(long order)
		{
			if (order > 0 && order < MAX_ORDER)
			{
				last = Math.max(last, order);
			}
		}
	}
}

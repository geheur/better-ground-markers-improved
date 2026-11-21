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

	// Disk access, not for the client thread
	Filepath dataDirectory() throws IOException
	{
		return getPluginDirectory();
	}

	@Override
	protected void startUp() throws Exception
	{
		// Client thread, so after a quick restart this runs after the queued shutdown
		clientThread.invoke(() ->
		{
			sceneStrokes.startUp();
			eventBus.register(sceneStrokes);
			bgmiOverlay.startUp();
			overlayManager.add(bgmiOverlay);
			eventBus.register(bgmiOverlay);

			partySync.startUp();
			eventBus.register(partySync);
		});
		mouseManager.registerMouseListener(bgmiOverlay);
		keyManager.registerKeyListener(bgmiOverlay);

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
		mouseManager.unregisterMouseListener(bgmiOverlay);
		keyManager.unregisterKeyListener(bgmiOverlay);
		// Canvas, scene objects and party state are client thread only
		clientThread.invoke(() ->
		{
			overlayManager.remove(bgmiOverlay);
			eventBus.unregister(bgmiOverlay);
			bgmiOverlay.shutDown();

			eventBus.unregister(sceneStrokes);
			sceneStrokes.shutDown();
			sceneStrokes.clearAll();

			eventBus.unregister(partySync);
			partySync.shutDown();
		});

		// Members show again on next start
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
		// Screen pixels
		static final int MIN_WIDTH = 1;
		static final int MAX_WIDTH = 100;

		static double validWidth(double width)
		{
			return width >= MIN_WIDTH && width <= MAX_WIDTH ? width : 1;
		}

		static boolean isValidPlane(int plane)
		{
			return plane >= 0 && plane < Constants.MAX_Z;
		}

		static final Comparator<Stroke> DRAW_ORDER = Comparator.comparingLong((Stroke s) -> s.order).thenComparing(s -> s.id);

		// Old strokes get an id on load
		String id = newId();
		// Instance copy of a saved template stroke (InstanceMap), shared across places
		transient String templateId;
		List<StrokePoint> pts = new ArrayList<>();
		Color color;
		double strokeWidth;
		// From StrokeClock, 0 for old strokes
		long order;
		// Old strokes load on plane 0
		int plane;
		// Color is Rainbow.FIRST
		boolean rainbow;
		// Rainbow.distances cache for first distancesKnown points of distancesFor
		private transient float[] distances;
		private transient List<StrokePoint> distancesFor;
		private transient int distancesKnown;
		private transient Color opaqueColor;

		static String newId()
		{
			return Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, Character.MAX_RADIX);
		}

		Color opaqueColor()
		{
			if (opaqueColor == null)
			{
				opaqueColor = color.getAlpha() == 255 ? color : new Color(color.getRGB(), false);
			}
			return opaqueColor;
		}

		// Client thread only
		float[] distances()
		{
			if (distancesFor != pts || distancesKnown > pts.size())
			{
				// Simplify replaced the list
				distancesFor = pts;
				distancesKnown = 0;
			}
			if (distancesKnown < pts.size() || distances == null)
			{
				distances = Rainbow.distances(pts, distances, distancesKnown);
				distancesKnown = pts.size();
			}
			return distances;
		}

		public int primaryRegion()
		{
			return new WorldPoint(pts.get(0).getX(), pts.get(0).getY(), 0).getRegionID();
		}
	}

	// Draw order is time in ms, bumped above all known orders for skewed party clocks. Client thread only
	@Singleton
	static class StrokeClock
	{
		// Larger orders from party are rejected
		private static final long MAX_ORDER = 1L << 53;

		private long last = 0;

		long next()
		{
			last = Math.max(last + 1, System.currentTimeMillis());
			return last;
		}

		// Invalid orders (old plugin versions) get a new one
		long receive(long order)
		{
			if (order <= 0 || order >= MAX_ORDER)
			{
				return next();
			}
			last = Math.max(last, order);
			return order;
		}

		// Saved order can be in the future from a fast party clock
		void advancePast(long order)
		{
			if (order > 0 && order < MAX_ORDER)
			{
				last = Math.max(last, order);
			}
		}
	}
}

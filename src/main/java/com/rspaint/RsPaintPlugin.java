package com.rspaint;

import com.google.inject.Provides;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "RsPaint",
	description = "draw on the floor",
	tags = {"paint", "draw", "ground", "marker", "tile", "color", "art"}
)
public class RsPaintPlugin extends Plugin
{
	@Inject Client client;
	@Provides RsPaintConfig provideConfig(ConfigManager configManager) { return configManager.getConfig(RsPaintConfig.class); }
	@Inject RsPaintConfig config;
	@Inject KeyManager keyManager;
	@Inject MouseManager mouseManager;
	@Inject BGMIOverlay bgmiOverlay;
	@Inject EventBus eventBus;
	@Inject OverlayManager overlayManager;

	@Override
	protected void startUp() throws Exception
	{
		bgmiOverlay.startUp();
		overlayManager.add(bgmiOverlay);
		mouseManager.registerMouseListener(bgmiOverlay);
		keyManager.registerKeyListener(bgmiOverlay);
		eventBus.register(bgmiOverlay);
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(bgmiOverlay);
		mouseManager.unregisterMouseListener(bgmiOverlay);
		keyManager.unregisterKeyListener(bgmiOverlay);
		eventBus.unregister(bgmiOverlay);
		overlayManager.remove(bgmiOverlay);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
	}

	@Value
	static class StrokePoint {
		int x, y;
		float subx, suby;
	}

	static class Stroke {
		List<StrokePoint> pts = new ArrayList<>();
		Color color;
		double strokeWidth;
//		List<Integer> regions = new ArrayList<>();

		public int primaryRegion()
		{
			return new WorldPoint(pts.get(0).getX(), pts.get(0).getY(), 0).getRegionID();
		}
	}
}

package com.rspaint;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Constants;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(RsPaintConfig.GROUP)
public interface RsPaintConfig extends Config
{
	// Changing this loses saved strokes, colors and sizes
	String GROUP = "rspaint";

	@ConfigItem(
		keyName = "drawKeybind",
		name = "Draw keybind",
		description = "Hold this key and drag the mouse to draw, or press it to switch draw mode on and off when Toggle draw and erase is on.",
		position = 0
	)
	default Keybind drawKeybind()
	{
		return new Keybind(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK);
	}

	@ConfigItem(
		keyName = "eraseKeybind",
		name = "Erase keybind",
		description = "Hold this key and drag the mouse to erase, or press it to switch erase mode on and off when Toggle draw and erase is on.",
		position = 1
	)
	default Keybind eraseKeybind()
	{
		return new Keybind(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK);
	}

	@ConfigItem(
		keyName = "undoKeybind",
		name = "Undo keybind",
		description = "Undoes your last line or erase while you're drawing or erasing.",
		position = 2
	)
	default Keybind undoKeybind()
	{
		return new Keybind(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
	}

	@ConfigItem(
		keyName = "redoKeybind",
		name = "Redo keybind",
		description = "Redoes the last change you undid while you're drawing or erasing.",
		position = 3
	)
	default Keybind redoKeybind()
	{
		return new Keybind(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
	}

	@ConfigItem(
		keyName = "toggleModes",
		name = "Toggle draw and erase",
		description = "Press the draw or erase key to turn its mode on and press it again to turn it off, instead of holding it down.",
		position = 4
	)
	default boolean toggleModes()
	{
		return true;
	}

	@ConfigSection(
		name = "Rendering",
		description = "These only change how strokes look on your screen, not what your party sees.",
		position = 5
	)
	String renderingSection = "rendering";

	@ConfigItem(
		keyName = "drawInWorld",
		name = "Draw in the world",
		description = "With the GPU plugin or 117 HD, strokes are drawn into the game world, so players, NPCs, objects and projectiles show in front of them and strokes keep their width on the ground. Otherwise strokes are drawn over the game.",
		section = renderingSection,
		position = 0
	)
	default boolean drawInWorld()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lineStyle",
		name = "Line style",
		description = "Straight joins the points with straight lines, Smooth curves near the points to hide small wobbles, and Smooth through points curves through every point.",
		section = renderingSection,
		position = 1
	)
	default LineStyle lineStyle()
	{
		return LineStyle.STRAIGHT;
	}

	@ConfigItem(
		keyName = "antialiasing",
		name = "Smooth edges",
		description = "Antialiases strokes drawn over the game. Strokes in the world use the anti-aliasing setting of the GPU plugin or 117 HD instead.",
		section = renderingSection,
		position = 2
	)
	default boolean antialiasing()
	{
		return true;
	}

	@ConfigItem(
		keyName = "scaleWidthWithZoom",
		name = "Scale width with zoom",
		description = "Makes strokes wider as you zoom in and thinner as you zoom out so they keep their size on the ground, with the toolbar size matching the default zoom in fixed mode.",
		section = renderingSection,
		position = 3
	)
	default boolean scaleWidthWithZoom()
	{
		return true;
	}

	@ConfigItem(
		keyName = "transparentStrokes",
		name = "Transparent strokes",
		description = "Lets the game show through transparent colors, and through every stroke when Stroke opacity is under 100%. A stroke doesn't darken where it crosses itself, but its color mixes with older strokes it crosses. When this is off, all strokes are opaque.",
		section = renderingSection,
		position = 4
	)
	default boolean transparentStrokes()
	{
		return true;
	}

	@Range(min = 10, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "strokeOpacity",
		name = "Stroke opacity",
		description = "Scales the opacity of every stroke color when Transparent strokes is on, so at 100% each stroke keeps the opacity of its color.",
		section = renderingSection,
		position = 5
	)
	default int strokeOpacity()
	{
		return 100;
	}

	@Range(min = 0, max = Constants.EXTENDED_SCENE_SIZE)
	@ConfigItem(
		keyName = "renderDistance",
		name = "Render distance",
		description = "Only shows strokes within this many tiles of the camera, like Draw Distance in the GPU plugin, or on every loaded tile at 0.",
		section = renderingSection,
		position = 6
	)
	default int renderDistance()
	{
		return 0;
	}

	@RequiredArgsConstructor
	enum LineStyle
	{
		STRAIGHT("Straight"),
		SMOOTH("Smooth"),
		THROUGH_POINTS("Smooth through points");

		private final String displayName;

		@Override
		public String toString()
		{
			return displayName;
		}
	}
}

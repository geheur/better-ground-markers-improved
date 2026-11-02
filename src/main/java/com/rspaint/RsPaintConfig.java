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
	// Saved strokes, colors and the stroke size are in this group too, so it must not change.
	String GROUP = "rspaint";

	@ConfigItem(
		keyName = "drawKeybind",
		name = "Draw keybind",
		description = "Hold this key and click and drag to draw. If Toggle draw and erase is on, press it to turn draw mode on or off."
	)
	default Keybind drawKeybind()
	{
		return new Keybind(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK);
	}

	@ConfigItem(
		keyName = "eraseKeybind",
		name = "Erase keybind",
		description = "Hold this key and click and drag to erase. If Toggle draw and erase is on, press it to turn erase mode on or off."
	)
	default Keybind eraseKeybind()
	{
		return new Keybind(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK);
	}

	@ConfigItem(
		keyName = "toggleModes",
		name = "Toggle draw and erase",
		description = "Press the draw or erase key once to turn the mode on, and again to turn it off, instead of holding the key."
	)
	default boolean toggleModes()
	{
		return false;
	}

	@ConfigItem(
		keyName = "undoKeybind",
		name = "Undo keybind",
		description = "In draw mode or erase mode, press this key to undo your last line or erase."
	)
	default Keybind undoKeybind()
	{
		return new Keybind(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
	}

	@ConfigItem(
		keyName = "redoKeybind",
		name = "Redo keybind",
		description = "In draw mode or erase mode, press this key to redo the last change that you undid."
	)
	default Keybind redoKeybind()
	{
		return new Keybind(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK);
	}

	@ConfigSection(
		name = "Rendering",
		description = "These settings change only how strokes look on your screen. They do not change the strokes that party members get.",
		position = 2
	)
	String renderingSection = "rendering";

	@ConfigItem(
		keyName = "antialiasing",
		name = "Smooth edges",
		description = "Draws strokes with antialiasing (soft line edges).",
		section = renderingSection,
		position = 0
	)
	default boolean antialiasing()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lineStyle",
		name = "Line style",
		description = "Straight connects the points with straight lines. Smooth draws curves near the points and hides small steps. Smooth through points draws curves that touch each point.",
		section = renderingSection,
		position = 1
	)
	default LineStyle lineStyle()
	{
		return LineStyle.SMOOTH;
	}

	@ConfigItem(
		keyName = "transparentStrokes",
		name = "Transparent strokes",
		description = "Lets the game show through strokes that have a transparent color, and through all strokes if Stroke opacity is less than 100%. Where a stroke crosses itself, it does not get darker. Where a stroke crosses an older stroke, the colors mix. If this is off, all strokes are opaque.",
		section = renderingSection,
		position = 2
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
		description = "Changes the opacity of all strokes, if Transparent strokes is on. It multiplies the opacity of each stroke color. At 100%, each stroke keeps the opacity of its color.",
		section = renderingSection,
		position = 3
	)
	default int strokeOpacity()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "scaleWidthWithZoom",
		name = "Scale width with zoom",
		description = "Makes strokes wider when you zoom in and thinner when you zoom out, so that they keep their size on the ground. The stroke size on the toolbar is the width at the default zoom in fixed mode.",
		section = renderingSection,
		position = 4
	)
	default boolean scaleWidthWithZoom()
	{
		return true;
	}

	@Range(min = 0, max = Constants.EXTENDED_SCENE_SIZE)
	@ConfigItem(
		keyName = "renderDistance",
		name = "Render distance",
		description = "Strokes show only on tiles that are up to this many tiles from the camera, like Draw Distance in the GPU plugin. At 0, strokes show on all loaded tiles.",
		section = renderingSection,
		position = 5
	)
	default int renderDistance()
	{
		return 0;
	}

	/** How a stroke connects its points on the screen. It changes only the drawing, not the stored points. */
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

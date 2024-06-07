package com.rspaint;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

@ConfigGroup("rspaint")
public interface RsPaintConfig extends Config
{
	@ConfigItem(
		keyName = "drawKeybind",
		name = "Draw keybind",
		description = "Hold this key and click and drag to draw."
	)
	default Keybind drawKeybind()
	{
		return new Keybind(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK);
	}

	@ConfigItem(
		keyName = "eraseKeybind",
		name = "Erase keybind",
		description = "Hold this key and click and drag to erase."
	)
	default Keybind eraseKeybind()
	{
		return new Keybind(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK);
	}
}

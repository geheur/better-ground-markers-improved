package com.rspaint;

import static org.junit.Assert.assertTrue;

import java.awt.Color;
import net.runelite.api.JagexColor;
import org.junit.Test;

public class FlatModelTest
{
	private static final float[] GAMMAS = {1f, 0.9f, 0.8f, 0.7f, 0.6f};

	/** The largest difference of one channel, from 0 to 255, between the color and what the renderer shows for hsl. */
	private static float error(Color color, int hsl, float gamma)
	{
		float[] shown = FlatModel.rgb(hsl);
		int[] wanted = {color.getRed(), color.getGreen(), color.getBlue()};
		float max = 0;
		for (int c = 0; c < 3; c++)
		{
			max = Math.max(max, Math.abs((float) Math.pow(shown[c], gamma) * 255 - wanted[c]));
		}
		return max;
	}

	/** The redmean distance that FlatModel.nearestHsl makes smallest. */
	private static float distance(Color color, int hsl, float gamma)
	{
		float[] shown = FlatModel.rgb(hsl);
		float r = color.getRed() / 255f, g = color.getGreen() / 255f, b = color.getBlue() / 255f;
		float sr = (float) Math.pow(shown[0], gamma), sg = (float) Math.pow(shown[1], gamma), sb = (float) Math.pow(shown[2], gamma);
		float redMean = (sr + r) / 2;
		return (2 + redMean) * (sr - r) * (sr - r) + 4 * (sg - g) * (sg - g) + (3 - redMean) * (sb - b) * (sb - b);
	}

	@Test
	public void paletteColorsShowNearlyAsTheyAre()
	{
		// At the default brightness of the game and with 117 HD. Brighter settings cannot show strong colors such as
		// pure yellow, because the renderer raises each channel to the gamma and the saturation is at most 0.9375.
		for (float gamma : new float[]{1f, 0.8f})
		{
			for (Color color : BGMIOverlay.DEFAULT_PALETTE)
			{
				float err = error(color, FlatModel.nearestHsl(color, gamma), gamma);
				assertTrue(String.format("%06X at gamma %s is off by %.1f", color.getRGB() & 0xFFFFFF, gamma, err), err < 24);
			}
		}
	}

	@Test
	public void nearerThanJagexColor()
	{
		for (float gamma : GAMMAS)
		{
			for (Color color : BGMIOverlay.DEFAULT_PALETTE)
			{
				float ours = distance(color, FlatModel.nearestHsl(color, gamma), gamma);
				float old = distance(color, JagexColor.rgbToHSL(color.getRGB(), 1.0) & 0xFFFF, gamma);
				assertTrue(String.format("%06X at gamma %s: %.1f, was %.1f", color.getRGB() & 0xFFFFFF, gamma, ours, old), ours <= old);
			}
		}
	}

	@Test
	public void redStaysRed()
	{
		// JagexColor.rgbToHSL wraps this red to hue 1, which shows as orange.
		int hsl = FlatModel.nearestHsl(new Color(0xED1C24), 0.8f);
		int hue = hsl >> 10 & 63;
		assertTrue("hue " + hue, hue == 0 || hue == 63);
	}
}

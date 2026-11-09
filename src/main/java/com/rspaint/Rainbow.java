package com.rspaint;

import com.rspaint.RsPaintPlugin.StrokePoint;
import java.awt.Color;
import java.util.List;

/**
 * The colors of a rainbow stroke. The color of a place on the stroke comes from how far along the line it is, in world
 * units, so the color changes only where the line goes, and a still mouse adds no colors. The colors loop through
 * the hues, from red at the start of the stroke.
 */
final class Rainbow
{
	/** How far along the line one loop of all hues goes, in world units. 128 world units are one tile. */
	static final double LOOP = 8 * 128;

	/** The colors in one loop. More steps than the 64 hues of the game, so the colors do not show steps. */
	static final int STEPS = 192;

	/** The stored color of a rainbow stroke: the first color. Old versions of the plugin draw the stroke in this color. */
	static final Color FIRST = new Color(0xFF0000);

	// The hues that the loop goes through. Between two of them, one channel goes up or down, so all colors are full hues.
	private static final int[] HUES = {0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF};

	private static final Color[] COLORS = new Color[STEPS];

	static
	{
		for (int i = 0; i < STEPS; i++)
		{
			double place = (double) i / STEPS * HUES.length;
			int from = (int) place;
			double mix = place - from;
			int a = HUES[from], b = HUES[(from + 1) % HUES.length];
			COLORS[i] = new Color(
				mixChannel(a >> 16 & 255, b >> 16 & 255, mix),
				mixChannel(a >> 8 & 255, b >> 8 & 255, mix),
				mixChannel(a & 255, b & 255, mix));
		}
	}

	private Rainbow()
	{
	}

	private static int mixChannel(int from, int to, double mix)
	{
		return (int) Math.round(from + (to - from) * mix);
	}

	/** Returns the step of the place that is this far along the line, from 0 to STEPS - 1. */
	static int step(double distance)
	{
		return Math.floorMod((int) Math.floor(distance / LOOP * STEPS), STEPS);
	}

	/** Returns the color of the step. */
	static Color color(int step)
	{
		return COLORS[Math.floorMod(step, STEPS)];
	}

	/** Returns the color of the place that is this far along the line. */
	static Color colorAt(double distance)
	{
		return COLORS[step(distance)];
	}

	/**
	 * Returns how far along the line each point is, in world units, with the points joined by straight lines.
	 * The overlay and the renderer use the same distances, so a stroke has the same colors in both.
	 */
	static float[] distances(List<StrokePoint> pts, float[] reuse, int known)
	{
		int n = pts.size();
		float[] out = reuse != null && reuse.length >= n ? reuse : new float[Math.max(16, n * 2)];
		if (out != reuse && reuse != null)
		{
			System.arraycopy(reuse, 0, out, 0, Math.min(known, reuse.length));
		}
		int start = Math.max(1, Math.min(known, n));
		if (n > 0)
		{
			out[0] = 0;
		}
		for (int i = start; i < n; i++)
		{
			StrokePoint a = pts.get(i - 1), b = pts.get(i);
			double dx = (b.getX() - a.getX() + b.getSubx() - a.getSubx()) * 128.0;
			double dy = (b.getY() - a.getY() + b.getSuby() - a.getSuby()) * 128.0;
			out[i] = out[i - 1] + (float) Math.sqrt(dx * dx + dy * dy);
		}
		return out;
	}
}

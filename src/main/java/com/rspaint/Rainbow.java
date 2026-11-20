package com.rspaint;

import com.rspaint.RsPaintPlugin.StrokePoint;
import java.awt.Color;
import java.util.List;

final class Rainbow
{
	// 128 units per tile
	static final double LOOP = 8 * 128;

	// More than the 64 game hues, avoids banding
	static final int STEPS = 192;

	// Stored color, old versions draw it as is
	static final Color FIRST = new Color(0xFF0000);

	// Neighbors differ by one channel
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

	static int step(double distance)
	{
		return Math.floorMod((int) Math.floor(distance / LOOP * STEPS), STEPS);
	}

	static Color color(int step)
	{
		return COLORS[Math.floorMod(step, STEPS)];
	}

	static Color colorAt(double distance)
	{
		return COLORS[step(distance)];
	}

	// Distance along the line to each point, world units
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

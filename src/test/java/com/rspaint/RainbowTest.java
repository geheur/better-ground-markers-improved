package com.rspaint;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.rspaint.RsPaintPlugin.Stroke;
import com.rspaint.RsPaintPlugin.StrokePoint;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RainbowTest
{
	@Test
	public void startsRedAndLoops()
	{
		assertEquals(new Color(0xFF0000), Rainbow.colorAt(0));
		assertEquals(Rainbow.colorAt(100), Rainbow.colorAt(100 + Rainbow.LOOP));
		assertEquals(Rainbow.colorAt(Rainbow.LOOP - 1), Rainbow.colorAt(-1));
		assertEquals(new Color(0xFFFF00), Rainbow.colorAt(Rainbow.LOOP / 6));
	}

	@Test
	public void nextColorsAreClose()
	{
		// No step changes a channel by much, so the steps do not show.
		for (int i = 0; i < Rainbow.STEPS; i++)
		{
			Color a = Rainbow.color(i), b = Rainbow.color(i + 1);
			int change = Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen()) + Math.abs(a.getBlue() - b.getBlue());
			assertTrue(change <= 9);
		}
	}

	@Test
	public void distancesAreAlongTheLineInWorldUnits()
	{
		List<StrokePoint> pts = new ArrayList<>();
		pts.add(new StrokePoint(3200, 3200, 0f, 0f));
		pts.add(new StrokePoint(3203, 3200, 0f, 0f));
		pts.add(new StrokePoint(3203, 3204, 0f, 0f));
		float[] d = Rainbow.distances(pts, null, 0);
		assertEquals(0, d[0], 0);
		assertEquals(3 * 128, d[1], 1e-3);
		assertEquals(7 * 128, d[2], 1e-3);
	}

	@Test
	public void strokeMeasuresOnlyNewPoints()
	{
		Stroke stroke = new Stroke();
		List<StrokePoint> all = new ArrayList<>();
		for (int i = 0; i < 100; i++)
		{
			StrokePoint p = new StrokePoint(3200 + i / 3, 3200 + i / 7, (i % 3) / 3f, (i % 7) / 7f);
			all.add(p);
			stroke.pts.add(p);
			// Measure as the points come, as while the player draws.
			stroke.distances();
		}
		float[] expected = Rainbow.distances(all, null, 0);
		float[] got = stroke.distances();
		for (int i = 0; i < all.size(); i++)
		{
			assertEquals(expected[i], got[i], 1e-3);
		}
		// Simplify makes a new list, so the stroke measures again.
		stroke.pts = new ArrayList<>(all.subList(0, 2));
		assertArrayEquals(new float[]{0, (float) Math.hypot(128 / 3.0, 128 / 7.0)}, java.util.Arrays.copyOf(stroke.distances(), 2), 1e-3f);
	}
}

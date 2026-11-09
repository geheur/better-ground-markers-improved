package com.rspaint;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class SceneStrokesTest
{
	private static final int SIZE = 104;

	@Test
	public void tilesInTheSceneStay()
	{
		assertEquals(0, SceneStrokes.anchorTile(0, 0, SIZE, false));
		assertEquals(53, SceneStrokes.anchorTile(48, 5, SIZE, true));
		assertEquals(103, SceneStrokes.anchorTile(96, 7, SIZE, false));
	}

	@Test
	public void nearZonesStandOnTheEdge()
	{
		assertEquals(0, SceneStrokes.anchorTile(-8, 3, SIZE, false));
		assertEquals(103, SceneStrokes.anchorTile(104, 3, SIZE, false));
	}

	@Test
	public void worldWidthMatchesTheOverlayAtTheReferenceDepth()
	{
		// Pixels on the screen at the reference depth: world units * scale / depth.
		float depth = SceneStrokes.REFERENCE_DEPTH;
		int scale = 1024;
		// Scale with zoom: the overlay draws width * scale / 512 pixels.
		assertEquals(20 * 2, SceneStrokes.worldWidth(20, true, scale) * scale / depth, 1e-9);
		// Without it: the overlay draws width pixels.
		assertEquals(5, SceneStrokes.worldWidth(5, false, scale) * scale / depth, 1e-9);
	}

	@Test
	public void worldWidthWithZoomDoesNotChangeWithTheCamera()
	{
		assertEquals(SceneStrokes.worldWidth(7, true, 300), SceneStrokes.worldWidth(7, true, 1400), 1e-9);
	}

	@Test
	public void farZonesStandFurtherInForEachZoneOut()
	{
		assertEquals(1, SceneStrokes.anchorTile(-16, 0, SIZE, true));
		assertEquals(1, SceneStrokes.anchorTile(-16, 7, SIZE, true));
		assertEquals(4, SceneStrokes.anchorTile(-40, 2, SIZE, true));
		assertEquals(102, SceneStrokes.anchorTile(112, 0, SIZE, true));
		assertEquals(99, SceneStrokes.anchorTile(136, 6, SIZE, true));
	}
}

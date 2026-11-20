package com.rspaint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import com.rspaint.PartyCanvasSync.SyncedStroke;
import com.rspaint.RsPaintPlugin.Stroke;
import com.rspaint.RsPaintPlugin.StrokePoint;
import java.awt.Color;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class InstanceMapTest
{
	private static final int BASE_X = 6400;
	private static final int BASE_Y = 6400;
	// Region 12345 (x 48, y 57)
	private static final int TEMPLATE_CHUNK_X = 386;
	private static final int TEMPLATE_CHUNK_Y = 456;

	// Layout as in getInstanceTemplateChunks
	private static int chunk(int plane, int chunkX, int chunkY, int rotation)
	{
		return plane << 24 | chunkX << 14 | chunkY << 3 | rotation << 1;
	}

	private static int[][][] emptyChunks()
	{
		int[][][] chunks = new int[4][13][13];
		for (int[][] plane : chunks)
		{
			for (int[] column : plane)
			{
				Arrays.fill(column, -1);
			}
		}
		return chunks;
	}

	private static Stroke stroke(int plane, StrokePoint... points)
	{
		Stroke stroke = new Stroke();
		stroke.color = Color.RED;
		stroke.strokeWidth = 3;
		stroke.order = 7;
		stroke.plane = plane;
		stroke.pts.addAll(Arrays.asList(points));
		return stroke;
	}

	@Test
	public void tilesTurnAsWorldPointRotateTurnsThem()
	{
		// Rotation 1 maps tile (x, y) to (y, 7 - x)
		StrokePoint turned = InstanceMap.rotate(new StrokePoint(2, 5, 0.25f, 0.75f), 0, 0, 1, 0, 0);
		assertEquals(new StrokePoint(5, 5, 0.75f, 0.75f), turned);
		turned = InstanceMap.rotate(new StrokePoint(1, 0, 0.5f, 0.5f), 0, 0, 1, 0, 0);
		assertEquals(new StrokePoint(0, 6, 0.5f, 0.5f), turned);
	}

	@Test
	public void aTurnAndTheTurnBackGiveThePoint()
	{
		StrokePoint point = new StrokePoint(103, 211, 0.125f, 0.875f);
		for (int rotation = 0; rotation < 4; rotation++)
		{
			StrokePoint turned = InstanceMap.rotate(point, 96, 208, rotation, 3000, 4000);
			assertEquals(point, InstanceMap.rotate(turned, 3000, 4000, (4 - rotation) & 3, 96, 208));
		}
	}

	@Test
	public void strokesSaveInTemplateCoordinatesAndLoadAtTheSamePlace()
	{
		for (int rotation = 0; rotation < 4; rotation++)
		{
			int[][][] chunks = emptyChunks();
			chunks[1][4][6] = chunk(0, TEMPLATE_CHUNK_X, TEMPLATE_CHUNK_Y, rotation);
			InstanceMap map = new InstanceMap(chunks, BASE_X, BASE_Y);
			int chunkX = BASE_X + 4 * 8;
			int chunkY = BASE_Y + 6 * 8;
			// First point on chunk edge, last in next chunk
			Stroke stroke = stroke(1, new StrokePoint(chunkX, chunkY, 0f, 0f), new StrokePoint(chunkX + 3, chunkY + 2, 0.5f, 0.25f),
				new StrokePoint(chunkX + 9, chunkY + 1, 1f, 0.5f));

			SyncedStroke template = map.toTemplate(stroke);
			assertEquals(stroke.id, template.getId());
			assertEquals(0, template.getPlane());
			StrokePoint first = template.getPoints().get(0);
			// First point stays in template chunk
			assertEquals(TEMPLATE_CHUNK_X, first.getX() >> 3);
			assertEquals(TEMPLATE_CHUNK_Y, first.getY() >> 3);
			assertEquals(12345, map.templateRegion(stroke));

			List<InstanceMap.Placement> placements = map.placements(template);
			assertEquals(Collections.singletonList(new InstanceMap.Placement(1, 4, 6)), placements);
			assertEquals(stroke.pts, map.toInstance(template, placements.get(0)));
		}
	}

	@Test
	public void theNextInstanceShowsTheStrokeAtItsOwnPlaceAndTurn()
	{
		int[][][] first = emptyChunks();
		first[0][2][2] = chunk(0, TEMPLATE_CHUNK_X, TEMPLATE_CHUNK_Y, 0);
		InstanceMap firstMap = new InstanceMap(first, BASE_X, BASE_Y);
		Stroke stroke = stroke(0, new StrokePoint(BASE_X + 16 + 1, BASE_Y + 16 + 2, 0.5f, 0.5f));
		SyncedStroke template = firstMap.toTemplate(stroke);

		// Next instance: chunk moved and turned a quarter
		int[][][] next = emptyChunks();
		next[0][7][3] = chunk(0, TEMPLATE_CHUNK_X, TEMPLATE_CHUNK_Y, 1);
		InstanceMap nextMap = new InstanceMap(next, 8000, 9000);
		List<InstanceMap.Placement> placements = nextMap.placements(template);
		assertEquals(1, placements.size());
		// Tile (1, 2) turns to (2, 6)
		assertEquals(Collections.singletonList(new StrokePoint(8000 + 56 + 2, 9000 + 24 + 6, 0.5f, 0.5f)),
			nextMap.toInstance(template, placements.get(0)));
	}

	@Test
	public void aChunkThatShowsTwiceGivesTwoPlacements()
	{
		int[][][] chunks = emptyChunks();
		chunks[0][1][1] = chunk(0, TEMPLATE_CHUNK_X, TEMPLATE_CHUNK_Y, 0);
		chunks[0][9][5] = chunk(0, TEMPLATE_CHUNK_X, TEMPLATE_CHUNK_Y, 2);
		InstanceMap map = new InstanceMap(chunks, BASE_X, BASE_Y);
		SyncedStroke template = map.toTemplate(stroke(0, new StrokePoint(BASE_X + 8, BASE_Y + 8, 0.5f, 0.5f)));
		assertEquals(Arrays.asList(new InstanceMap.Placement(0, 1, 1), new InstanceMap.Placement(0, 9, 5)),
			map.placements(template));
	}

	@Test
	public void aStrokeOnAnotherTemplatePlaneHasNoPlacement()
	{
		int[][][] chunks = emptyChunks();
		chunks[0][1][1] = chunk(0, TEMPLATE_CHUNK_X, TEMPLATE_CHUNK_Y, 0);
		InstanceMap map = new InstanceMap(chunks, BASE_X, BASE_Y);
		SyncedStroke template = new SyncedStroke("a", 0, 1, 1, 2,
			Collections.singletonList(new StrokePoint(TEMPLATE_CHUNK_X * 8, TEMPLATE_CHUNK_Y * 8, 0, 0)), null);
		assertEquals(Collections.emptyList(), map.placements(template));
	}

	@Test
	public void tilesWithoutATemplateChunkCannotBeSaved()
	{
		InstanceMap map = new InstanceMap(emptyChunks(), BASE_X, BASE_Y);
		Stroke stroke = stroke(0, new StrokePoint(BASE_X + 3, BASE_Y + 3, 0.5f, 0.5f));
		assertEquals(-1, map.templateRegion(stroke));
		assertNull(map.toTemplate(stroke));
		// Outside scene
		assertEquals(-1, map.templateRegion(new StrokePoint(BASE_X - 1, BASE_Y, 0, 0), 0));
	}

	@Test
	public void templateRegionsInAnInstanceRegion()
	{
		int[][][] chunks = emptyChunks();
		chunks[0][0][0] = chunk(0, TEMPLATE_CHUNK_X, TEMPLATE_CHUNK_Y, 0);
		// Chunk 8 is in the next region (8 chunks wide)
		chunks[2][8][0] = chunk(1, 400, 400, 0);
		InstanceMap map = new InstanceMap(chunks, BASE_X, BASE_Y);
		int firstRegion = (BASE_X >> 6) << 8 | BASE_Y >> 6;
		int nextRegion = ((BASE_X >> 6) + 1) << 8 | BASE_Y >> 6;
		assertEquals(Collections.singleton(12345), map.templateRegionsIn(firstRegion));
		Set<Integer> next = map.templateRegionsIn(nextRegion);
		assertEquals(Collections.singleton((400 >> 3) << 8 | 400 >> 3), next);
		assertEquals(2, map.templateRegions().size());
	}

	@Test
	public void theSameChunksAreTheSameScene()
	{
		int[][][] chunks = emptyChunks();
		chunks[0][1][1] = chunk(0, TEMPLATE_CHUNK_X, TEMPLATE_CHUNK_Y, 0);
		InstanceMap map = new InstanceMap(chunks, BASE_X, BASE_Y);
		assertEquals(true, map.isSame(chunks, BASE_X, BASE_Y));
		chunks[0][1][1] = chunk(0, TEMPLATE_CHUNK_X, TEMPLATE_CHUNK_Y, 1);
		assertEquals(false, map.isSame(chunks, BASE_X, BASE_Y));
	}
}

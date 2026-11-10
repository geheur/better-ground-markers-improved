package com.rspaint;

import com.rspaint.PartyCanvasSync.SyncedStroke;
import com.rspaint.RsPaintPlugin.Stroke;
import com.rspaint.RsPaintPlugin.StrokePoint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Value;

/**
 * The template chunks of an instance scene. An instance is made of 8 by 8 tile chunks that the game copies from the
 * real map, the templates, and each copy can be turned by a quarter turn. The game puts each new instance at new
 * coordinates, so strokes in an instance are saved in the coordinates of the templates, as the Ground Markers plugin
 * saves its tiles. Then the strokes show again in the next instance that uses the same templates.
 *
 * <p>A stroke is anchored at the chunk of its first point. All points turn with that chunk, so the stroke keeps its
 * shape also when it goes into the next chunk, and the next chunk is from another place of the map.
 */
final class InstanceMap
{
	private static final int CHUNK_SIZE = 8;

	private final int[][][] chunks;
	private final int baseX;
	private final int baseY;

	/** The place of a template chunk in the instance: the plane and the chunk of the scene. */
	@Value
	static class Placement
	{
		int plane;
		int chunkX;
		int chunkY;

		/** A short text that is different for each placement, to make the ids of the copies of a stroke. */
		String key()
		{
			return plane + "." + chunkX + "." + chunkY;
		}
	}

	/** chunks is WorldView.getInstanceTemplateChunks(), indexed by plane, scene chunk x and scene chunk y. */
	InstanceMap(int[][][] chunks, int baseX, int baseY)
	{
		this.chunks = copy(chunks);
		this.baseX = baseX;
		this.baseY = baseY;
	}

	/** True if the scene has the same template chunks at the same base, so the loaded strokes are still correct. */
	boolean isSame(int[][][] chunks, int baseX, int baseY)
	{
		return this.baseX == baseX && this.baseY == baseY && Arrays.deepEquals(this.chunks, chunks);
	}

	/** Returns the region of the template tile under the instance tile, or -1 if the tile has no template chunk. */
	int templateRegion(StrokePoint point, int plane)
	{
		int data = chunkData(plane, chunkIndex(point.getX(), baseX), chunkIndex(point.getY(), baseY));
		return data == -1 ? -1 : region(templateX(data), templateY(data));
	}

	/** Returns the template region of the first point of the stroke, or -1 if it has no template chunk. */
	int templateRegion(Stroke stroke)
	{
		return stroke.pts.isEmpty() ? -1 : templateRegion(stroke.pts.get(0), stroke.plane);
	}

	/** Returns the regions of all template chunks of the scene. */
	Set<Integer> templateRegions()
	{
		Set<Integer> regions = new LinkedHashSet<>();
		for (int[][] plane : chunks)
		{
			for (int[] column : plane)
			{
				for (int data : column)
				{
					if (data != -1)
					{
						regions.add(region(templateX(data), templateY(data)));
					}
				}
			}
		}
		return regions;
	}

	/** Returns the regions of the template chunks that are in the instance region, on all planes. */
	Set<Integer> templateRegionsIn(int instanceRegion)
	{
		int regionX = (instanceRegion >> 8) << 6;
		int regionY = (instanceRegion & 0xFF) << 6;
		Set<Integer> regions = new LinkedHashSet<>();
		for (int plane = 0; plane < chunks.length; plane++)
		{
			for (int x = 0; x < chunks[plane].length; x++)
			{
				int worldX = baseX + x * CHUNK_SIZE;
				if (worldX < regionX || worldX >= regionX + 64)
				{
					continue;
				}
				for (int y = 0; y < chunks[plane][x].length; y++)
				{
					int worldY = baseY + y * CHUNK_SIZE;
					int data = chunks[plane][x][y];
					if (data != -1 && worldY >= regionY && worldY < regionY + 64)
					{
						regions.add(region(templateX(data), templateY(data)));
					}
				}
			}
		}
		return regions;
	}

	/** Returns the placement of the chunk of the first point of the stroke. The stroke must have points. */
	Placement placementOf(Stroke stroke)
	{
		StrokePoint first = stroke.pts.get(0);
		return new Placement(stroke.plane, chunkIndex(first.getX(), baseX), chunkIndex(first.getY(), baseY));
	}

	/**
	 * Returns the stroke in template coordinates, with the id of the stroke, or null if its first point has no template
	 * chunk. The plane is the plane of the template.
	 */
	SyncedStroke toTemplate(Stroke stroke)
	{
		if (stroke.pts.isEmpty())
		{
			return null;
		}
		Placement placement = placementOf(stroke);
		int data = chunkData(placement.getPlane(), placement.getChunkX(), placement.getChunkY());
		if (data == -1)
		{
			return null;
		}
		// The game turns the template by the rotation to make the instance chunk, so the template is the instance chunk
		// turned back.
		int rotation = (4 - rotation(data)) & 3;
		int fromX = baseX + placement.getChunkX() * CHUNK_SIZE;
		int fromY = baseY + placement.getChunkY() * CHUNK_SIZE;
		List<StrokePoint> points = new ArrayList<>(stroke.pts.size());
		for (StrokePoint point : stroke.pts)
		{
			points.add(rotate(point, fromX, fromY, rotation, templateX(data), templateY(data)));
		}
		return new SyncedStroke(stroke.id, stroke.color.getRGB(), stroke.strokeWidth, stroke.order, templatePlane(data),
			points, stroke.rainbow ? Boolean.TRUE : null);
	}

	/**
	 * Returns the places in the instance of the template chunk of the first point of the stroke. The list is empty if
	 * the scene does not have that chunk. It has more than one place if the instance uses the chunk more than once.
	 */
	List<Placement> placements(SyncedStroke template)
	{
		List<Placement> result = new ArrayList<>();
		if (template.getPoints().isEmpty())
		{
			return result;
		}
		StrokePoint first = template.getPoints().get(0);
		for (int plane = 0; plane < chunks.length; plane++)
		{
			for (int x = 0; x < chunks[plane].length; x++)
			{
				for (int y = 0; y < chunks[plane][x].length; y++)
				{
					int data = chunks[plane][x][y];
					if (data != -1 && templatePlane(data) == template.getPlane()
						&& first.getX() >= templateX(data) && first.getX() < templateX(data) + CHUNK_SIZE
						&& first.getY() >= templateY(data) && first.getY() < templateY(data) + CHUNK_SIZE)
					{
						result.add(new Placement(plane, x, y));
					}
				}
			}
		}
		return result;
	}

	/** Returns the points of the template stroke in instance coordinates at the placement. */
	List<StrokePoint> toInstance(SyncedStroke template, Placement placement)
	{
		int data = chunkData(placement.getPlane(), placement.getChunkX(), placement.getChunkY());
		int toX = baseX + placement.getChunkX() * CHUNK_SIZE;
		int toY = baseY + placement.getChunkY() * CHUNK_SIZE;
		List<StrokePoint> points = new ArrayList<>(template.getPoints().size());
		for (StrokePoint point : template.getPoints())
		{
			points.add(rotate(point, templateX(data), templateY(data), rotation(data), toX, toY));
		}
		return points;
	}

	/**
	 * Moves the point from the chunk at fromX, fromY to the chunk at toX, toY, and turns it by the rotation in quarter
	 * turns, with the same formula as WorldPoint.rotate. The point can be outside the chunk. Then it turns with the
	 * chunk, so a stroke keeps its shape. The place in the tile turns too, so a turned stroke is not mirrored.
	 */
	static StrokePoint rotate(StrokePoint point, int fromX, int fromY, int rotation, int toX, int toY)
	{
		int x = point.getX() - fromX;
		int y = point.getY() - fromY;
		float subx = point.getSubx();
		float suby = point.getSuby();
		int last = CHUNK_SIZE - 1;
		switch (rotation & 3)
		{
			case 1:
				return new StrokePoint(toX + y, toY + last - x, suby, 1 - subx);
			case 2:
				return new StrokePoint(toX + last - x, toY + last - y, 1 - subx, 1 - suby);
			case 3:
				return new StrokePoint(toX + last - y, toY + x, 1 - suby, subx);
			default:
				return new StrokePoint(toX + x, toY + y, subx, suby);
		}
	}

	private int chunkData(int plane, int chunkX, int chunkY)
	{
		if (plane < 0 || plane >= chunks.length || chunkX < 0 || chunkX >= chunks[plane].length
			|| chunkY < 0 || chunkY >= chunks[plane][chunkX].length)
		{
			return -1;
		}
		return chunks[plane][chunkX][chunkY];
	}

	private static int chunkIndex(int world, int base)
	{
		return Math.floorDiv(world - base, CHUNK_SIZE);
	}

	// The same packing as WorldPoint.fromLocalInstance reads.
	private static int rotation(int data)
	{
		return data >> 1 & 0x3;
	}

	private static int templateX(int data)
	{
		return (data >> 14 & 0x3FF) * CHUNK_SIZE;
	}

	private static int templateY(int data)
	{
		return (data >> 3 & 0x7FF) * CHUNK_SIZE;
	}

	private static int templatePlane(int data)
	{
		return data >> 24 & 0x3;
	}

	/** The same formula as WorldPoint.getRegionID(). */
	private static int region(int x, int y)
	{
		return (x >> 6) << 8 | y >> 6;
	}

	private static int[][][] copy(int[][][] chunks)
	{
		int[][][] result = new int[chunks.length][][];
		for (int plane = 0; plane < chunks.length; plane++)
		{
			result[plane] = new int[chunks[plane].length][];
			for (int x = 0; x < chunks[plane].length; x++)
			{
				result[plane][x] = chunks[plane][x].clone();
			}
		}
		return result;
	}
}

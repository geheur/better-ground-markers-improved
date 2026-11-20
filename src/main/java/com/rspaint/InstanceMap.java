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

// Instance coordinates change per instance, so strokes are saved in template coordinates
// A stroke is anchored at the chunk of its first point, all points turn with it
final class InstanceMap
{
	private static final int CHUNK_SIZE = 8;

	private final int[][][] chunks;
	private final int baseX;
	private final int baseY;

	@Value
	static class Placement
	{
		int plane;
		int chunkX;
		int chunkY;

		String key()
		{
			return plane + "." + chunkX + "." + chunkY;
		}
	}

	// chunks: WorldView.getInstanceTemplateChunks(), indexed plane, x, y
	InstanceMap(int[][][] chunks, int baseX, int baseY)
	{
		this.chunks = copy(chunks);
		this.baseX = baseX;
		this.baseY = baseY;
	}

	boolean isSame(int[][][] chunks, int baseX, int baseY)
	{
		return this.baseX == baseX && this.baseY == baseY && Arrays.deepEquals(this.chunks, chunks);
	}

	// -1 without a template chunk
	int templateRegion(StrokePoint point, int plane)
	{
		int data = chunkData(plane, chunkIndex(point.getX(), baseX), chunkIndex(point.getY(), baseY));
		return data == -1 ? -1 : region(templateX(data), templateY(data));
	}

	int templateRegion(Stroke stroke)
	{
		return stroke.pts.isEmpty() ? -1 : templateRegion(stroke.pts.get(0), stroke.plane);
	}

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

	Placement placementOf(Stroke stroke)
	{
		StrokePoint first = stroke.pts.get(0);
		return new Placement(stroke.plane, chunkIndex(first.getX(), baseX), chunkIndex(first.getY(), baseY));
	}

	// Null without a template chunk at the first point
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
		// Undo the game rotation
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

	// Every place of the first point template chunk
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

	// As WorldPoint.rotate, subtile part turns too
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

	// Packing as read by WorldPoint.fromLocalInstance
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

	// As WorldPoint.getRegionID()
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

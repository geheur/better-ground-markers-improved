package com.rspaint;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.rspaint.RsPaintPlugin.StrokePoint;
import java.awt.geom.Line2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;

/**
 * Writes the points of a stroke as one short string, for party messages and exports.
 * Also removes the points of a complete stroke that do not change its shape.
 *
 * <p>Each position is rounded to 1/128 of a tile, which is one local unit. The first point is stored as its full
 * position, and each other point as the change from the point before it. Consecutive points are near each other,
 * so most changes fit in one byte (zigzag varint). The bytes are base64 with the URL alphabet and no padding,
 * so that Gson does not escape them.
 */
final class StrokeCodec
{
	static final int UNITS_PER_TILE = 128;

	// Simplify removes a point if it is at most this many local units from the line between the kept points around it.
	// 2 units is 1/64 of a tile: about half a pixel at the default zoom, and about 2 pixels at full zoom.
	private static final double SIMPLIFY_TOLERANCE = 2;

	// A change has at most 5 varint bytes. That is more than the int range of a position needs.
	private static final int MAX_VARINT_BYTES = 5;

	private StrokeCodec()
	{
	}

	static String encode(List<StrokePoint> points)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream(points.size() * 2 + 8);
		long lastX = 0;
		long lastY = 0;
		for (StrokePoint point : points)
		{
			long x = toUnits(point.getX(), point.getSubx());
			long y = toUnits(point.getY(), point.getSuby());
			writeVarint(out, zigzag(x - lastX));
			writeVarint(out, zigzag(y - lastY));
			lastX = x;
			lastY = y;
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
	}

	/**
	 * Returns the points without the ones that are nearly on the line between their neighbors (Ramer-Douglas-Peucker).
	 * The kept points are rounded to 1/128 tile, as encode rounds them, so you and your party have the same points.
	 */
	static List<StrokePoint> simplify(List<StrokePoint> points)
	{
		int n = points.size();
		long[] xs = new long[n];
		long[] ys = new long[n];
		for (int i = 0; i < n; i++)
		{
			StrokePoint point = points.get(i);
			xs[i] = toUnits(point.getX(), point.getSubx());
			ys[i] = toUnits(point.getY(), point.getSuby());
		}

		boolean[] keep = new boolean[n];
		if (n > 0)
		{
			keep[0] = true;
			keep[n - 1] = true;
		}

		// A stack of index ranges, not recursion, so a very long stroke cannot overflow the call stack.
		double toleranceSq = SIMPLIFY_TOLERANCE * SIMPLIFY_TOLERANCE;
		Deque<int[]> ranges = new ArrayDeque<>();
		if (n > 2)
		{
			ranges.push(new int[]{0, n - 1});
		}
		while (!ranges.isEmpty())
		{
			int[] range = ranges.pop();
			int first = range[0];
			int last = range[1];
			int farthest = -1;
			double farthestSq = toleranceSq;
			for (int i = first + 1; i < last; i++)
			{
				double distanceSq = Line2D.ptSegDistSq(xs[first], ys[first], xs[last], ys[last], xs[i], ys[i]);
				if (distanceSq > farthestSq)
				{
					farthestSq = distanceSq;
					farthest = i;
				}
			}
			if (farthest != -1)
			{
				keep[farthest] = true;
				ranges.push(new int[]{first, farthest});
				ranges.push(new int[]{farthest, last});
			}
		}

		List<StrokePoint> result = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			if (keep[i])
			{
				result.add(fromUnits(xs[i], ys[i]));
			}
		}
		return result;
	}

	/** Returns the points, or null if the string is not a valid encoding. */
	static List<StrokePoint> decode(String encoded)
	{
		byte[] bytes;
		try
		{
			bytes = Base64.getUrlDecoder().decode(encoded);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}

		List<StrokePoint> points = new ArrayList<>();
		int[] position = {0};
		long x = 0;
		long y = 0;
		while (position[0] < bytes.length)
		{
			long dx = readVarint(bytes, position);
			long dy = readVarint(bytes, position);
			if (dx < 0 || dy < 0)
			{
				return null;
			}
			x += unzigzag(dx);
			y += unzigzag(dy);
			if (!fitsInTiles(x) || !fitsInTiles(y))
			{
				return null;
			}
			points.add(fromUnits(x, y));
		}
		return points;
	}

	private static long toUnits(int tile, float sub)
	{
		// Math.round of NaN is 0, so a bad sub-tile position cannot break the encoding.
		float clamped = Math.max(0f, Math.min(1f, sub));
		return (long) tile * UNITS_PER_TILE + Math.round(clamped * UNITS_PER_TILE);
	}

	private static boolean fitsInTiles(long units)
	{
		long tile = Math.floorDiv(units, UNITS_PER_TILE);
		return tile >= Integer.MIN_VALUE && tile <= Integer.MAX_VALUE;
	}

	/** A position at the right or top edge of a tile becomes the left or bottom edge of the next tile. That is the same place. */
	private static StrokePoint fromUnits(long x, long y)
	{
		return new StrokePoint(
			(int) Math.floorDiv(x, UNITS_PER_TILE),
			(int) Math.floorDiv(y, UNITS_PER_TILE),
			Math.floorMod(x, UNITS_PER_TILE) / (float) UNITS_PER_TILE,
			Math.floorMod(y, UNITS_PER_TILE) / (float) UNITS_PER_TILE);
	}

	private static long zigzag(long value)
	{
		return (value << 1) ^ (value >> 63);
	}

	private static long unzigzag(long value)
	{
		return (value >>> 1) ^ -(value & 1);
	}

	private static void writeVarint(ByteArrayOutputStream out, long value)
	{
		while ((value & ~0x7FL) != 0)
		{
			out.write((int) ((value & 0x7F) | 0x80));
			value >>>= 7;
		}
		out.write((int) value);
	}

	/** Returns the value and moves the position past it, or returns -1 if the bytes end or the value is too long. */
	private static long readVarint(byte[] bytes, int[] position)
	{
		long value = 0;
		for (int i = 0; i < MAX_VARINT_BYTES; i++)
		{
			if (position[0] >= bytes.length)
			{
				return -1;
			}
			int b = bytes[position[0]++];
			value |= (long) (b & 0x7F) << (7 * i);
			if ((b & 0x80) == 0)
			{
				return value;
			}
		}
		return -1;
	}

	/** Reads and writes a point list with StrokeCodec. A bad string reads as null, so the stroke is skipped. */
	static final class PointsAdapter extends TypeAdapter<List<StrokePoint>>
	{
		@Override
		public void write(JsonWriter out, List<StrokePoint> points) throws IOException
		{
			if (points == null)
			{
				out.nullValue();
			}
			else
			{
				out.value(encode(points));
			}
		}

		@Override
		public List<StrokePoint> read(JsonReader in) throws IOException
		{
			if (in.peek() != JsonToken.STRING)
			{
				in.skipValue();
				return null;
			}
			return decode(in.nextString());
		}
	}
}

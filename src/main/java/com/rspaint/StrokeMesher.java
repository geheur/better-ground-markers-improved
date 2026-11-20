package com.rspaint;

import com.rspaint.RsPaintConfig.LineStyle;
import com.rspaint.RsPaintPlugin.StrokePoint;
import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

// Flat convex ground polygons per zone. Outline is a union, so transparent strokes stay even
final class StrokeMesher
{
	static final int TILE = 128;
	static final int ZONE_TILES = 8;
	static final int ZONE = TILE * ZONE_TILES;
	// Small cells keep vertex colors smooth
	static final int RAINBOW_CELL = TILE / 4;

	private StrokeMesher()
	{
	}

	interface Ground
	{
		// Heights {sw, se, ne, nw} into out, false if not loaded. Larger is lower
		boolean corners(int worldX, int worldY, int[] out);
	}

	// Vertex x and y are world units from the zone's southwest corner
	static final class Piece
	{
		private static final AtomicLong NEXT_ID = new AtomicLong();

		final long id = NEXT_ID.getAndIncrement();
		final int zoneX, zoneY;
		float[] x = new float[32], y = new float[32], z = new float[32];
		// Rainbow strokes only, else null
		float[] distances;
		int vertices;
		int[] sizes = new int[8];
		int polygons;
		int faces;

		Piece(int zoneX, int zoneY)
		{
			this.zoneX = zoneX;
			this.zoneY = zoneY;
		}

		private void add(double[] px, double[] py, double[] pz, float[] pd, int n, double offsetX, double offsetY)
		{
			if (vertices + n > x.length)
			{
				int size = Math.max(x.length * 2, vertices + n);
				x = Arrays.copyOf(x, size);
				y = Arrays.copyOf(y, size);
				z = Arrays.copyOf(z, size);
				if (distances != null)
				{
					distances = Arrays.copyOf(distances, size);
				}
			}
			for (int i = 0; i < n; i++)
			{
				x[vertices + i] = (float) (px[i] + offsetX);
				y[vertices + i] = (float) (py[i] + offsetY);
				z[vertices + i] = (float) pz[i];
				if (distances != null)
				{
					distances[vertices + i] = pd[i];
				}
			}
			vertices += n;
			if (polygons == sizes.length)
			{
				sizes = Arrays.copyOf(sizes, polygons * 2);
			}
			sizes[polygons++] = n;
			faces += n - 2;
		}
	}

	static final class Mesh
	{
		final List<Piece> pieces;
		final double minX, minY, maxX, maxY;

		Mesh(List<Piece> pieces, double minX, double minY, double maxX, double maxY)
		{
			this.pieces = pieces;
			this.minX = minX;
			this.minY = minY;
			this.maxX = maxX;
			this.maxY = maxY;
		}

		boolean overlaps(Mesh other)
		{
			return minX <= other.maxX && other.minX <= maxX && minY <= other.maxY && other.minY <= maxY;
		}
	}

	// Null if empty. Parts [0, k], [k, 2k], ... join without a gap
	static Mesh build(List<StrokePoint> pts, int from, int to, double width, LineStyle style, Ground ground)
	{
		return build(pts, from, to, width, style, ground, false);
	}

	// Rainbow cuts RAINBOW_CELL squares, later passes cover earlier ones
	static Mesh build(List<StrokePoint> pts, int from, int to, double width, LineStyle style, Ground ground, boolean rainbow)
	{
		if (pts.isEmpty() || from < 0 || to >= pts.size() || from > to || !(width > 0))
		{
			return null;
		}
		// Relative to first point's tile, keeps doubles exact
		long originX = (long) pts.get(from).getX() * TILE;
		long originY = (long) pts.get(from).getY() * TILE;
		if (rainbow)
		{
			return buildRainbow(pts, from, to, width, style, ground, originX, originY);
		}
		Shape outline = outline(pts, from, to, width, style, originX, originY);
		Area area = new Area(flatten(outline, flatness(width)));
		Rectangle2D bounds = area.getBounds2D();
		List<Piece> pieces = new Slabs(ground, originX, originY, TILE).cut(area);
		return new Mesh(pieces, originX + bounds.getMinX(), originY + bounds.getMinY(),
			originX + bounds.getMaxX(), originY + bounds.getMaxY());
	}

	// Runs split at self-crossings, each keeps only ground no later run covers
	private static Mesh buildRainbow(List<StrokePoint> pts, int from, int to, double width, LineStyle style, Ground ground,
		long originX, long originY)
	{
		int end = Math.min(pts.size() - 1, to + 1);
		float[] all = Rainbow.distances(pts.subList(0, end + 1), null, 0);
		double[] px = new double[end + 1 - from], py = new double[end + 1 - from];
		for (int i = from; i <= end; i++)
		{
			StrokePoint p = pts.get(i);
			px[i - from] = (long) p.getX() * TILE - originX + p.getSubx() * TILE;
			py[i - from] = (long) p.getY() * TILE - originY + p.getSuby() * TILE;
		}
		List<int[]> runs = runs(px, py, all, from, to, width);

		int count = runs.size();
		double flatness = flatness(width);
		Area[] areas = new Area[count];
		Rectangle2D[] boxes = new Rectangle2D[count];
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		for (int k = 0; k < count; k++)
		{
			int[] run = runs.get(k);
			areas[k] = new Area(flatten(outline(pts, run[0], run[1], width, style, originX, originY), flatness));
			boxes[k] = areas[k].getBounds2D();
			minX = Math.min(minX, boxes[k].getMinX());
			minY = Math.min(minY, boxes[k].getMinY());
			maxX = Math.max(maxX, boxes[k].getMaxX());
			maxY = Math.max(maxY, boxes[k].getMaxY());
		}

		// Blocks of visible later runs, one big area is too slow
		double block = Math.max(4 * TILE, 2 * width);
		Map<Long, Area> shown = new HashMap<>();
		Slabs slabs = new Slabs(ground, originX, originY, RAINBOW_CELL);
		for (int k = count - 1; k >= 0; k--)
		{
			Rectangle2D box = boxes[k];
			int bx0 = (int) Math.floor(box.getMinX() / block), bx1 = (int) Math.floor(box.getMaxX() / block);
			int by0 = (int) Math.floor(box.getMinY() / block), by1 = (int) Math.floor(box.getMaxY() / block);
			Area visible = areas[k];
			for (int bx = bx0; bx <= bx1; bx++)
			{
				for (int by = by0; by <= by1; by++)
				{
					Area later = shown.get((long) bx << 32 | (by & 0xFFFFFFFFL));
					if (later != null)
					{
						visible.subtract(later);
					}
				}
			}
			if (visible.isEmpty())
			{
				continue;
			}
			for (int bx = bx0; bx <= bx1; bx++)
			{
				for (int by = by0; by <= by1; by++)
				{
					shown.computeIfAbsent((long) bx << 32 | (by & 0xFFFFFFFFL), key -> new Area()).add(visible);
				}
			}
			int[] run = runs.get(k);
			// Part ends use neighbor points to join colors, inside only own points
			int first = run[0] == from ? Math.max(0, from - 1) : run[0];
			int last = run[1] == to ? end : run[1];
			slabs.line = new Centerline(pts, first, last, width, originX, originY, all);
			slabs.cut(visible);
		}
		if (minX > maxX)
		{
			minX = minY = maxX = maxY = 0;
		}
		return new Mesh(slabs.order, originX + minX, originY + minY, originX + maxX, originY + maxY);
	}

	// Run ends before a segment within width of a place at least width back along the line
	static List<int[]> runs(double[] px, double[] py, float[] all, int from, int to, double width)
	{
		List<int[]> runs = new ArrayList<>();
		if (to <= from)
		{
			runs.add(new int[]{from, to});
			return runs;
		}
		// Half-width pieces, so sharp turns still read as far apart along the line
		double step = Math.max(width / 2, 4);
		double cell = Math.max(width, 8);
		double widthSq = width * width;
		// x0, y0, x1, y1, d0, d1 per piece
		double[] pieces = new double[64 * 6];
		int pieceCount = 0;
		// Piece indexes per grid square
		Map<Long, int[]> grid = new HashMap<>();
		double[] made = new double[6];
		int start = from;
		for (int j = from; j < to; j++)
		{
			int a = j - from;
			double sx = px[a + 1] - px[a], sy = py[a + 1] - py[a];
			int n = Math.max(1, (int) Math.ceil(Math.sqrt(sx * sx + sy * sy) / step));
			boolean back = false;
			for (int m = 0; m < n && !back; m++)
			{
				piece(px[a], py[a], sx, sy, all[j], all[j + 1], (double) m / n, (double) (m + 1) / n, made);
				int gx0 = (int) Math.floor(Math.min(made[0], made[2]) / cell), gx1 = (int) Math.floor(Math.max(made[0], made[2]) / cell);
				int gy0 = (int) Math.floor(Math.min(made[1], made[3]) / cell), gy1 = (int) Math.floor(Math.max(made[1], made[3]) / cell);
				for (int gx = gx0 - 1; gx <= gx1 + 1 && !back; gx++)
				{
					for (int gy = gy0 - 1; gy <= gy1 + 1 && !back; gy++)
					{
						int[] list = grid.get((long) gx << 32 | (gy & 0xFFFFFFFFL));
						for (int k = 1; list != null && k <= list[0]; k++)
						{
							int p = list[k] * 6;
							if (made[4] - pieces[p + 5] >= width && segmentDistanceSq(pieces[p], pieces[p + 1], pieces[p + 2],
								pieces[p + 3], made[0], made[1], made[2], made[3]) < widthSq)
							{
								back = true;
								break;
							}
						}
					}
				}
			}
			if (back)
			{
				runs.add(new int[]{start, j});
				start = j;
				grid.clear();
				pieceCount = 0;
			}
			for (int m = 0; m < n; m++)
			{
				piece(px[a], py[a], sx, sy, all[j], all[j + 1], (double) m / n, (double) (m + 1) / n, made);
				if ((pieceCount + 1) * 6 > pieces.length)
				{
					pieces = Arrays.copyOf(pieces, pieces.length * 2);
				}
				System.arraycopy(made, 0, pieces, pieceCount * 6, 6);
				int gx0 = (int) Math.floor(Math.min(made[0], made[2]) / cell), gx1 = (int) Math.floor(Math.max(made[0], made[2]) / cell);
				int gy0 = (int) Math.floor(Math.min(made[1], made[3]) / cell), gy1 = (int) Math.floor(Math.max(made[1], made[3]) / cell);
				for (int gx = gx0; gx <= gx1; gx++)
				{
					for (int gy = gy0; gy <= gy1; gy++)
					{
						long key = (long) gx << 32 | (gy & 0xFFFFFFFFL);
						int[] list = grid.get(key);
						if (list == null)
						{
							list = new int[5];
						}
						else if (list[0] + 1 == list.length)
						{
							list = Arrays.copyOf(list, list.length * 2);
						}
						list[++list[0]] = pieceCount;
						grid.put(key, list);
					}
				}
				pieceCount++;
			}
		}
		runs.add(new int[]{start, to});
		return runs;
	}

	// Segment part t0 to t1 into out as x0, y0, x1, y1, d0, d1
	private static void piece(double x, double y, double sx, double sy, float d0, float d1, double t0, double t1, double[] out)
	{
		out[0] = x + sx * t0;
		out[1] = y + sy * t0;
		out[2] = x + sx * t1;
		out[3] = y + sy * t1;
		out[4] = d0 + (d1 - d0) * t0;
		out[5] = d0 + (d1 - d0) * t1;
	}

	static double segmentDistanceSq(double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy)
	{
		double d1 = cross(ax, ay, bx, by, cx, cy), d2 = cross(ax, ay, bx, by, dx, dy);
		double d3 = cross(cx, cy, dx, dy, ax, ay), d4 = cross(cx, cy, dx, dy, bx, by);
		if ((d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0))
		{
			return 0;
		}
		return Math.min(Math.min(pointSegmentSq(ax, ay, cx, cy, dx, dy), pointSegmentSq(bx, by, cx, cy, dx, dy)),
			Math.min(pointSegmentSq(cx, cy, ax, ay, bx, by), pointSegmentSq(dx, dy, ax, ay, bx, by)));
	}

	private static double cross(double ax, double ay, double bx, double by, double px, double py)
	{
		return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
	}

	private static double pointSegmentSq(double px, double py, double ax, double ay, double bx, double by)
	{
		double sx = bx - ax, sy = by - ay;
		double lengthSq = sx * sx + sy * sy;
		double t = lengthSq > 0 ? Math.max(0, Math.min(1, ((px - ax) * sx + (py - ay) * sy) / lengthSq)) : 0;
		double dx = ax + sx * t - px, dy = ay + sy * t - py;
		return dx * dx + dy * dy;
	}

	// About 20 lines per round cap, fewer when wide
	static double flatness(double width)
	{
		return Math.max(0.25, Math.min(1.5, width * 0.005));
	}

	// May overlap itself. Curves match BGMIOverlay.endRun
	static Shape outline(List<StrokePoint> pts, int from, int to, double width, LineStyle style, long originX, long originY)
	{
		int n = pts.size();
		// Neighbors shape curves
		int first = Math.max(0, from - 1);
		int last = Math.min(n - 1, to + 2);
		double[] x = new double[last - first + 1];
		double[] y = new double[last - first + 1];
		boolean dot = true;
		for (int i = first; i <= last; i++)
		{
			StrokePoint p = pts.get(i);
			x[i - first] = (long) p.getX() * TILE - originX + p.getSubx() * TILE;
			y[i - first] = (long) p.getY() * TILE - originY + p.getSuby() * TILE;
			if (i >= from && i <= to && (x[i - first] != x[from - first] || y[i - first] != y[from - first]))
			{
				dot = false;
			}
		}

		Path2D.Double path = new Path2D.Double();
		int a = from - first;
		int b = to - first;
		if (dot)
		{
			// Dot
			path.moveTo(x[a], y[a]);
			path.lineTo(x[a] + 0.01, y[a]);
		}
		else if (n == 2 || style == LineStyle.STRAIGHT)
		{
			path.moveTo(x[a], y[a]);
			for (int i = a + 1; i <= b; i++)
			{
				path.lineTo(x[i], y[i]);
			}
		}
		else if (style == LineStyle.SMOOTH)
		{
			// Curve j: midpoint(j - 1, j) to midpoint(j, j + 1), control j
			if (from == 0)
			{
				path.moveTo(x[a], y[a]);
				path.lineTo((x[a] + x[a + 1]) / 2, (y[a] + y[a + 1]) / 2);
			}
			else
			{
				path.moveTo((x[a] + x[a + 1]) / 2, (y[a] + y[a + 1]) / 2);
			}
			for (int j = from + 1; j <= Math.min(to, n - 2); j++)
			{
				int i = j - first;
				path.quadTo(x[i], y[i], (x[i] + x[i + 1]) / 2, (y[i] + y[i + 1]) / 2);
			}
			if (to == n - 1)
			{
				path.lineTo(x[b], y[b]);
			}
		}
		else
		{
			// Catmull-Rom, ends repeat as own neighbors
			path.moveTo(x[a], y[a]);
			for (int j = from; j < to; j++)
			{
				int i = j - first;
				int prev = Math.max(j - 1, 0) - first;
				int next = Math.min(j + 2, n - 1) - first;
				path.curveTo(
					x[i] + (x[i + 1] - x[prev]) / 6, y[i] + (y[i + 1] - y[prev]) / 6,
					x[i + 1] - (x[next] - x[i]) / 6, y[i + 1] - (y[next] - y[i]) / 6,
					x[i + 1], y[i + 1]);
			}
		}
		return new BasicStroke((float) width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(path);
	}

	// Area is much faster with straight lines
	static Path2D flatten(Shape shape, double flatness)
	{
		Path2D.Double flat = new Path2D.Double(Path2D.WIND_NON_ZERO);
		flat.append(shape.getPathIterator(null, flatness), false);
		return flat;
	}

	static final class Centerline
	{
		private static final int BUCKET = TILE;

		private final double[] x, y;
		private final float[] d;
		private final int count;
		private final double radiusSq;
		// Per square: count, then segment indexes
		private final Map<Long, int[]> buckets = new HashMap<>();

		Centerline(List<StrokePoint> pts, int first, int last, double width, long originX, long originY, float[] all)
		{
			count = last - first + 1;
			radiusSq = width * width / 4;
			x = new double[count];
			y = new double[count];
			d = new float[count];
			for (int i = 0; i < count; i++)
			{
				StrokePoint p = pts.get(first + i);
				x[i] = (long) p.getX() * TILE - originX + p.getSubx() * TILE;
				y[i] = (long) p.getY() * TILE - originY + p.getSuby() * TILE;
				d[i] = all[first + i];
			}
			// Curves bend away from straight segments
			double reach = width / 2 + TILE;
			for (int i = 0; i + 1 < count; i++)
			{
				int minX = (int) Math.floor((Math.min(x[i], x[i + 1]) - reach) / BUCKET);
				int maxX = (int) Math.floor((Math.max(x[i], x[i + 1]) + reach) / BUCKET);
				int minY = (int) Math.floor((Math.min(y[i], y[i + 1]) - reach) / BUCKET);
				int maxY = (int) Math.floor((Math.max(y[i], y[i + 1]) + reach) / BUCKET);
				for (int bx = minX; bx <= maxX; bx++)
				{
					for (int by = minY; by <= maxY; by++)
					{
						long key = (long) bx << 32 | (by & 0xFFFFFFFFL);
						int[] list = buckets.get(key);
						if (list == null)
						{
							list = new int[5];
						}
						else if (list[0] + 1 == list.length)
						{
							list = Arrays.copyOf(list, list.length * 2);
						}
						list[++list[0]] = i;
						buckets.put(key, list);
					}
				}
			}
		}

		// Largest distance along the line within half width, matches overlay. Nearest place if no segment reaches
		float distanceAt(double px, double py)
		{
			if (count == 1)
			{
				return d[0];
			}
			long key = (long) (int) Math.floor(px / BUCKET) << 32 | ((int) Math.floor(py / BUCKET) & 0xFFFFFFFFL);
			int[] list = buckets.get(key);
			// No list: all segments
			int n = list != null ? list[0] : count - 1;
			double nearestSq = Double.MAX_VALUE;
			float nearest = d[0];
			float last = Float.NaN;
			for (int k = 0; k < n; k++)
			{
				int i = list != null ? list[k + 1] : k;
				double sx = x[i + 1] - x[i], sy = y[i + 1] - y[i];
				double length = Math.sqrt(sx * sx + sy * sy);
				double ox = px - x[i], oy = py - y[i];
				double along = length > 0 ? (ox * sx + oy * sy) / length : 0;
				double offSq = Math.max(0, ox * ox + oy * oy - along * along);
				double clamped = Math.max(0, Math.min(length, along));
				double distanceSq = offSq + (along - clamped) * (along - clamped);
				if (distanceSq < nearestSq)
				{
					nearestSq = distanceSq;
					nearest = place(i, clamped, length);
				}
				if (offSq <= radiusSq)
				{
					double reach = Math.sqrt(radiusSq - offSq);
					if (along - reach <= length && along + reach >= 0)
					{
						float place = place(i, Math.min(length, along + reach), length);
						if (!(place <= last))
						{
							last = place;
						}
					}
				}
			}
			return Float.isNaN(last) ? nearest : last;
		}

		private float place(int i, double at, double length)
		{
			return length > 0 ? (float) (d[i] + (d[i + 1] - d[i]) * at / length) : d[i];
		}
	}

	// Area to horizontal slabs, tiles, then ground triangles
	private static final class Slabs
	{
		private static final double EPSILON = 1e-7;

		private final Ground ground;
		private final long originX, originY;
		private final int cell;
		// Rainbow only, set per run, else null
		Centerline line;
		private final long originTileX, originTileY;
		private final Map<Long, Piece> pieces = new HashMap<>();
		// One per zone, across cut calls
		final List<Piece> order = new ArrayList<>();

		// Edges: {x at low y, low y, x at high y, high y}
		private double[] edges = new double[64];
		private int edgeCount;

		// Clip scratch
		private double[] ax = new double[16], ay = new double[16], bx = new double[16], by = new double[16];
		// Polygon before diagonal cut
		private double[] keepX = new double[16], keepY = new double[16];
		private double[] pz = new double[16];
		private float[] pd = new float[16];
		private final int[] corners = new int[4], other = new int[4];

		Slabs(Ground ground, long originX, long originY, int cell)
		{
			this.ground = ground;
			this.cell = cell;
			this.originX = originX;
			this.originY = originY;
			originTileX = originX / TILE;
			originTileY = originY / TILE;
		}

		// Areas across calls must not overlap
		List<Piece> cut(Area area)
		{
			edgeCount = 0;
			double[] ys = readEdges(area);
			if (edgeCount == 0)
			{
				return order;
			}
			Integer[] byLow = new Integer[edgeCount];
			for (int i = 0; i < edgeCount; i++)
			{
				byLow[i] = i;
			}
			Arrays.sort(byLow, (a, b) -> Double.compare(edges[a * 4 + 1], edges[b * 4 + 1]));

			int[] active = new int[edgeCount];
			int activeCount = 0;
			int next = 0;
			double[] xa = new double[edgeCount];
			double[] xb = new double[edgeCount];
			Integer[] sorted = new Integer[edgeCount];
			// Merges trapezoids between the same two edges across slabs, else winding strokes explode in count
			// Per left edge: right edge (or -1), from y, to y
			openRight = new int[edgeCount];
			Arrays.fill(openRight, -1);
			openFrom = new double[edgeCount];
			openTo = new double[edgeCount];
			seen = new int[edgeCount];
			open = new int[edgeCount];
			openCount = 0;
			for (int s = 0; s + 1 < ys.length; s++)
			{
				double y0 = ys[s];
				double y1 = ys[s + 1];
				if (y0 == Math.floor(y0 / cell) * cell)
				{
					// Trapezoids must not cross rows
					closeAll();
				}
				int kept = 0;
				for (int i = 0; i < activeCount; i++)
				{
					if (edges[active[i] * 4 + 3] > y0)
					{
						active[kept++] = active[i];
					}
				}
				activeCount = kept;
				while (next < edgeCount && edges[byLow[next] * 4 + 1] <= y0)
				{
					int e = byLow[next++];
					if (edges[e * 4 + 3] > y0)
					{
						active[activeCount++] = e;
					}
				}
				if (y1 - y0 < EPSILON || activeCount < 2)
				{
					continue;
				}
				for (int i = 0; i < activeCount; i++)
				{
					int e = active[i];
					xa[i] = xAt(e, y0);
					xb[i] = xAt(e, y1);
					sorted[i] = i;
				}
				// Edges do not cross inside a slab
				Arrays.sort(sorted, 0, activeCount, (a, b) -> Double.compare(xa[a] + xb[a], xa[b] + xb[b]));
				// Inside flips at each edge
				slab++;
				for (int i = 0; i + 1 < activeCount; i += 2)
				{
					int l = active[sorted[i]];
					int r = active[sorted[i + 1]];
					if (openRight[l] != r)
					{
						if (openRight[l] != -1)
						{
							close(l);
						}
						else
						{
							open[openCount++] = l;
						}
						openRight[l] = r;
						openFrom[l] = y0;
					}
					openTo[l] = y1;
					seen[l] = slab;
				}
				// Close trapezoids that ended
				int stillOpen = 0;
				for (int i = 0; i < openCount; i++)
				{
					int l = open[i];
					if (seen[l] == slab)
					{
						open[stillOpen++] = l;
					}
					else
					{
						close(l);
						openRight[l] = -1;
					}
				}
				openCount = stillOpen;
			}
			closeAll();
			return order;
		}

		private int[] openRight, seen, open;
		private double[] openFrom, openTo;
		private int openCount, slab;

		// Stays open, caller must reopen or forget it
		private void close(int l)
		{
			int r = openRight[l];
			double from = openFrom[l];
			double to = openTo[l];
			trapezoid(xAt(l, from), xAt(r, from), xAt(r, to), xAt(l, to), from, to);
		}

		private void closeAll()
		{
			for (int i = 0; i < openCount; i++)
			{
				close(open[i]);
				openRight[open[i]] = -1;
			}
			openCount = 0;
		}

		private double xAt(int e, double y)
		{
			double x0 = edges[e * 4], y0 = edges[e * 4 + 1], x1 = edges[e * 4 + 2], y1 = edges[e * 4 + 3];
			return x0 + (x1 - x0) * (y - y0) / (y1 - y0);
		}

		// Returns sorted slab lines: vertex ys and cell rows
		private double[] readEdges(Area area)
		{
			double[] ys = new double[64];
			int yCount = 0;
			double[] c = new double[6];
			double startX = 0, startY = 0, lastX = 0, lastY = 0;
			double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
			for (PathIterator it = area.getPathIterator(null, 0.5); !it.isDone(); it.next())
			{
				int type = it.currentSegment(c);
				if (type == PathIterator.SEG_MOVETO)
				{
					startX = lastX = c[0];
					startY = lastY = c[1];
				}
				else if (type == PathIterator.SEG_LINETO || type == PathIterator.SEG_CLOSE)
				{
					double toX = type == PathIterator.SEG_CLOSE ? startX : c[0];
					double toY = type == PathIterator.SEG_CLOSE ? startY : c[1];
					addEdge(lastX, lastY, toX, toY);
					lastX = toX;
					lastY = toY;
				}
				else
				{
					continue;
				}
				if (yCount == ys.length)
				{
					ys = Arrays.copyOf(ys, yCount * 2);
				}
				ys[yCount++] = lastY;
				minY = Math.min(minY, lastY);
				maxY = Math.max(maxY, lastY);
			}
			if (yCount == 0)
			{
				return new double[0];
			}
			// Cell rows, so each slab is in one row
			for (double row = Math.ceil(minY / cell) * cell; row < maxY; row += cell)
			{
				if (yCount == ys.length)
				{
					ys = Arrays.copyOf(ys, yCount * 2);
				}
				ys[yCount++] = row;
			}
			ys = Arrays.copyOf(ys, yCount);
			Arrays.sort(ys);
			int unique = 0;
			for (int i = 0; i < ys.length; i++)
			{
				if (unique == 0 || ys[i] != ys[unique - 1])
				{
					ys[unique++] = ys[i];
				}
			}
			return Arrays.copyOf(ys, unique);
		}

		private void addEdge(double x0, double y0, double x1, double y1)
		{
			if (y0 == y1)
			{
				return;
			}
			if ((edgeCount + 1) * 4 > edges.length)
			{
				edges = Arrays.copyOf(edges, edges.length * 2);
			}
			int i = edgeCount++ * 4;
			if (y0 < y1)
			{
				edges[i] = x0;
				edges[i + 1] = y0;
				edges[i + 2] = x1;
				edges[i + 3] = y1;
			}
			else
			{
				edges[i] = x1;
				edges[i + 1] = y1;
				edges[i + 2] = x0;
				edges[i + 3] = y0;
			}
		}

		private void trapezoid(double x00, double x01, double x11, double x10, double y0, double y1)
		{
			int row = (int) Math.floor((y0 + y1) / 2 / TILE);
			int first = (int) Math.floor(Math.min(x00, x10) / cell);
			int last = (int) Math.floor(Math.max(x01, x11) / cell);
			for (int column = first; column <= last; column++)
			{
				// Equals column when cells are tiles
				int tileColumn = Math.floorDiv(column * cell, TILE);
				int worldX = (int) (originTileX + tileColumn);
				int worldY = (int) (originTileY + row);
				if (!ground.corners(worldX, worldY, corners))
				{
					continue;
				}
				int sw = corners[0], se = corners[1], ne = corners[2], nw = corners[3];
				int end = column;
				// Rainbow keeps small cells
				if (cell == TILE && sw == se && se == ne && ne == nw)
				{
					// Adjacent flat tiles in a zone share a polygon
					while (end < last && Math.floorMod(worldX + (end - column), ZONE_TILES) != ZONE_TILES - 1
						&& ground.corners(worldX + (end - column) + 1, worldY, other)
						&& other[0] == sw && other[1] == sw && other[2] == sw && other[3] == sw)
					{
						end++;
					}
				}
				// Clip to cells column..end
				int n = quad(x00, x01, x11, x10, y0, y1);
				n = clip(n, 1, 0, (end + 1) * (double) cell);
				n = clip(n, -1, 0, -column * (double) cell);
				double tileX = tileColumn * (double) TILE;
				double tileY = row * (double) TILE;
				if (sw + ne == se + nw)
				{
					// One plane
					emit(n, worldX, worldY, tileX, tileY, sw, se, ne, nw, true);
				}
				else
				{
					// Game splits tiles along the southeast-northwest line
					int saved = n;
					if (keepX.length < saved)
					{
						keepX = new double[saved * 2];
						keepY = new double[saved * 2];
					}
					System.arraycopy(ax, 0, keepX, 0, saved);
					System.arraycopy(ay, 0, keepY, 0, saved);
					n = clip(saved, 1, 1, tileX + tileY + TILE);
					emit(n, worldX, worldY, tileX, tileY, sw, se, ne, nw, true);
					System.arraycopy(keepX, 0, ax, 0, saved);
					System.arraycopy(keepY, 0, ay, 0, saved);
					n = clip(saved, -1, -1, -(tileX + tileY + TILE));
					emit(n, worldX, worldY, tileX, tileY, sw, se, ne, nw, false);
				}
				column = end;
			}
		}

		private int quad(double x00, double x01, double x11, double x10, double y0, double y1)
		{
			int n = 0;
			ax[n] = x00;
			ay[n++] = y0;
			if (x01 != x00)
			{
				ax[n] = x01;
				ay[n++] = y0;
			}
			ax[n] = x11;
			ay[n++] = y1;
			if (x10 != x11)
			{
				ax[n] = x10;
				ay[n++] = y1;
			}
			return n;
		}

		// Sutherland-Hodgman, keeps nx * x + ny * y <= limit
		private int clip(int n, double nx, double ny, double limit)
		{
			if (bx.length < n * 2 + 2)
			{
				bx = new double[n * 2 + 2];
				by = new double[n * 2 + 2];
			}
			int m = 0;
			for (int i = 0; i < n; i++)
			{
				int j = (i + 1) % n;
				double di = nx * ax[i] + ny * ay[i] - limit;
				double dj = nx * ax[j] + ny * ay[j] - limit;
				if (di <= 0)
				{
					bx[m] = ax[i];
					by[m++] = ay[i];
				}
				if (di < 0 && dj > 0 || di > 0 && dj < 0)
				{
					double t = di / (di - dj);
					bx[m] = ax[i] + (ax[j] - ax[i]) * t;
					by[m++] = ay[i] + (ay[j] - ay[i]) * t;
				}
			}
			double[] swap = ax;
			ax = bx;
			bx = swap;
			swap = ay;
			ay = by;
			by = swap;
			return m;
		}

		// South triangle heights from sw, north from ne
		private void emit(int n, int worldX, int worldY, double tileX, double tileY, int sw, int se, int ne, int nw, boolean south)
		{
			if (n < 3 || Math.abs(signedArea(n)) < EPSILON)
			{
				return;
			}
			if (pz.length < n)
			{
				pz = new double[n * 2];
				pd = new float[n * 2];
			}
			for (int i = 0; i < n; i++)
			{
				if (line != null)
				{
					pd[i] = line.distanceAt(ax[i], ay[i]);
				}
				double u = (ax[i] - tileX) / TILE;
				double v = (ay[i] - tileY) / TILE;
				pz[i] = south
					? sw + (se - sw) * u + (nw - sw) * v
					: ne + (nw - ne) * (1 - u) + (se - ne) * (1 - v);
			}
			int zoneX = Math.floorDiv(worldX, ZONE_TILES);
			int zoneY = Math.floorDiv(worldY, ZONE_TILES);
			long key = (long) zoneX << 32 | (zoneY & 0xFFFFFFFFL);
			Piece piece = pieces.get(key);
			if (piece == null)
			{
				piece = new Piece(zoneX, zoneY);
				if (line != null)
				{
					piece.distances = new float[piece.x.length];
				}
				pieces.put(key, piece);
				order.add(piece);
			}
			piece.add(ax, ay, pz, pd, n, originX - (long) zoneX * ZONE, originY - (long) zoneY * ZONE);
		}

		private double signedArea(int n)
		{
			double sum = 0;
			for (int i = 0; i < n; i++)
			{
				int j = (i + 1) % n;
				sum += ax[i] * ay[j] - ax[j] * ay[i];
			}
			return sum / 2;
		}
	}
}

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

/**
 * Turns a stroke into flat convex polygons that lie on the ground, so the renderer can draw the stroke as part of the
 * game world.
 *
 * <p>The stroke outline is made in world units: the line through the points, with the width of the stroke, round caps
 * and round joins. The outline is the union of that shape, so where the stroke crosses itself, the polygons do not
 * overlap and a transparent stroke does not get darker. Each polygon lies in one triangle of one ground tile and has the
 * height of that triangle, so the stroke follows the ground on slopes.
 *
 * <p>The polygons of each zone (8 by 8 tiles) are kept together. The renderer draws each zone as its own models.
 */
final class StrokeMesher
{
	static final int TILE = 128;
	static final int ZONE_TILES = 8;
	static final int ZONE = TILE * ZONE_TILES;

	private StrokeMesher()
	{
	}

	/** The ground heights. */
	interface Ground
	{
		/**
		 * Writes the heights of the corners of the world tile into out as {southwest, southeast, northeast, northwest}.
		 * Returns false if the tile is not loaded. A larger height is lower, as in the game.
		 */
		boolean corners(int worldX, int worldY, int[] out);
	}

	/** The polygons of one stroke in one zone. Vertex x and y are world units from the southwest corner of the zone. */
	static final class Piece
	{
		private static final AtomicLong NEXT_ID = new AtomicLong();

		/** A new id for each piece, so the renderer sees when the piece of a zone changes. */
		final long id = NEXT_ID.getAndIncrement();
		/** The world zone: the world tile divided by ZONE_TILES. */
		final int zoneX, zoneY;
		float[] x = new float[32], y = new float[32], z = new float[32];
		int vertices;
		/** The vertex count of each polygon. The vertices of the polygons follow each other. */
		int[] sizes = new int[8];
		int polygons;
		/** The triangles of all polygons: each polygon of n vertices is n - 2 triangles. */
		int faces;

		Piece(int zoneX, int zoneY)
		{
			this.zoneX = zoneX;
			this.zoneY = zoneY;
		}

		private void add(double[] px, double[] py, double[] pz, int n, double offsetX, double offsetY)
		{
			if (vertices + n > x.length)
			{
				int size = Math.max(x.length * 2, vertices + n);
				x = Arrays.copyOf(x, size);
				y = Arrays.copyOf(y, size);
				z = Arrays.copyOf(z, size);
			}
			for (int i = 0; i < n; i++)
			{
				x[vertices + i] = (float) (px[i] + offsetX);
				y[vertices + i] = (float) (py[i] + offsetY);
				z[vertices + i] = (float) pz[i];
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

	/** The polygons of a stroke, by zone. */
	static final class Mesh
	{
		final List<Piece> pieces;
		/** The box around the stroke outline in world units. */
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

	/**
	 * Returns the polygons of the part of the stroke from point from to point to, with the width in world units, or null
	 * if there are no points. The line style connects the points as on the screen, but in world units. The parts
	 * [0, k], [k, 2k], ... of a stroke join without a gap, and their curves are the curves of the whole stroke.
	 */
	static Mesh build(List<StrokePoint> pts, int from, int to, double width, LineStyle style, Ground ground)
	{
		if (pts.isEmpty() || from < 0 || to >= pts.size() || from > to || !(width > 0))
		{
			return null;
		}
		// All coordinates are world units from the southwest corner of the tile of the first point, so doubles stay exact.
		long originX = (long) pts.get(from).getX() * TILE;
		long originY = (long) pts.get(from).getY() * TILE;
		Shape outline = outline(pts, from, to, width, style, originX, originY);
		Area area = new Area(flatten(outline, flatness(width)));
		Rectangle2D bounds = area.getBounds2D();
		List<Piece> pieces = new Slabs(ground, originX, originY).cut(area);
		return new Mesh(pieces, originX + bounds.getMinX(), originY + bounds.getMinY(),
			originX + bounds.getMaxX(), originY + bounds.getMaxY());
	}

	/**
	 * How far a flattened curve can be from the curve, in world units. A round cap gets about 20 straight lines, and very
	 * wide strokes get a little fewer.
	 */
	static double flatness(double width)
	{
		return Math.max(0.25, Math.min(1.5, width * 0.005));
	}

	/**
	 * The part of the stroke as a filled shape: the line with the width, round caps and round joins. Can overlap itself.
	 * The curves are the curves of BGMIOverlay.endRun, so the line style looks the same.
	 */
	static Shape outline(List<StrokePoint> pts, int from, int to, double width, LineStyle style, long originX, long originY)
	{
		int n = pts.size();
		// The neighbors of the part are in the curves too.
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
			// A very short line with round caps is a dot with the width of the stroke.
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
			// Curve j goes from the middle of points j - 1 and j to the middle of points j and j + 1, with point j as its
			// control point. A part has the curves after its first point up to its last point.
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
			// Catmull-Rom curves: the first and last points of the stroke are used again as their own neighbors.
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

	/** The shape with its curves as straight lines. Area works much faster with straight lines only. */
	static Path2D flatten(Shape shape, double flatness)
	{
		Path2D.Double flat = new Path2D.Double(Path2D.WIND_NON_ZERO);
		flat.append(shape.getPathIterator(null, flatness), false);
		return flat;
	}

	/**
	 * Cuts an area into horizontal slabs, the slabs into tiles, and the tiles into their two ground triangles.
	 * The area must not overlap itself, as an Area does not.
	 */
	private static final class Slabs
	{
		private static final double EPSILON = 1e-7;

		private final Ground ground;
		private final long originX, originY;
		private final long originTileX, originTileY;
		private final Map<Long, Piece> pieces = new HashMap<>();
		private final List<Piece> order = new ArrayList<>();

		// The edges, each {x at its low y, low y, x at its high y, high y}.
		private double[] edges = new double[64];
		private int edgeCount;

		// Scratch for the polygons that clip makes.
		private double[] ax = new double[16], ay = new double[16], bx = new double[16], by = new double[16];
		// The polygon before the cut along the diagonal of a tile.
		private double[] keepX = new double[16], keepY = new double[16];
		private final double[] pz = new double[16];
		private final int[] corners = new int[4], other = new int[4];

		Slabs(Ground ground, long originX, long originY)
		{
			this.ground = ground;
			this.originX = originX;
			this.originY = originY;
			originTileX = originX / TILE;
			originTileY = originY / TILE;
		}

		List<Piece> cut(Area area)
		{
			double[] ys = readEdges(area);
			if (edgeCount == 0)
			{
				return order;
			}
			// The edges by their low y, so the sweep adds them in order.
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
			// A slab line from a vertex goes across the whole area, so the trapezoids between the same two edges in
			// slabs that follow each other are one trapezoid. Without this, a stroke that winds over many rows has
			// as many trapezoids as vertices times crossings. Per left edge: its right edge (or -1), from and to y.
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
				if (y0 == Math.floor(y0 / TILE) * TILE)
				{
					// A tile row line: the trapezoids must not go into the next row.
					closeAll();
				}
				// Edges end at slab lines, because all their end points are slab lines.
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
				// Edges do not cross inside a slab, so the order in the middle is the order of the whole slab.
				Arrays.sort(sorted, 0, activeCount, (a, b) -> Double.compare(xa[a] + xb[a], xa[b] + xb[b]));
				// Inside and outside change at each edge.
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
				// The trapezoids that did not go on into this slab end.
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

		/** Cuts the open trapezoid of the left edge into tiles. It stays open, so the caller must reopen or forget it. */
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

		/** Reads the edges of the area and returns the sorted slab lines: each vertex y and each tile row line between. */
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
			// The slabs also stop at each tile row, so each slab is in one row of tiles.
			for (double row = Math.ceil(minY / TILE) * TILE; row < maxY; row += TILE)
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
				// A flat edge bounds no slab.
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

		/** Cuts the trapezoid with the corners (x00, y0), (x01, y0), (x11, y1), (x10, y1) into the tiles of its row. */
		private void trapezoid(double x00, double x01, double x11, double x10, double y0, double y1)
		{
			int row = (int) Math.floor((y0 + y1) / 2 / TILE);
			int first = (int) Math.floor(Math.min(x00, x10) / TILE);
			int last = (int) Math.floor(Math.max(x01, x11) / TILE);
			for (int column = first; column <= last; column++)
			{
				int worldX = (int) (originTileX + column);
				int worldY = (int) (originTileY + row);
				if (!ground.corners(worldX, worldY, corners))
				{
					continue;
				}
				int sw = corners[0], se = corners[1], ne = corners[2], nw = corners[3];
				int end = column;
				if (sw == se && se == ne && ne == nw)
				{
					// Flat tiles next to each other in the same zone take one polygon.
					while (end < last && Math.floorMod(worldX + (end - column), ZONE_TILES) != ZONE_TILES - 1
						&& ground.corners(worldX + (end - column) + 1, worldY, other)
						&& other[0] == sw && other[1] == sw && other[2] == sw && other[3] == sw)
					{
						end++;
					}
				}
				// The part of the trapezoid in the tiles from column to end.
				int n = quad(x00, x01, x11, x10, y0, y1);
				n = clip(n, 1, 0, (end + 1) * TILE);
				n = clip(n, -1, 0, -column * TILE);
				double tileX = column * (double) TILE;
				double tileY = row * (double) TILE;
				if (sw + ne == se + nw)
				{
					// The tile is one plane, so its two triangles need no cut.
					emit(n, worldX, worldY, tileX, tileY, sw, se, ne, nw, true);
				}
				else
				{
					// The game draws a tile as two triangles, with the line from the southeast corner to the northwest corner.
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

		/** Puts the four corners in ax and ay, without corners that are the same point, and returns their count. */
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

		/**
		 * Keeps the part of the convex polygon in ax and ay where nx * x + ny * y <= limit (Sutherland-Hodgman).
		 * Returns the new vertex count. The result is in ax and ay again.
		 */
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

		/**
		 * Adds the polygon in ax and ay to the piece of its zone, with the height of the tile triangle at each vertex.
		 * The south triangle has the southwest corner, the north triangle has the northeast corner.
		 */
		private void emit(int n, int worldX, int worldY, double tileX, double tileY, int sw, int se, int ne, int nw, boolean south)
		{
			if (n < 3 || Math.abs(signedArea(n)) < EPSILON)
			{
				return;
			}
			for (int i = 0; i < n; i++)
			{
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
				pieces.put(key, piece);
				order.add(piece);
			}
			piece.add(ax, ay, pz, n, originX - (long) zoneX * ZONE, originY - (long) zoneY * ZONE);
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

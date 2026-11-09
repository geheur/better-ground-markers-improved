package com.rspaint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.rspaint.RsPaintConfig.LineStyle;
import com.rspaint.RsPaintPlugin.StrokePoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class StrokeMesherTest
{
	private static final StrokeMesher.Ground FLAT = (x, y, out) ->
	{
		java.util.Arrays.fill(out, -100);
		return true;
	};

	/** Tiles that slope down to the east and are not flat planes, so the mesher must cut them along the diagonal. */
	private static final StrokeMesher.Ground SLOPE = (x, y, out) ->
	{
		out[0] = x * 50;
		out[1] = (x + 1) * 50;
		out[2] = (x + 1) * 50 + y;
		out[3] = x * 50;
		return true;
	};

	private static StrokeMesher.Mesh build(List<StrokePoint> pts, double width, LineStyle style, StrokeMesher.Ground ground)
	{
		return StrokeMesher.build(pts, 0, pts.size() - 1, width, style, ground);
	}

	private static double area(StrokeMesher.Mesh mesh)
	{
		double sum = 0;
		for (StrokeMesher.Piece p : mesh.pieces)
		{
			int first = 0;
			for (int k = 0; k < p.polygons; k++)
			{
				int n = p.sizes[k];
				double a = 0;
				for (int i = 0; i < n; i++)
				{
					int j = (i + 1) % n;
					a += p.x[first + i] * p.y[first + j] - p.x[first + j] * p.y[first + i];
				}
				sum += Math.abs(a) / 2;
				first += n;
			}
		}
		return sum;
	}

	private static List<StrokePoint> line(int fromX, float fromSub, int toX, float toSub, int y)
	{
		List<StrokePoint> pts = new ArrayList<>();
		pts.add(new StrokePoint(fromX, y, fromSub, 0.5f));
		pts.add(new StrokePoint(toX, y, toSub, 0.5f));
		return pts;
	}

	@Test
	public void straightLineHasAreaOfStrokeShape()
	{
		// From x = 3200.25 to x = 3203.75 tiles: 3.5 tiles long.
		StrokeMesher.Mesh mesh = build(line(3200, 0.25f, 3203, 0.75f, 3200), 20, LineStyle.STRAIGHT, FLAT);
		assertNotNull(mesh);
		double expected = 3.5 * 128 * 20 + Math.PI * 10 * 10;
		assertEquals(expected, area(mesh), expected * 0.01);
		for (StrokeMesher.Piece p : mesh.pieces)
		{
			for (int i = 0; i < p.vertices; i++)
			{
				assertEquals(-100, p.z[i], 0);
			}
		}
	}

	@Test
	public void crossingStrokeDoesNotOverlapItself()
	{
		// A stroke that goes there and back covers its line once.
		List<StrokePoint> pts = line(3200, 0.25f, 3203, 0.75f, 3200);
		pts.add(new StrokePoint(3200, 3200, 0.25f, 0.5f));
		StrokeMesher.Mesh mesh = build(pts, 20, LineStyle.STRAIGHT, FLAT);
		double expected = 3.5 * 128 * 20 + Math.PI * 10 * 10;
		assertEquals(expected, area(mesh), expected * 0.01);
	}

	@Test
	public void piecesAreSplitByZone()
	{
		// Tiles 3198 to 3201 are in zones 399 and 400.
		StrokeMesher.Mesh mesh = build(line(3198, 0.5f, 3201, 0.5f, 3203), 10, LineStyle.STRAIGHT, FLAT);
		assertEquals(2, mesh.pieces.size());
		for (StrokeMesher.Piece p : mesh.pieces)
		{
			assertEquals(400, p.zoneY);
			for (int i = 0; i < p.vertices; i++)
			{
				assertTrue(p.x[i] >= -1e-3 && p.x[i] <= StrokeMesher.ZONE + 1e-3);
				assertTrue(p.y[i] >= 0 && p.y[i] <= StrokeMesher.ZONE);
			}
		}
	}

	@Test
	public void verticesHaveTheHeightOfTheirGroundTriangle()
	{
		StrokeMesher.Mesh mesh = build(line(3200, 0.1f, 3203, 0.9f, 3200), 60, LineStyle.STRAIGHT, SLOPE);
		for (StrokeMesher.Piece p : mesh.pieces)
		{
			int first = 0;
			for (int k = 0; k < p.polygons; k++)
			{
				int n = p.sizes[k];
				double cx = 0, cy = 0;
				for (int i = 0; i < n; i++)
				{
					cx += p.x[first + i];
					cy += p.y[first + i];
				}
				// The middle of the polygon tells which tile and triangle it is in.
				double wx = (double) p.zoneX * StrokeMesher.ZONE + cx / n, wy = (double) p.zoneY * StrokeMesher.ZONE + cy / n;
				int tx = (int) Math.floor(wx / 128), ty = (int) Math.floor(wy / 128);
				boolean south = (wx - tx * 128) + (wy - ty * 128) <= 128;
				int[] c = new int[4];
				SLOPE.corners(tx, ty, c);
				for (int i = 0; i < n; i++)
				{
					double u = ((double) p.zoneX * StrokeMesher.ZONE + p.x[first + i]) / 128 - tx;
					double v = ((double) p.zoneY * StrokeMesher.ZONE + p.y[first + i]) / 128 - ty;
					double z = south ? c[0] + (c[1] - c[0]) * u + (c[3] - c[0]) * v
						: c[2] + (c[3] - c[2]) * (1 - u) + (c[1] - c[2]) * (1 - v);
					assertEquals(z, p.z[first + i], 0.05);
				}
				first += n;
			}
		}
	}

	@Test
	public void unloadedTilesAreLeftOut()
	{
		StrokeMesher.Ground half = (x, y, out) -> x < 3202 && FLAT.corners(x, y, out);
		StrokeMesher.Mesh mesh = build(line(3200, 0f, 3204, 0f, 3200), 10, LineStyle.STRAIGHT, half);
		// From x = 3200 tiles, less the half cap on the west side, to the end of tile 3201.
		double expected = 2 * 128 * 10 + Math.PI * 5 * 5 / 2;
		assertEquals(expected, area(mesh), expected * 0.01);
	}

	@Test
	public void oneDotIsARoundDot()
	{
		List<StrokePoint> pts = new ArrayList<>();
		pts.add(new StrokePoint(3200, 3200, 0.5f, 0.5f));
		StrokeMesher.Mesh mesh = build(pts, 40, LineStyle.THROUGH_POINTS, FLAT);
		// The round cap is straight lines, so it has a little less area than a circle.
		assertEquals(Math.PI * 20 * 20, area(mesh), Math.PI * 20 * 20 * 0.03);
		assertNull(StrokeMesher.build(new ArrayList<>(), 0, 0, 40, LineStyle.STRAIGHT, FLAT));
	}

	/** The ground covered by the polygons, as one area in world units. */
	private static java.awt.geom.Area covered(List<StrokeMesher.Mesh> meshes)
	{
		java.awt.geom.Area all = new java.awt.geom.Area();
		for (StrokeMesher.Mesh mesh : meshes)
		{
			for (StrokeMesher.Piece p : mesh.pieces)
			{
				int first = 0;
				for (int k = 0; k < p.polygons; k++)
				{
					java.awt.geom.Path2D.Double poly = new java.awt.geom.Path2D.Double();
					for (int i = 0; i < p.sizes[k]; i++)
					{
						double x = (double) p.zoneX * StrokeMesher.ZONE + p.x[first + i] - 3200 * 128;
						double y = (double) p.zoneY * StrokeMesher.ZONE + p.y[first + i] - 3200 * 128;
						if (i == 0)
						{
							poly.moveTo(x, y);
						}
						else
						{
							poly.lineTo(x, y);
						}
					}
					poly.closePath();
					// A little larger, so the shared edges of polygons join.
					all.add(new java.awt.geom.Area(new java.awt.BasicStroke(0.02f).createStrokedShape(poly)));
					all.add(new java.awt.geom.Area(poly));
					first += p.sizes[k];
				}
			}
		}
		return all;
	}

	private static double area(java.awt.geom.Area a)
	{
		double sum = 0;
		double[] c = new double[6];
		double sx = 0, sy = 0, lx = 0, ly = 0;
		for (java.awt.geom.PathIterator it = a.getPathIterator(null, 0.1); !it.isDone(); it.next())
		{
			int type = it.currentSegment(c);
			if (type == java.awt.geom.PathIterator.SEG_MOVETO)
			{
				sx = lx = c[0];
				sy = ly = c[1];
				continue;
			}
			double x = type == java.awt.geom.PathIterator.SEG_CLOSE ? sx : c[0];
			double y = type == java.awt.geom.PathIterator.SEG_CLOSE ? sy : c[1];
			sum += lx * y - x * ly;
			lx = x;
			ly = y;
		}
		return Math.abs(sum / 2);
	}

	@Test
	public void partsJoinLikeTheWholeStroke()
	{
		List<StrokePoint> pts = new ArrayList<>();
		for (int i = 0; i < 12; i++)
		{
			pts.add(new StrokePoint(3200 + i / 3, 3200 + (i % 4 == 0 ? 1 : 0), (i % 3) / 3f, (i * 7 % 10) / 10f));
		}
		for (LineStyle style : LineStyle.values())
		{
			java.awt.geom.Area whole = covered(java.util.Collections.singletonList(build(pts, 30, style, FLAT)));
			List<StrokeMesher.Mesh> parts = new ArrayList<>();
			for (int from = 0; from < pts.size() - 1; from += 4)
			{
				parts.add(StrokeMesher.build(pts, from, Math.min(from + 4, pts.size() - 1), 30, style, FLAT));
			}
			java.awt.geom.Area joined = covered(parts);
			double wholeArea = area(whole);
			java.awt.geom.Area difference = new java.awt.geom.Area(whole);
			difference.exclusiveOr(joined);
			assertTrue(style + " differs by " + area(difference), area(difference) < wholeArea * 0.01);
		}
	}

	@Test
	public void longScribbleIsFastEnough()
	{
		Random random = new Random(1);
		List<StrokePoint> pts = new ArrayList<>();
		double x = 3200 * 128, y = 3200 * 128, angle = 0;
		for (int i = 0; i < 2000; i++)
		{
			angle += random.nextGaussian() * 0.5;
			x += Math.cos(angle) * 12;
			y += Math.sin(angle) * 12;
			pts.add(new StrokePoint((int) Math.floor(x / 128), (int) Math.floor(y / 128),
				(float) (x / 128 - Math.floor(x / 128)), (float) (y / 128 - Math.floor(y / 128))));
		}
		for (LineStyle style : LineStyle.values())
		{
			long c0 = System.nanoTime();
			int chunkFaces = 0;
			for (int from = 0; from < pts.size() - 1; from += 48)
			{
				chunkFaces += StrokeMesher.build(pts, from, Math.min(from + 48, pts.size() - 1), 25, style, SLOPE).pieces.stream().mapToInt(p -> p.faces).sum();
			}
			System.out.println("chunks of 48: " + (System.nanoTime() - c0) / 1_000_000 + " ms, " + chunkFaces + " faces");
			long start = System.nanoTime();
			StrokeMesher.Mesh mesh = build(pts, 25, style, SLOPE);
			long ms = (System.nanoTime() - start) / 1_000_000;
			int faces = mesh.pieces.stream().mapToInt(p -> p.faces).sum();
			System.out.println(style + ": " + ms + " ms, " + faces + " faces, " + mesh.pieces.size() + " zones");
		}
	}
}

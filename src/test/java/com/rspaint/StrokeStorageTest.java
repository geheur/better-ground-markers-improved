package com.rspaint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.rspaint.RsPaintPlugin.Stroke;
import com.rspaint.RsPaintPlugin.StrokePoint;
import java.awt.Color;
import java.util.List;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;

public class StrokeStorageTest
{
	// The same Gson that RuneLite gives the plugin, so Color is written the same way.
	private static final Gson GSON = RuneLiteAPI.GSON;
	private final StrokeStorage storage = new StrokeStorage(GSON);

	/** A region as the original plugin saved it: a plain array, no id, order or plane. */
	private static String unversionedText(Color color)
	{
		return "[{\"pts\":[{\"x\":3200,\"y\":3201,\"subx\":0.5,\"suby\":0.25},{\"x\":3201,\"y\":3201,\"subx\":0.0,\"suby\":1.0}],"
			+ "\"color\":" + GSON.toJson(color) + ",\"strokeWidth\":3.0}]";
	}

	@Test
	public void readsUnversionedArrayAsVersionZero() throws Exception
	{
		StrokeStorage.Loaded loaded = storage.read(unversionedText(Color.RED));

		assertEquals(0, loaded.getVersion());
		assertEquals(1, loaded.getStrokes().size());
		Stroke stroke = loaded.getStrokes().get(0);
		assertEquals(Color.RED, stroke.color);
		assertEquals(3.0, stroke.strokeWidth, 0);
		assertEquals(0, stroke.plane);
		assertEquals(0, stroke.order);
		assertNotNull(stroke.id);
		assertEquals(new StrokePoint(3200, 3201, 0.5f, 0.25f), stroke.pts.get(0));
		assertEquals(2, stroke.pts.size());
	}

	@Test
	public void writeThenReadKeepsTheStrokes() throws Exception
	{
		Stroke stroke = storage.read(unversionedText(new Color(10, 20, 30, 128))).getStrokes().get(0);
		stroke.order = 42;
		stroke.plane = 2;

		String text = storage.write(List.of(stroke));
		assertTrue(text, text.startsWith("{\"version\":1,"));

		StrokeStorage.Loaded loaded = storage.read(text);
		assertEquals(StrokeStorage.FORMAT_VERSION, loaded.getVersion());
		Stroke read = loaded.getStrokes().get(0);
		assertEquals(stroke.id, read.id);
		assertEquals(42, read.order);
		assertEquals(2, read.plane);
		assertEquals(new Color(10, 20, 30, 128), read.color);
		assertEquals(stroke.pts, read.pts);
	}

	@Test
	public void skipsStrokesWithoutPointsOrColor() throws Exception
	{
		String text = "{\"version\":1,\"strokes\":[null,{\"pts\":[],\"color\":" + GSON.toJson(Color.RED) + "},"
			+ "{\"pts\":[{\"x\":1,\"y\":2,\"subx\":0,\"suby\":0}]}]}";
		assertTrue(storage.read(text).getStrokes().isEmpty());
	}

	@Test(expected = StrokeStorage.UnreadableStrokesException.class)
	public void refusesNewerVersion() throws Exception
	{
		storage.read("{\"version\":" + (StrokeStorage.FORMAT_VERSION + 1) + ",\"strokes\":[]}");
	}

	@Test(expected = StrokeStorage.UnreadableStrokesException.class)
	public void refusesMissingVersion() throws Exception
	{
		storage.read("{\"strokes\":[]}");
	}

	@Test(expected = StrokeStorage.UnreadableStrokesException.class)
	public void refusesBrokenJson() throws Exception
	{
		storage.read("[{\"pts\":");
	}
}

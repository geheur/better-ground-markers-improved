package com.rspaint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.rspaint.PartyCanvasSync.SyncedStroke;
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

	private static final StrokePoint FIRST = new StrokePoint(3200, 3201, 0.5f, 0.25f);
	private static final StrokePoint LAST = new StrokePoint(3201, 3201, 0.75f, 0.5f);

	private static String version1Stroke(Color color, String extraFields)
	{
		return "{\"pts\":[{\"x\":3200,\"y\":3201,\"subx\":0.5,\"suby\":0.25},{\"x\":3201,\"y\":3201,\"subx\":0.75,\"suby\":0.5}],"
			+ "\"color\":" + GSON.toJson(color) + ",\"strokeWidth\":3.0" + extraFields + "}";
	}

	@Test
	public void readsOriginalArrayAsVersion1()
	{
		StrokeStorage.Loaded loaded = read("[" + version1Stroke(Color.RED, "") + "," + version1Stroke(Color.BLUE, "") + "]");

		assertEquals(1, loaded.getVersion());
		assertEquals(2, loaded.getStrokes().size());
		SyncedStroke stroke = loaded.getStrokes().get(0);
		assertEquals(Color.RED.getRGB(), stroke.getColor());
		assertEquals(3.0, stroke.getStrokeWidth(), 0);
		assertEquals(0, stroke.getPlane());
		assertNotNull(stroke.getId());
		assertEquals(List.of(FIRST, LAST), stroke.getPoints());
		// Strokes without a draw order keep the order of the list.
		assertEquals(1, stroke.getOrder());
		assertEquals(2, loaded.getStrokes().get(1).getOrder());
	}

	@Test
	public void readsWrappedVersion1WithIdOrderAndPlane()
	{
		String text = "{\"version\":1,\"strokes\":[" + version1Stroke(Color.RED, ",\"id\":\"abc\",\"order\":42,\"plane\":2") + "]}";
		SyncedStroke stroke = read(text).getStrokes().get(0);

		assertEquals("abc", stroke.getId());
		assertEquals(42, stroke.getOrder());
		assertEquals(2, stroke.getPlane());
	}

	@Test
	public void writesVersion2WithEncodedPoints()
	{
		SyncedStroke stroke = new SyncedStroke("abc", new Color(10, 20, 30, 128).getRGB(), 3, 42, 2, List.of(FIRST, LAST), null);

		String text = storage.write(List.of(stroke));
		assertTrue(text, text.startsWith("{\"version\":2,"));
		assertTrue(text, text.contains("\"p\":\""));
		assertFalse(text, text.contains("subx"));

		StrokeStorage.Loaded loaded = read(text);
		assertEquals(2, loaded.getVersion());
		assertEquals(List.of(stroke), loaded.getStrokes());
	}

	@Test
	public void version1SavesSmallerAsVersion2()
	{
		String version1 = "[" + version1Stroke(Color.RED, "") + "]";
		String version2 = storage.write(read(version1).getStrokes());
		assertTrue(version2, version2.length() < version1.length());
		assertEquals(read(version1).getStrokes().get(0).getPoints(), read(version2).getStrokes().get(0).getPoints());
	}

	@Test
	public void writesInDrawOrder()
	{
		SyncedStroke top = new SyncedStroke("b", 0, 1, 5, 0, List.of(FIRST), null);
		SyncedStroke bottom = new SyncedStroke("a", 0, 1, 3, 0, List.of(FIRST), null);
		assertEquals(List.of(bottom, top), read(storage.write(List.of(top, bottom))).getStrokes());
	}

	@Test
	public void skipsBadStrokes()
	{
		String version1 = "{\"version\":1,\"strokes\":[null,{\"pts\":[],\"color\":" + GSON.toJson(Color.RED) + "},"
			+ "{\"pts\":[{\"x\":1,\"y\":2,\"subx\":0,\"suby\":0}]}]}";
		assertTrue(read(version1).getStrokes().isEmpty());

		String version2 = "{\"version\":2,\"strokes\":[null,{\"i\":\"a\",\"p\":\"not base64!\"},{\"i\":\"b\",\"z\":9,\"p\":\"\"},{\"i\":\"c\"}]}";
		assertTrue(read(version2).getStrokes().isEmpty());
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

	private StrokeStorage.Loaded read(String text)
	{
		try
		{
			return storage.read(text);
		}
		catch (StrokeStorage.UnreadableStrokesException e)
		{
			throw new AssertionError(e);
		}
	}
}

package com.rspaint;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.rspaint.PartyCanvasSync.SyncedStroke;
import com.rspaint.RsPaintPlugin.Stroke;
import com.rspaint.RsPaintPlugin.StrokePoint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;

/**
 * Turns strokes into JSON text and back. The saved strokes of a region (the config key strokes_[region id]) and the
 * exports through the clipboard use the same text and the same format version.
 *
 * <p>read() finds the version of the text and reads it with the parser of that version. To change the format, raise
 * FORMAT_VERSION, keep the parser of each old version, and add the new version to read().
 */
@Singleton
class StrokeStorage
{
	/**
	 * The format of the text.
	 * Version 1: the format of the original plugin. A plain JSON array of Stroke objects. Each point is a JSON object
	 * with x, y, subx and suby, and the color is a Color object. The strokes have no id, draw order or plane.
	 * Version 2: {"version":2,"strokes":[...]}. Each stroke is a SyncedStroke: an id, an ARGB color, a width,
	 * a draw order, a plane, and the points as one StrokeCodec string.
	 */
	static final int FORMAT_VERSION = 2;

	// The longest text that read() accepts. Real text is much shorter.
	private static final int MAX_TEXT_LENGTH = 32 * 1024 * 1024;

	private final Gson gson;

	@Inject
	StrokeStorage(Gson gson)
	{
		this.gson = gson;
	}

	/**
	 * The text cannot be read. The message is for the player.
	 * The overlay must not save over saved text that it cannot read, so the strokes are not lost.
	 */
	static class UnreadableStrokesException extends Exception
	{
		UnreadableStrokesException(String message)
		{
			super(message);
		}

		UnreadableStrokesException(String message, Throwable cause)
		{
			super(message, cause);
		}
	}

	/** The strokes of the text in the current format, and the format version that the text had. */
	@Value
	static class Loaded
	{
		int version;
		List<SyncedStroke> strokes;
	}

	@Value
	private static class Saved
	{
		int version;
		List<SyncedStroke> strokes;
	}

	/** Returns the strokes as text in the current format, sorted in draw order. */
	String write(List<SyncedStroke> strokes)
	{
		List<SyncedStroke> sorted = new ArrayList<>(strokes);
		sorted.sort(SyncedStroke.DRAW_ORDER);
		return gson.toJson(new Saved(FORMAT_VERSION, sorted));
	}

	/** Returns the valid strokes of the text in the current format, in the order of the text. Bad strokes are skipped. */
	Loaded read(String text) throws UnreadableStrokesException
	{
		if (text.length() > MAX_TEXT_LENGTH)
		{
			throw new UnreadableStrokesException("The text is too long.");
		}

		int version;
		List<SyncedStroke> strokes;
		try
		{
			JsonElement root = gson.fromJson(text, JsonElement.class);
			if (root != null && root.isJsonArray())
			{
				version = 1;
				strokes = readVersion1(root);
			}
			else if (root != null && root.isJsonObject())
			{
				version = formatVersion(root.getAsJsonObject());
				JsonElement list = root.getAsJsonObject().get("strokes");
				if (list == null || !list.isJsonArray())
				{
					throw new UnreadableStrokesException("The text has no stroke list.");
				}
				// Builds of this branch before version 2 saved version 1 strokes in {"version":1,"strokes":[...]}.
				strokes = version == 1 ? readVersion1(list) : Arrays.asList(gson.fromJson(list, SyncedStroke[].class));
			}
			else
			{
				throw new UnreadableStrokesException("The text is not drawing JSON.");
			}
		}
		catch (JsonParseException | IllegalStateException | NumberFormatException e)
		{
			throw new UnreadableStrokesException("The text is not valid drawing JSON.", e);
		}

		List<SyncedStroke> result = new ArrayList<>();
		for (SyncedStroke stroke : strokes)
		{
			SyncedStroke clean = clean(stroke);
			if (clean != null)
			{
				result.add(clean);
			}
		}
		return new Loaded(version, result);
	}

	private static int formatVersion(JsonObject root) throws UnreadableStrokesException
	{
		JsonElement version = root.get("version");
		if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber() || version.getAsInt() < 1)
		{
			throw new UnreadableStrokesException("The text has no valid format version.");
		}
		if (version.getAsInt() > FORMAT_VERSION)
		{
			throw new UnreadableStrokesException("A newer version of the plugin made this text (format "
				+ version.getAsInt() + "). Update the plugin.");
		}
		return version.getAsInt();
	}

	/**
	 * Reads version 1 strokes into the current format. A stroke without a draw order gets the position in the list,
	 * so the strokes keep their order under all newer strokes. A stroke without a plane is on plane 0.
	 * The points are simplified, as the overlay does with a new stroke, so the strokes save smaller.
	 */
	private List<SyncedStroke> readVersion1(JsonElement list)
	{
		Stroke[] strokes = gson.fromJson(list, Stroke[].class);
		List<SyncedStroke> result = new ArrayList<>();
		for (int i = 0; i < strokes.length; i++)
		{
			Stroke stroke = strokes[i];
			if (stroke == null || stroke.color == null || stroke.pts == null || stroke.pts.isEmpty() || stroke.pts.contains(null))
			{
				continue;
			}
			if (stroke.order <= 0)
			{
				stroke.order = i + 1;
			}
			result.add(PartyCanvasSync.toSynced(stroke, StrokeCodec.simplify(stroke.pts)));
		}
		return result;
	}

	/** Returns the stroke with valid values, or null if it has no valid points or plane. */
	private static SyncedStroke clean(SyncedStroke stroke)
	{
		if (stroke == null || stroke.getPoints() == null || !Stroke.isValidPlane(stroke.getPlane()))
		{
			return null;
		}

		List<StrokePoint> points = new ArrayList<>();
		for (StrokePoint point : stroke.getPoints())
		{
			if (point != null && Float.isFinite(point.getSubx()) && Float.isFinite(point.getSuby()))
			{
				points.add(new StrokePoint(point.getX(), point.getY(), clamp01(point.getSubx()), clamp01(point.getSuby())));
			}
		}
		if (points.isEmpty())
		{
			return null;
		}

		double width = Stroke.validWidth(stroke.getStrokeWidth());
		return new SyncedStroke(stroke.getId(), stroke.getColor(), width, stroke.getOrder(), stroke.getPlane(), points);
	}

	private static float clamp01(float value)
	{
		return Math.max(0f, Math.min(1f, value));
	}
}

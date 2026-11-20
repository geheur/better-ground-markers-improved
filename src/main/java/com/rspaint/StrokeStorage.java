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

// New format: raise FORMAT_VERSION, keep old parsers, add the version to read()
@Singleton
class StrokeStorage
{
	// 1: JSON array of Stroke, points as objects, Color object, no id/order/plane
	// 2: {"version":2,"strokes":[...]} of SyncedStroke, points as StrokeCodec string
	// "r":true (rainbow) is skipped by other readers, so no version bump
	static final int FORMAT_VERSION = 2;

	private static final int MAX_TEXT_LENGTH = 32 * 1024 * 1024;

	private final Gson gson;

	@Inject
	StrokeStorage(Gson gson)
	{
		this.gson = gson;
	}

	// Message is shown to the player. Overlay must not save over unreadable text
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

	String write(List<SyncedStroke> strokes)
	{
		List<SyncedStroke> sorted = new ArrayList<>(strokes);
		sorted.sort(SyncedStroke.DRAW_ORDER);
		return gson.toJson(new Saved(FORMAT_VERSION, sorted));
	}

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
				// Earlier builds saved version 1 as {"version":1,"strokes":[...]}
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

	// Missing order becomes list position, so old strokes stay under newer ones
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
		return new SyncedStroke(stroke.getId(), stroke.getColor(), width, stroke.getOrder(), stroke.getPlane(), points,
			stroke.isRainbow() ? Boolean.TRUE : null);
	}

	private static float clamp01(float value)
	{
		return Math.max(0f, Math.min(1f, value));
	}
}

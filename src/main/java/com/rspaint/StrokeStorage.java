package com.rspaint;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.rspaint.RsPaintPlugin.Stroke;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;

/**
 * Turns the saved strokes of one region into config text and back.
 *
 * <p>The text is a JSON object with a format version and a list of strokes: {"version":1,"strokes":[...]}.
 * Text saved before versions existed is a plain JSON array of strokes. read() treats it as version 0.
 *
 * <p>To change the format, raise FORMAT_VERSION and add a step to migrate(). Old text then still loads,
 * and the overlay saves it again in the new format.
 */
@Singleton
class StrokeStorage
{
	/**
	 * The format of the saved strokes.
	 * Version 0: a plain JSON array of Stroke objects, saved before versions existed.
	 * Version 1: the same strokes in {"version":1,"strokes":[...]}. The points are JSON objects with x, y, subx and suby.
	 */
	static final int FORMAT_VERSION = 1;

	private static final Type STROKE_LIST_TYPE = new TypeToken<List<Stroke>>(){}.getType();

	private final Gson gson;

	@Inject
	StrokeStorage(Gson gson)
	{
		this.gson = gson;
	}

	/** The saved text cannot be read. The overlay must not save over it, so the strokes are not lost. */
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

	/** The strokes of saved text, and the format version of the text before migrate() changed it. */
	@Value
	static class Loaded
	{
		int version;
		List<Stroke> strokes;
	}

	@Value
	private static class Saved
	{
		int version;
		List<Stroke> strokes;
	}

	/** Returns the strokes as config text in the current format. */
	String write(List<Stroke> strokes)
	{
		return gson.toJson(new Saved(FORMAT_VERSION, strokes));
	}

	/** Returns the strokes in the text, in the order of the text. Strokes without points or color are skipped. */
	Loaded read(String text) throws UnreadableStrokesException
	{
		JsonObject root;
		int version;
		List<Stroke> strokes;
		try
		{
			// Read the JSON as a tree first, so that migrate() can change an old format before Gson reads it into classes.
			JsonElement element = gson.fromJson(text, JsonElement.class);
			if (element != null && element.isJsonArray())
			{
				version = 0;
				root = new JsonObject();
				root.add("strokes", element);
			}
			else if (element != null && element.isJsonObject())
			{
				root = element.getAsJsonObject();
				version = formatVersion(root);
			}
			else
			{
				throw new UnreadableStrokesException("The saved strokes are not a JSON array or object.");
			}

			if (version > FORMAT_VERSION)
			{
				throw new UnreadableStrokesException("A newer version of the plugin saved these strokes (format " + version + ").");
			}
			migrate(root, version);

			JsonElement list = root.get("strokes");
			if (list == null || !list.isJsonArray())
			{
				throw new UnreadableStrokesException("The saved strokes have no stroke list.");
			}
			strokes = gson.fromJson(list, STROKE_LIST_TYPE);
		}
		catch (JsonParseException | IllegalStateException | NumberFormatException e)
		{
			throw new UnreadableStrokesException("The saved strokes are not valid JSON.", e);
		}

		List<Stroke> result = new ArrayList<>();
		for (Stroke stroke : strokes)
		{
			if (stroke != null && stroke.color != null && stroke.pts != null && !stroke.pts.isEmpty() && !stroke.pts.contains(null))
			{
				result.add(stroke);
			}
		}
		return new Loaded(version, result);
	}

	private static int formatVersion(JsonObject root) throws UnreadableStrokesException
	{
		JsonElement version = root.get("version");
		if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber() || version.getAsInt() < 1)
		{
			throw new UnreadableStrokesException("The saved strokes have no valid format version.");
		}
		return version.getAsInt();
	}

	/**
	 * Changes the JSON of an older format version into the current format. Each step changes one version into the next,
	 * so text of any older version goes through all of the later steps. The steps change the JSON tree,
	 * because the JSON of an old format can fail to read into the current classes.
	 */
	private static void migrate(JsonObject root, int version)
	{
		if (version < 1)
		{
			// Version 0 strokes have the same shape as version 1. Only the version is new.
			// Strokes without an id, draw order or plane get them when Gson makes the Stroke and when the overlay loads it.
			root.addProperty("version", 1);
		}
		// A step for version 2 goes here, for example:
		// if (version < 2) { for (JsonObject stroke : strokeObjects(root)) { ...change stroke to the version 2 shape... } }
	}

	/** Returns the stroke objects in the list of the root. Use it in the steps of migrate(). */
	static List<JsonObject> strokeObjects(JsonObject root)
	{
		List<JsonObject> result = new ArrayList<>();
		JsonElement strokes = root.get("strokes");
		if (strokes instanceof JsonArray)
		{
			for (JsonElement stroke : strokes.getAsJsonArray())
			{
				if (stroke.isJsonObject())
				{
					result.add(stroke.getAsJsonObject());
				}
			}
		}
		return result;
	}
}

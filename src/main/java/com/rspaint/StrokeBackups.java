package com.rspaint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Saves saved text in an old format to a file before the overlay saves it again in the current format.
 * The files are in .runelite/plugin-data/rspaint/backups. The player can import a file through the panel.
 */
@Slf4j
@Singleton
class StrokeBackups
{
	static final String DIRECTORY = "backups";

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	// The most files with the same name and time. Real backups never get near this.
	private static final int MAX_ATTEMPTS = 100;

	private final RsPaintPlugin plugin;
	private final ScheduledExecutorService executor;

	@Inject
	StrokeBackups(RsPaintPlugin plugin, ScheduledExecutorService executor)
	{
		this.plugin = plugin;
		this.executor = executor;
	}

	/**
	 * Writes the text to a new file on a background thread. The future fails if the file cannot be written.
	 *
	 * @param key the config key of the text, for example strokes_12850
	 * @param version the format version of the text
	 */
	CompletableFuture<Void> save(String key, int version, String text)
	{
		String time = LocalDateTime.now().format(TIME);
		return CompletableFuture.runAsync(() ->
		{
			try
			{
				Filepath file = write(key, version, time, text);
				log.debug("Saved a backup of {} (format {}) to {}", key, version, file);
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		}, executor);
	}

	private Filepath write(String key, int version, String time, String text) throws IOException
	{
		Filepath directory = plugin.dataDirectory().joinSegment(DIRECTORY);
		directory.createDirectories();
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)
		{
			Filepath file = directory.joinSegment(fileName(key, version, time, attempt));
			try
			{
				// Never write over an older backup.
				file.write(text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				return file;
			}
			catch (FileAlreadyExistsException e)
			{
				// Try the next name.
			}
		}
		throw new IOException("All backup names for " + key + " at " + time + " are in use.");
	}

	/** Returns the file name, for example strokes_12850_format1_20261109-153012.json. Attempts after the first get a number. */
	static String fileName(String key, int version, String time, int attempt)
	{
		String suffix = attempt <= 1 ? "" : "_" + attempt;
		return key + "_format" + version + "_" + time + suffix + ".json";
	}
}

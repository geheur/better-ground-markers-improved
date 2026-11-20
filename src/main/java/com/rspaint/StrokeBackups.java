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

// Backs up old-format text before it is rewritten
@Slf4j
@Singleton
class StrokeBackups
{
	static final String DIRECTORY = "backups";

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private static final int MAX_ATTEMPTS = 100;

	private final RsPaintPlugin plugin;
	private final ScheduledExecutorService executor;

	@Inject
	StrokeBackups(RsPaintPlugin plugin, ScheduledExecutorService executor)
	{
		this.plugin = plugin;
		this.executor = executor;
	}

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
				// Never overwrite older backups
				file.write(text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				return file;
			}
			catch (FileAlreadyExistsException e)
			{
			}
		}
		throw new IOException("All backup names for " + key + " at " + time + " are in use.");
	}

	// Like strokes_12850_format1_20261109-153012.json, number suffix after the first attempt
	static String fileName(String key, int version, String time, int attempt)
	{
		String suffix = attempt <= 1 ? "" : "_" + attempt;
		return key + "_format" + version + "_" + time + suffix + ".json";
	}
}

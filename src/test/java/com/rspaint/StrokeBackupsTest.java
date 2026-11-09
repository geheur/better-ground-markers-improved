package com.rspaint;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class StrokeBackupsTest
{
	@Test
	public void firstAttemptHasNoNumber()
	{
		assertEquals("strokes_12850_format1_20261109-153012.json",
			StrokeBackups.fileName("strokes_12850", 1, "20261109-153012", 1));
	}

	@Test
	public void laterAttemptsGetANumber()
	{
		assertEquals("strokes_12850_format1_20261109-153012_2.json",
			StrokeBackups.fileName("strokes_12850", 1, "20261109-153012", 2));
	}
}

/*
 * Copyright (c) 2026, maiz
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
/*
 * Adapted from FlatModel in HD World Markers (https://github.com/Maiz19/in-world-tile-markers),
 * BSD 2-Clause License, copyright (c) 2026, maiz.
 */
package com.rspaint;

import java.awt.Color;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;

/**
 * Makes the faces of a carrier model show one flat color. The lit colors, the unlit colors and the vertex normals are
 * all set, because renderers read different ones: the GPU plugin reads faceColors1 to 3, and 117 HD can read the unlit
 * colors and lights faces with the vertex normals.
 */
final class FlatModel
{
	/** Packed HSL white: no saturation, full lightness. */
	static final int WHITE = 127;

	/** The smallest alpha that a white layer gets. Less white does not show, so it is left out. */
	private static final int MIN_WHITE = 16;

	/** WHITE as sRGB, as 117 HD converts it. */
	private static final float[] WHITE_RGB = rgb(WHITE);

	private FlatModel()
	{
	}

	/**
	 * Writes the color as up to two layers into out, as {hsl, alpha, white alpha}. With the lightness cap of 117 HD, a
	 * color that is lighter than the cap is the hue and saturation at the highest lightness that the cap lets through,
	 * with white over it, at an alpha that makes both together look like the color. Otherwise there is one layer, and
	 * the white alpha is 0.
	 */
	static void layers(Color color, int alpha, boolean lightnessCap, int[] out)
	{
		int hsl = JagexColor.rgbToHSL(color.getRGB(), 1.0) & 0xFFFF;
		out[0] = hsl;
		out[1] = alpha;
		out[2] = 0;
		if (!lightnessCap || alpha <= 0)
		{
			return;
		}
		int max = maxLightness(hsl >> 7 & 7);
		if ((hsl & 127) <= max)
		{
			return;
		}
		int capped = (hsl & ~127) | max;
		// The color as a mix of the capped color and white: C = t * W + (1 - t) * P.
		float[] p = rgb(capped);
		float cr = color.getRed() / 255f, cg = color.getGreen() / 255f, cb = color.getBlue() / 255f;
		float num = (cr - p[0]) * (WHITE_RGB[0] - p[0]) + (cg - p[1]) * (WHITE_RGB[1] - p[1]) + (cb - p[2]) * (WHITE_RGB[2] - p[2]);
		float den = 0;
		for (int i = 0; i < 3; i++)
		{
			den += (WHITE_RGB[i] - p[i]) * (WHITE_RGB[i] - p[i]);
		}
		float t = den > 0 ? Math.max(0, Math.min(1, num / den)) : 0;
		if (t <= 0)
		{
			return;
		}
		// White at alpha a * t over the capped color at a * (1 - t) / (1 - a * t) blends like the color at alpha a.
		float a = alpha / 255f, whiteAlpha = a * t;
		if (whiteAlpha * 255 < MIN_WHITE)
		{
			return;
		}
		float colorAlpha = whiteAlpha >= 1 ? 0 : a * (1 - t) / (1 - whiteAlpha);
		out[0] = capped;
		out[1] = Math.round(colorAlpha * 255);
		out[2] = Math.round(whiteAlpha * 255);
	}

	/** The lightness cap of 117 HD for a saturation from 0 to 7, as its undoVanillaShading. */
	private static int maxLightness(int saturation)
	{
		return (int) (127 - 72 * Math.pow(saturation / 7.0, 0.05));
	}

	/** Packed HSL as sRGB from 0 to 1, as 117 HD converts it (convertHsl, hslToSrgb). */
	private static float[] rgb(int hsl)
	{
		float h = (hsl >> 10 & 63) / 64f + 0.0078125f, s = (hsl >> 7 & 7) / 8f + 0.0625f, l = (hsl & 127) / 128f;
		float q = l < 0.5f ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
		return new float[]{hue(p, q, h + 1 / 3f), hue(p, q, h), hue(p, q, h - 1 / 3f)};
	}

	private static float hue(float p, float q, float t)
	{
		if (t < 0)
		{
			t += 1;
		}
		if (t > 1)
		{
			t -= 1;
		}
		if (t < 1 / 6f)
		{
			return p + (q - p) * 6 * t;
		}
		if (t < 1 / 2f)
		{
			return q;
		}
		if (t < 2 / 3f)
		{
			return p + (q - p) * (2 / 3f - t) * 6;
		}
		return p;
	}

	static void paint(Model m, int face, int hsl, int alpha)
	{
		m.getFaceColors1()[face] = hsl;
		m.getFaceColors2()[face] = hsl;
		// Three equal corner colors draw the face in one color, and -2 hides it. Not -1 ("flat"): for flat faces
		// 117 HD does not use the vertex normals and takes the normal of the triangle, which turns with the camera.
		m.getFaceColors3()[face] = alpha == 0 ? -2 : hsl;
		byte[] transparencies = m.getFaceTransparencies();
		if (transparencies != null)
		{
			transparencies[face] = (byte) (255 - alpha);
		}
		short[] unlit = m.getUnlitFaceColors();
		if (unlit != null)
		{
			unlit[face] = (short) hsl;
		}
	}

	static void hide(Model m, int face)
	{
		m.getFaceIndices1()[face] = 0;
		m.getFaceIndices2()[face] = 0;
		m.getFaceIndices3()[face] = 0;
		m.getFaceColors3()[face] = -2;
		byte[] transparencies = m.getFaceTransparencies();
		if (transparencies != null)
		{
			transparencies[face] = (byte) 255;
		}
	}

	/** Points all vertex normals straight up. Model y is down, and the unit length is 256. */
	static void normals(Model m)
	{
		int[] nx = m.getVertexNormalsX(), ny = m.getVertexNormalsY(), nz = m.getVertexNormalsZ();
		if (nx == null || ny == null || nz == null)
		{
			return;
		}
		for (int i = 0; i < nx.length; i++)
		{
			nx[i] = 0;
			ny[i] = -256;
			nz[i] = 0;
		}
	}

	/** Finds the cached bounding box again after the vertices changed. The bounds cylinder stays fixed. */
	static void bounds(Model m)
	{
		// The box is cached for each orientation, so a change to another orientation and back makes it again.
		m.getAABB(1);
		m.getAABB(0);
	}
}

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
 * Adapted from CarrierModels in HD World Markers (https://github.com/Maiz19/in-world-tile-markers),
 * BSD 2-Clause License, copyright (c) 2026, maiz. Changes for RsPaint: the largest model is known to the caller.
 */
package com.rspaint;

import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.gameval.ItemID;

@Singleton
class CarrierModels
{
	// Under GPU plugin ModelUploader limits
	static final int MAX_VERTICES = 6400;
	static final int MAX_FACES = 3900;

	private final Client client;
	private ModelData seed;
	// Empty seed, no retry until reset()
	private boolean seedFailed;
	// Null while loading, retry next frame
	private boolean triedThisFrame;

	@Inject
	CarrierModels(Client client)
	{
		this.client = client;
	}

	void nextFrame()
	{
		triedThisFrame = false;
	}

	private boolean seeded()
	{
		if (seed == null && !seedFailed && !triedThisFrame)
		{
			triedThisFrame = true;
			ModelData loaded = client.loadModelData(client.getItemDefinition(ItemID.BRONZE_DAGGER).getInventoryModel());
			if (loaded == null)
			{
				return false;
			}
			seedFailed = loaded.getVerticesCount() <= 0 || loaded.getFaceCount() <= 0;
			if (!seedFailed)
			{
				seed = loaded;
			}
		}
		return seed != null;
	}

	private int copies(int vertices, int faces)
	{
		return Math.max(-Math.floorDiv(-vertices, seed.getVerticesCount()), -Math.floorDiv(-faces, seed.getFaceCount()));
	}

	// Six vertices kept for bounds
	private int maxCopies()
	{
		return Math.max(2, Math.min((MAX_VERTICES - 6) / seed.getVerticesCount(), MAX_FACES / seed.getFaceCount()));
	}

	boolean fits(int vertices, int faces)
	{
		if (!seeded())
		{
			return vertices <= MAX_VERTICES / 2 && faces <= MAX_FACES / 2;
		}
		return copies(vertices + 6, faces) <= maxCopies();
	}

	// Merge keeps transparencies if any part has them
	boolean canMakeOpaque()
	{
		return seeded() && seed.getFaceTransparencies() == null;
	}

	// Bounds are found once, so temporary vertices at radius fix them; later geometry must stay inside
	// Transparent models are not drawn at 6000+ units across, opaque ones use the depth buffer
	Model create(int vertices, int faces, int radius, boolean transparent)
	{
		if (!seeded())
		{
			return null;
		}
		int count = Math.min(maxCopies(), Math.max(2, copies(vertices + 6, faces)));
		ModelData[] parts = new ModelData[count];
		for (int i = 0; i < count; i++)
		{
			// Merge joins same-position vertices, so offset each copy
			parts[i] = seed.shallowCopy().cloneVertices().translate(i * 2048, 0, 0);
		}
		ModelData data = client.mergeModels(parts).cloneVertices().cloneColors();
		// Transparency array selects the GPU sorted pass
		if (transparent)
		{
			data = data.cloneTransparencies(true);
		}
		if (data.getFaceTextures() != null)
		{
			data = data.cloneTextures();
		}
		// Never change game cache arrays
		if (data.getFaceIndices1() == seed.getFaceIndices1() || data.getFaceIndices2() == seed.getFaceIndices2()
			|| data.getFaceIndices3() == seed.getFaceIndices3() || data.getVerticesX() == seed.getVerticesX())
		{
			return null;
		}
		if (data.getFaceTextures() != null)
		{
			Arrays.fill(data.getFaceTextures(), (short) -1);
		}
		Model model = data.light();
		if (model == null || model.getVerticesCount() < vertices || model.getFaceCount() < faces
			|| (model.getFaceTransparencies() != null) != transparent)
		{
			return null;
		}
		for (int f = 0; f < model.getFaceCount(); f++)
		{
			FlatModel.hide(model, f);
		}
		// Seed priorities sort before depth, so zero them
		byte[] priorities = model.getFaceRenderPriorities();
		if (priorities != null)
		{
			Arrays.fill(priorities, (byte) 0);
		}
		float[] x = model.getVerticesX();
		float[] y = model.getVerticesY();
		float[] z = model.getVerticesZ();
		Arrays.fill(x, 0);
		Arrays.fill(y, 0);
		Arrays.fill(z, 0);
		x[0] = radius;
		x[1] = -radius;
		y[2] = radius;
		y[3] = -radius;
		z[4] = radius;
		z[5] = -radius;
		model.calculateBoundsCylinder();
		// Bounds fixed too early let geometry escape
		return model.getRadius() >= radius ? model : null;
	}

	void reset()
	{
		seed = null;
		seedFailed = false;
		triedThisFrame = false;
	}
}

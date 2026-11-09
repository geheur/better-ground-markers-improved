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

/**
 * Makes models whose arrays RsPaint owns and writes. The API cannot make an empty model, so copies of a small model
 * from the game cache are merged, and all of their geometry is replaced. The item is never shown.
 */
@Singleton
class CarrierModels
{
	/*
	 * The limits of the renderers for one model (GPU plugin ModelUploader: 6500 vertices, 4000 faces per priority).
	 * The renderers skip a larger model and all faces in it, so carriers stay below the limits.
	 */
	static final int MAX_VERTICES = 6400;
	static final int MAX_FACES = 3900;

	private final Client client;
	private ModelData seed;
	// The seed has no geometry. It is not tried again until reset().
	private boolean seedFailed;
	// The seed was tried in this frame. The client gives null while it still loads the model, so it is tried again
	// in the next frame.
	private boolean triedThisFrame;

	@Inject
	CarrierModels(Client client)
	{
		this.client = client;
	}

	/** Starts a new frame: a seed that was still loading in the last frame is tried again. */
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

	/** The most copies of the seed that stay in the renderer limits. Six more vertices fix the bounds. */
	private int maxCopies()
	{
		return Math.max(2, Math.min((MAX_VERTICES - 6) / seed.getVerticesCount(), MAX_FACES / seed.getFaceCount()));
	}

	/** Returns true if one model can hold this many vertices and faces. */
	boolean fits(int vertices, int faces)
	{
		if (!seeded())
		{
			return vertices <= MAX_VERTICES / 2 && faces <= MAX_FACES / 2;
		}
		return copies(vertices + 6, faces) <= maxCopies();
	}

	/**
	 * Returns true if create can make opaque models, which have no transparency array. The seed must not have one,
	 * because a merged model has the array if one of its parts has it.
	 */
	boolean canMakeOpaque()
	{
		return seeded() && seed.getFaceTransparencies() == null;
	}

	/**
	 * Returns a model with at least this many vertices and faces, all faces hidden, and bounds fixed to the radius
	 * around its origin. Returns null if the model cannot be made now.
	 *
	 * <p>The client finds the bounds of a model only once, so they are fixed here with temporary vertices at the
	 * radius. The geometry that is written later must stay in the radius, because the GPU plugin sorts faces in the bounds.
	 *
	 * <p>A transparent model has a transparency array, so its faces can have alpha. The GPU plugin sorts its faces and
	 * does not draw it if it is 6000 units or more across. An opaque model has no transparency array (see
	 * canMakeOpaque). The GPU plugin draws it with the depth buffer, at any size.
	 */
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
			// Merging joins vertices at the same position, so copies at the same place add no vertices. Each copy moves.
			parts[i] = seed.shallowCopy().cloneVertices().translate(i * 2048, 0, 0);
		}
		ModelData data = client.mergeModels(parts).cloneVertices().cloneColors();
		// The GPU plugin draws a model with a transparency array in its sorted pass for transparent faces.
		if (transparent)
		{
			data = data.cloneTransparencies(true);
		}
		if (data.getFaceTextures() != null)
		{
			data = data.cloneTextures();
		}
		// Never change the arrays of the game cache.
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
		// The faces of the seed have render priorities, which renderers sort by before depth. A stroke face in such a
		// slot would be drawn over all other faces.
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
		// If the bounds were fixed before, geometry could go out of them.
		return model.getRadius() >= radius ? model : null;
	}

	/** Forgets the seed, for example after the plugin turns off. */
	void reset()
	{
		seed = null;
		seedFailed = false;
		triedThisFrame = false;
	}
}

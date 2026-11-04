package com.rspaint;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.rspaint.RsPaintPlugin.Stroke;
import com.rspaint.RsPaintPlugin.StrokeClock;
import com.rspaint.RsPaintPlugin.StrokePoint;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.PartyMemberMessage;
import net.runelite.client.party.messages.PartyMessage;
import net.runelite.client.party.messages.UserSync;

/** Sends the local canvas to party members and keeps a copy of each other member's canvas. */
@Slf4j
@Singleton
public class PartyCanvasSync
{
	private static final long FLUSH_INTERVAL_MS = 200;
	// A full sync goes out at most once in this time. If members join or ask during the wait, one full sync answers all of them.
	private static final long FULL_SYNC_INTERVAL_MS = 10_000;

	// Payload of one stroke in a message, measured with Gson: about 76 bytes for the stroke and 2.7 bytes for each point.
	// The plane adds 6 bytes ("z":0,) to the stroke. StrokeCodec encodes the points. As plain JSON objects, each point was about 55 bytes.
	private static final int STROKE_BYTES = 86;
	private static final int POINT_BYTES = 3;
	// Complete strokes go out in messages of at most about this many bytes.
	private static final int MAX_MESSAGE_BYTES = 32 * 1024;

	// A full sync sends only the newest strokes, so that a big canvas does not flood the party.
	// Older strokes stay on your screen, but party members lose them on the next full sync.
	// A stroke gets at most one point each frame, so a line drawn in 1 second at 50 FPS has about 50 points.
	// StrokeCodec.simplify keeps about 25 of them. Then 1000 strokes are about 150 KB in about 5 messages, all sent in one flush.
	// A line of 200 points keeps about 100, and 1000 of those are about 390 KB in about 12 messages.
	// A member canvas also keeps only this many strokes, the newest ones, so a member cannot fill your memory.
	private static final int MAX_SYNCED_STROKES = 1000;
	// A member canvas keeps at most this many points in each stroke, and drops the points after them.
	// Members send the points of a stroke while it is drawn, before simplify. A stroke gets at most one point each frame,
	// and only after the mouse moves 3 screen pixels. So 5000 points are a line of at least 15000 pixels, about 8 times
	// across a 1920-pixel screen, and more than 1.5 minutes of drawing without a stop at 50 FPS. Real strokes are much shorter.
	private static final int MAX_STROKE_POINTS = 5000;
	// A member canvas keeps at most this many points in all its strokes. If a member sends more, the oldest strokes go.
	// Without this limit, one member could send 1000 strokes of 5000 points, and drawing them would freeze the client.
	// A full sync of 1000 strokes that simplify keeps at 100 points each fits.
	private static final int MAX_MEMBER_POINTS = 100_000;

	@Inject private ClientThread clientThread;
	@Inject private PartyService partyService;
	@Inject private WSClient wsClient;
	@Inject private StrokeClock clock;

	// Client thread only.
	private final Map<Long, MemberCanvas> canvases = new HashMap<>();
	// Changed from the Swing thread by the side panel.
	private final Set<Long> hiddenMembers = ConcurrentHashMap.newKeySet();

	// Outgoing changes waiting for the next flush. Client thread only.
	private final Map<Stroke, List<StrokePoint>> pendingPoints = new LinkedHashMap<>();
	private final List<String> pendingErases = new ArrayList<>();
	// Strokes that undo, redo, a region load or an import put back. They go out complete and replace the receiver's copy.
	private final Set<Stroke> pendingRestores = new LinkedHashSet<>();
	private boolean fullSyncPending = false;
	private boolean canvasRequestPending = false;
	private long lastFlush = 0;
	private long lastFullSync = 0;

	void startUp()
	{
		wsClient.registerMessage(RsPaintStrokeAppend.class);
		wsClient.registerMessage(RsPaintStrokeErase.class);
		wsClient.registerMessage(RsPaintCanvasSync.class);
		wsClient.registerMessage(RsPaintCanvasClear.class);
		wsClient.registerMessage(RsPaintCanvasRequest.class);

		clientThread.invokeLater(() -> {
			fullSyncPending = true;
			canvasRequestPending = true;
		});
	}

	void shutDown()
	{
		wsClient.unregisterMessage(RsPaintStrokeAppend.class);
		wsClient.unregisterMessage(RsPaintStrokeErase.class);
		wsClient.unregisterMessage(RsPaintCanvasSync.class);
		wsClient.unregisterMessage(RsPaintCanvasClear.class);
		wsClient.unregisterMessage(RsPaintCanvasRequest.class);

		// The overlay is already removed, so no flush can follow this clear.
		clientThread.invokeLater(() -> {
			if (canSend())
			{
				send(new RsPaintCanvasClear());
			}
			canvases.clear();
			clearPending();
		});
		hiddenMembers.clear();
	}

	boolean isMemberVisible(long memberId)
	{
		return !hiddenMembers.contains(memberId);
	}

	void setMemberVisible(long memberId, boolean visible)
	{
		if (visible)
		{
			hiddenMembers.remove(memberId);
		}
		else
		{
			hiddenMembers.add(memberId);
		}
	}

	/** Gives each stroke of the shown members that is in the regions and on the plane to the action. */
	void forEachVisibleStroke(int[] regions, int plane, Consumer<Stroke> action)
	{
		if (regions == null)
		{
			return;
		}

		for (Map.Entry<Long, MemberCanvas> entry : canvases.entrySet())
		{
			if (hiddenMembers.contains(entry.getKey()))
			{
				continue;
			}

			for (int region : regions)
			{
				List<Stroke> strokes = entry.getValue().strokesByRegion.get(region);
				if (strokes == null)
				{
					continue;
				}
				for (Stroke stroke : strokes)
				{
					if (stroke.plane == plane)
					{
						action.accept(stroke);
					}
				}
			}
		}
	}

	/** Returns a copy of the member's strokes. Client thread only. */
	List<SyncedStroke> memberStrokes(long memberId)
	{
		List<SyncedStroke> result = new ArrayList<>();
		MemberCanvas canvas = canvases.get(memberId);
		if (canvas != null)
		{
			for (Stroke stroke : canvas.strokesById.values())
			{
				result.add(toSynced(stroke, stroke.pts));
			}
		}
		return result;
	}

	/** Returns a copy of the strokes of all members that are not hidden. Client thread only. */
	List<SyncedStroke> visibleMemberStrokes()
	{
		List<SyncedStroke> result = new ArrayList<>();
		for (Long memberId : canvases.keySet())
		{
			if (!hiddenMembers.contains(memberId))
			{
				result.addAll(memberStrokes(memberId));
			}
		}
		return result;
	}

	void onPointAdded(Stroke stroke, StrokePoint point)
	{
		if (partyService.isInParty())
		{
			pendingPoints.computeIfAbsent(stroke, k -> new ArrayList<>()).add(point);
		}
	}

	void onStrokesErased(Collection<Stroke> strokes)
	{
		if (!partyService.isInParty())
		{
			return;
		}

		for (Stroke stroke : strokes)
		{
			pendingPoints.remove(stroke);
			pendingRestores.remove(stroke);
			pendingErases.add(stroke.id);
		}
	}

	/**
	 * Sends the complete strokes on the next flush, without a reset. Each stroke replaces the receiver's copy.
	 * Undo, redo, region loads and imports use this. Client thread only.
	 */
	void onStrokesRestored(Collection<Stroke> strokes)
	{
		if (!partyService.isInParty())
		{
			return;
		}

		for (Stroke stroke : strokes)
		{
			// If the erase is not sent yet, receivers still have the stroke. A replace is correct in both cases.
			pendingErases.remove(stroke.id);
			pendingPoints.remove(stroke);
			pendingRestores.add(stroke);
		}
	}

	void onCanvasCleared()
	{
		clearPending();
		if (canSend())
		{
			send(new RsPaintCanvasClear());
		}
	}

	/** Called on each client tick. Sends at most once per FLUSH_INTERVAL_MS. */
	void flush(Map<Integer, List<Stroke>> localStrokesByRegion)
	{
		long now = System.currentTimeMillis();
		if (now - lastFlush < FLUSH_INTERVAL_MS)
		{
			return;
		}
		lastFlush = now;

		if (!canSend())
		{
			clearPending();
			return;
		}

		// Drop the pending changes even if a send fails, so the error does not repeat on every tick.
		try
		{
			sendPending(localStrokesByRegion, now);
		}
		catch (RuntimeException e)
		{
			log.warn("Could not send canvas changes to the party", e);
		}
		finally
		{
			pendingPoints.clear();
			pendingErases.clear();
			pendingRestores.clear();
		}
	}

	private void sendPending(Map<Integer, List<Stroke>> localStrokesByRegion, long now)
	{
		if (canvasRequestPending)
		{
			canvasRequestPending = false;
			send(new RsPaintCanvasRequest());
		}

		// If the last full sync is too recent, the full sync stays pending and goes out on a later flush.
		// It reads the canvas when it goes out, so it has all changes from the wait.
		if (fullSyncPending && now - lastFullSync >= FULL_SYNC_INTERVAL_MS)
		{
			fullSyncPending = false;
			lastFullSync = now;
			List<Stroke> strokes = new ArrayList<>();
			localStrokesByRegion.values().forEach(strokes::addAll);
			// Receivers sort by the order in each stroke. Old versions of the plugin use the message order.
			strokes.sort(Stroke.DRAW_ORDER);
			// The newest strokes are at the end of the list.
			int skipped = Math.max(0, strokes.size() - MAX_SYNCED_STROKES);
			sendCompleteStrokes(strokes.subList(skipped, strokes.size()), true);
		}

		// After the full sync, so that its reset does not remove these strokes again.
		if (!pendingRestores.isEmpty())
		{
			// A big import can restore many strokes. Receivers keep only the newest MAX_SYNCED_STROKES, so send only those.
			List<Stroke> strokes = new ArrayList<>(pendingRestores);
			strokes.sort(Stroke.DRAW_ORDER);
			int skipped = Math.max(0, strokes.size() - MAX_SYNCED_STROKES);
			sendCompleteStrokes(strokes.subList(skipped, strokes.size()), false);
		}

		if (!pendingPoints.isEmpty())
		{
			List<SyncedStroke> strokes = new ArrayList<>();
			for (Map.Entry<Stroke, List<StrokePoint>> entry : pendingPoints.entrySet())
			{
				strokes.add(toSynced(entry.getKey(), entry.getValue()));
			}
			send(new RsPaintStrokeAppend(strokes));
		}

		if (!pendingErases.isEmpty())
		{
			send(new RsPaintStrokeErase(new ArrayList<>(pendingErases)));
		}
	}

	/**
	 * Sends the complete strokes in chunks of about MAX_MESSAGE_BYTES at most. A stroke larger than that goes in a chunk alone.
	 * If reset is true, the first chunk resets the receiver's canvas. Without a reset, each stroke replaces the receiver's copy.
	 */
	private void sendCompleteStrokes(List<Stroke> strokes, boolean reset)
	{
		List<SyncedStroke> chunk = new ArrayList<>();
		int chunkBytes = 0;
		for (Stroke stroke : strokes)
		{
			if (stroke.pts.isEmpty())
			{
				continue;
			}

			pendingPoints.remove(stroke);

			int strokeBytes = STROKE_BYTES + POINT_BYTES * stroke.pts.size();
			if (!chunk.isEmpty() && chunkBytes + strokeBytes > MAX_MESSAGE_BYTES)
			{
				send(new RsPaintCanvasSync(reset, chunk));
				reset = false;
				chunk = new ArrayList<>();
				chunkBytes = 0;
			}
			chunk.add(toSynced(stroke, stroke.pts));
			chunkBytes += strokeBytes;
		}

		// A reset goes out even when empty, so receivers drop old strokes.
		if (!chunk.isEmpty() || reset)
		{
			send(new RsPaintCanvasSync(reset, chunk));
		}
	}

	static SyncedStroke toSynced(Stroke stroke, List<StrokePoint> points)
	{
		return new SyncedStroke(stroke.id, stroke.color.getRGB(), stroke.strokeWidth, stroke.order, stroke.plane, new ArrayList<>(points));
	}

	/** Returns a local stroke with the id, draw order and points of the synced stroke. A stroke without an id gets a new id. */
	static Stroke fromSynced(SyncedStroke synced)
	{
		Stroke stroke = new Stroke();
		if (synced.getId() != null)
		{
			stroke.id = synced.getId();
		}
		stroke.color = new Color(synced.getColor(), true);
		stroke.strokeWidth = synced.getStrokeWidth();
		stroke.order = synced.getOrder();
		stroke.plane = synced.getPlane();
		stroke.pts.addAll(synced.getPoints());
		return stroke;
	}

	private boolean canSend()
	{
		return partyService.isInParty() && partyService.getLocalMember() != null;
	}

	private void send(PartyMessage message)
	{
		try
		{
			partyService.send(message);
		}
		catch (IllegalStateException e)
		{
			log.debug("Could not send party message {}", message, e);
		}
	}

	private void clearPending()
	{
		pendingPoints.clear();
		pendingErases.clear();
		pendingRestores.clear();
		fullSyncPending = false;
		canvasRequestPending = false;
	}

	/** Returns the sender's canvas, or null if the sender is the local player or not in the party. */
	private MemberCanvas canvasFor(PartyMemberMessage message)
	{
		PartyMember local = partyService.getLocalMember();
		if (local != null && local.getMemberId() == message.getMemberId())
		{
			return null;
		}
		if (partyService.getMemberById(message.getMemberId()) == null)
		{
			return null;
		}
		return canvases.computeIfAbsent(message.getMemberId(), k -> new MemberCanvas());
	}

	@Subscribe
	public void onRsPaintStrokeAppend(RsPaintStrokeAppend message)
	{
		clientThread.invokeLater(() -> {
			MemberCanvas canvas = canvasFor(message);
			if (canvas != null && message.getStrokes() != null)
			{
				message.getStrokes().forEach(canvas::append);
			}
		});
	}

	@Subscribe
	public void onRsPaintStrokeErase(RsPaintStrokeErase message)
	{
		clientThread.invokeLater(() -> {
			MemberCanvas canvas = canvasFor(message);
			if (canvas != null && message.getStrokeIds() != null)
			{
				message.getStrokeIds().forEach(canvas::remove);
			}
		});
	}

	@Subscribe
	public void onRsPaintCanvasSync(RsPaintCanvasSync message)
	{
		clientThread.invokeLater(() -> {
			MemberCanvas canvas = canvasFor(message);
			if (canvas == null)
			{
				return;
			}
			if (message.isReset())
			{
				canvas.clear();
			}
			if (message.getStrokes() != null)
			{
				message.getStrokes().forEach(canvas::replace);
			}
		});
	}

	@Subscribe
	public void onRsPaintCanvasClear(RsPaintCanvasClear message)
	{
		clientThread.invokeLater(() -> {
			MemberCanvas canvas = canvasFor(message);
			if (canvas != null)
			{
				canvas.clear();
			}
		});
	}

	/** Returns true if the local player sent the message. The party server sends each message back to its sender too. */
	private boolean isFromLocalMember(PartyMemberMessage message)
	{
		PartyMember local = partyService.getLocalMember();
		return local != null && local.getMemberId() == message.getMemberId();
	}

	// Our own request comes back too. startUp already queued our full sync, so ignore it.
	@Subscribe
	public void onRsPaintCanvasRequest(RsPaintCanvasRequest message)
	{
		clientThread.invokeLater(() -> {
			if (!isFromLocalMember(message))
			{
				fullSyncPending = true;
			}
		});
	}

	// A joining member sends UserSync. Our own UserSync comes back too. onUserJoin already queued our full sync, so ignore it.
	@Subscribe
	public void onUserSync(UserSync message)
	{
		clientThread.invokeLater(() -> {
			if (!isFromLocalMember(message))
			{
				fullSyncPending = true;
			}
		});
	}

	@Subscribe
	public void onUserJoin(UserJoin event)
	{
		clientThread.invokeLater(() -> {
			PartyMember local = partyService.getLocalMember();
			if (local != null && local.getMemberId() == event.getMemberId())
			{
				fullSyncPending = true;
			}
		});
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		clientThread.invokeLater(() -> canvases.remove(event.getMemberId()));
		hiddenMembers.remove(event.getMemberId());
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		clientThread.invokeLater(() -> {
			canvases.clear();
			clearPending();
			// The members of the new party do not have the canvas yet, so the wait does not apply to them.
			lastFullSync = 0;
		});
		hiddenMembers.clear();
	}

	private class MemberCanvas
	{
		final Map<Integer, List<Stroke>> strokesByRegion = new HashMap<>();
		final Map<String, Stroke> strokesById = new HashMap<>();
		// The same strokes in draw order, so the oldest stroke is first.
		final TreeSet<Stroke> strokesByOrder = new TreeSet<>(Stroke.DRAW_ORDER);
		// The number of points in all strokes of the canvas.
		int totalPoints = 0;

		void append(SyncedStroke synced)
		{
			Stroke stroke = strokesById.get(synced.getId());
			if (stroke == null)
			{
				add(synced);
			}
			else if (synced.getPoints() != null)
			{
				totalPoints += addPoints(stroke, synced.getPoints());
				removeOldestOverBudget();
			}
		}

		void replace(SyncedStroke synced)
		{
			remove(synced.getId());
			add(synced);
		}

		void remove(String id)
		{
			Stroke stroke = strokesById.remove(id);
			if (stroke != null)
			{
				strokesByOrder.remove(stroke);
				totalPoints -= stroke.pts.size();
				List<Stroke> strokes = strokesByRegion.get(stroke.primaryRegion());
				if (strokes != null)
				{
					strokes.remove(stroke);
				}
			}
		}

		void clear()
		{
			strokesByRegion.clear();
			strokesById.clear();
			strokesByOrder.clear();
			totalPoints = 0;
		}

		private void add(SyncedStroke synced)
		{
			if (synced.getId() == null || synced.getPoints() == null || synced.getPoints().isEmpty()
				|| !Stroke.isValidPlane(synced.getPlane()))
			{
				return;
			}

			Stroke stroke = new Stroke();
			stroke.id = synced.getId();
			stroke.color = new Color(synced.getColor(), true);
			// Do not trust the width from the message. A very large width is slow to draw.
			stroke.strokeWidth = Stroke.validWidth(synced.getStrokeWidth());
			stroke.order = clock.receive(synced.getOrder());
			stroke.plane = synced.getPlane();
			addPoints(stroke, synced.getPoints());

			// Keep the newest MAX_SYNCED_STROKES strokes in draw order, the same strokes as the member's full sync.
			// A new stroke of the member is always the newest, so the oldest kept stroke goes. A stroke that is older
			// than all kept strokes, for example an old stroke that undo puts back, is dropped. A full sync would drop it too.
			if (strokesById.size() >= MAX_SYNCED_STROKES)
			{
				Stroke oldest = strokesByOrder.first();
				if (Stroke.DRAW_ORDER.compare(stroke, oldest) < 0)
				{
					return;
				}
				remove(oldest.id);
			}

			strokesById.put(stroke.id, stroke);
			strokesByOrder.add(stroke);
			strokesByRegion.computeIfAbsent(stroke.primaryRegion(), k -> new ArrayList<>()).add(stroke);
			totalPoints += stroke.pts.size();
			removeOldestOverBudget();
		}

		/** Adds the points to the stroke, but not past MAX_STROKE_POINTS. Returns the number of points added. */
		private int addPoints(Stroke stroke, List<StrokePoint> points)
		{
			int room = MAX_STROKE_POINTS - stroke.pts.size();
			if (room <= 0)
			{
				return 0;
			}
			int added = Math.min(room, points.size());
			stroke.pts.addAll(points.subList(0, added));
			return added;
		}

		/**
		 * Removes the oldest strokes until the canvas has at most MAX_MEMBER_POINTS points.
		 * A stroke has at most MAX_STROKE_POINTS points, so the newest stroke always stays.
		 */
		private void removeOldestOverBudget()
		{
			while (totalPoints > MAX_MEMBER_POINTS && !strokesByOrder.isEmpty())
			{
				remove(strokesByOrder.first().id);
			}
		}
	}

	/**
	 * A stroke, or a part of a stroke, that is sent to party members, exported, or saved as JSON (StrokeStorage version 2).
	 * The JSON names are one letter, and the points are one encoded string, because a full sync can have 1000 strokes.
	 */
	@Value
	static class SyncedStroke
	{
		/** From the stroke that draws first, at the bottom, to the stroke that draws last, on top. */
		static final Comparator<SyncedStroke> DRAW_ORDER = Comparator.comparingLong(SyncedStroke::getOrder)
			.thenComparing(SyncedStroke::getId, Comparator.nullsFirst(Comparator.naturalOrder()));

		@SerializedName("i")
		String id;
		@SerializedName("c")
		int color;
		@SerializedName("w")
		double strokeWidth;
		/** The draw order from StrokeClock. It is 0 if the sender has an old version of the plugin. */
		@SerializedName("o")
		long order;
		/** The plane (floor) of the stroke, from 0 to 3. */
		@SerializedName("z")
		int plane;
		@SerializedName("p")
		@JsonAdapter(StrokeCodec.PointsAdapter.class)
		List<StrokePoint> points;
	}

	// Party messages. The party server finds the class by its simple name, so the names start with RsPaint.

	/** Adds new points to the end of strokes. The receiver makes the stroke if it does not have it yet. */
	@Value
	@EqualsAndHashCode(callSuper = true)
	public static class RsPaintStrokeAppend extends PartyMemberMessage
	{
		List<SyncedStroke> strokes;
	}

	/** Removes strokes from the sender's canvas. */
	@Value
	@EqualsAndHashCode(callSuper = true)
	public static class RsPaintStrokeErase extends PartyMemberMessage
	{
		List<String> strokeIds;
	}

	/**
	 * Sends complete strokes from the sender's canvas.
	 * If reset is true, the receiver removes all of the sender's strokes before it adds these strokes.
	 */
	@Value
	@EqualsAndHashCode(callSuper = true)
	public static class RsPaintCanvasSync extends PartyMemberMessage
	{
		boolean reset;
		List<SyncedStroke> strokes;
	}

	/** Removes all strokes from the sender's canvas. */
	@Value
	@EqualsAndHashCode(callSuper = true)
	public static class RsPaintCanvasClear extends PartyMemberMessage
	{
	}

	/** Asks all party members to send their canvas again. */
	@Value
	@EqualsAndHashCode(callSuper = true)
	public static class RsPaintCanvasRequest extends PartyMemberMessage
	{
	}
}

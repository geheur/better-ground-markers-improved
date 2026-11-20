package com.rspaint;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.rspaint.RsPaintPlugin.Stroke;
import com.rspaint.RsPaintPlugin.StrokeClock;
import com.rspaint.RsPaintPlugin.StrokePoint;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
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

// Syncs canvases with party members
@Slf4j
@Singleton
public class PartyCanvasSync
{
	private static final long FLUSH_INTERVAL_MS = 200;
	// Joins and requests inside the gap share one full sync
	private static final long FULL_SYNC_INTERVAL_MS = 10_000;

	// Gson sizes: stroke 76 bytes plus 6 for plane, 2.7 per encoded point
	private static final int STROKE_BYTES = 86;
	private static final int POINT_BYTES = 3;
	private static final int MAX_MESSAGE_BYTES = 32 * 1024;

	// Newest only, older strokes stay local. 1000 strokes are about 150 to 390 KB
	// Member canvases use the same cap
	private static final int MAX_SYNCED_STROKES = 1000;
	// About 1.5 minutes of nonstop drawing, far past real strokes
	private static final int MAX_STROKE_POINTS = 5000;
	// Per member canvas, oldest go first. Stops a flood freezing the client
	private static final int MAX_MEMBER_POINTS = 100_000;
	// Spreads a full sync over several flushes. An oversized message goes alone
	private static final int MAX_FLUSH_BYTES = 32 * 1024;
	// Id plus quotes and comma
	private static final int ID_BYTES = 16;
	// A forgotten id is just sent again
	private static final int MAX_SENT_IDS = 10 * MAX_SYNCED_STROKES;

	@Inject private ClientThread clientThread;
	@Inject private PartyService partyService;
	@Inject private WSClient wsClient;
	@Inject private StrokeClock clock;

	// Client thread only
	private final Map<Long, MemberCanvas> canvases = new HashMap<>();
	// Changed from the Swing thread
	private final Set<Long> hiddenMembers = ConcurrentHashMap.newKeySet();

	// Waiting for next flush, client thread only
	private final Map<Stroke, List<StrokePoint>> pendingPoints = new LinkedHashMap<>();
	private final List<String> pendingErases = new ArrayList<>();
	// Undo, redo, region load and import. Sent complete, replacing the receiver's copy
	private final Set<Stroke> pendingRestores = new LinkedHashSet<>();
	// Client thread only
	private final Deque<Outgoing> outbox = new ArrayDeque<>();
	// Oldest first, client thread only
	private final Set<String> sentIds = new LinkedHashSet<>();
	private boolean fullSyncPending = false;
	private boolean canvasRequestPending = false;
	// Monotonic, wall clock can go back
	private static final long NEVER = Long.MIN_VALUE;
	private long lastFlush = NEVER;
	private long lastFullSync = NEVER;

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

		// Overlay already removed, no flush follows
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

	// Client thread only
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

	// Client thread only
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
		// Receivers drop points past MAX_STROKE_POINTS
		if (partyService.isInParty() && stroke.pts.size() <= MAX_STROKE_POINTS)
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
			sentIds.remove(stroke.id);
		}
	}

	// Client thread only
	void onStrokesRestored(Collection<Stroke> strokes)
	{
		if (!partyService.isInParty())
		{
			return;
		}

		for (Stroke stroke : strokes)
		{
			// Erase drops the id, so undo of an erase always resends
			if (sentIds.contains(stroke.id))
			{
				continue;
			}
			// Replace is right even if the erase is still unsent
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

	// Called each client tick
	void flush(Map<Integer, List<Stroke>> localStrokesByRegion)
	{
		long now = monotonicMillis();
		if (!hasElapsed(lastFlush, now, FLUSH_INTERVAL_MS))
		{
			return;
		}
		lastFlush = now;

		if (!canSend())
		{
			clearPending();
			return;
		}

		// Drop pending even on failure, or the error repeats every tick
		try
		{
			queuePending(localStrokesByRegion, now);
			sendFromOutbox();
		}
		catch (RuntimeException e)
		{
			log.warn("Could not send canvas changes to the party", e);
			outbox.clear();
		}
		finally
		{
			pendingPoints.clear();
			pendingErases.clear();
			pendingRestores.clear();
		}
	}

	// At least one message, then up to MAX_FLUSH_BYTES
	private void sendFromOutbox()
	{
		int bytes = 0;
		while (!outbox.isEmpty())
		{
			Outgoing next = outbox.peekFirst();
			if (bytes > 0 && bytes + next.bytes > MAX_FLUSH_BYTES)
			{
				return;
			}
			outbox.removeFirst();
			bytes += next.bytes;
			send(next.message);
		}
	}

	private void markSent(String id)
	{
		// Newest last, oldest forgotten first
		sentIds.remove(id);
		sentIds.add(id);
		if (sentIds.size() > MAX_SENT_IDS)
		{
			Iterator<String> oldest = sentIds.iterator();
			oldest.next();
			oldest.remove();
		}
	}

	// Receivers drop points past MAX_STROKE_POINTS
	private static List<StrokePoint> sentPoints(Stroke stroke)
	{
		return stroke.pts.size() > MAX_STROKE_POINTS ? stroke.pts.subList(0, MAX_STROKE_POINTS) : stroke.pts;
	}

	// Needs draw order
	private static List<Stroke> newestKept(List<Stroke> strokes)
	{
		List<Stroke> kept = new ArrayList<>();
		int points = 0;
		for (int i = strokes.size() - 1; i >= 0 && kept.size() < MAX_SYNCED_STROKES; i--)
		{
			Stroke stroke = strokes.get(i);
			int size = sentPoints(stroke).size();
			if (size == 0)
			{
				continue;
			}
			if (points + size > MAX_MEMBER_POINTS)
			{
				break;
			}
			points += size;
			kept.add(stroke);
		}
		Collections.reverse(kept);
		return kept;
	}

	// Only differences are meaningful
	private static long monotonicMillis()
	{
		return System.nanoTime() / 1_000_000;
	}

	private static boolean hasElapsed(long since, long now, long intervalMs)
	{
		return since == NEVER || now - since >= intervalMs;
	}

	private void queuePending(Map<Integer, List<Stroke>> localStrokesByRegion, long now)
	{
		if (canvasRequestPending)
		{
			canvasRequestPending = false;
			// Skips the outbox, order does not matter
			send(new RsPaintCanvasRequest());
		}

		// Too recent stays pending, reads the canvas when queued
		if (fullSyncPending && hasElapsed(lastFullSync, now, FULL_SYNC_INTERVAL_MS))
		{
			fullSyncPending = false;
			lastFullSync = now;
			// Reset wipes earlier strokes on receivers
			outbox.clear();
			sentIds.clear();
			List<Stroke> strokes = new ArrayList<>();
			localStrokesByRegion.values().forEach(strokes::addAll);
			// Old plugin versions use message order
			strokes.sort(Stroke.DRAW_ORDER);
			queueCompleteStrokes(newestKept(strokes), true);
		}

		// After full sync, or its reset removes these
		if (!pendingRestores.isEmpty())
		{
			// Big imports: receivers keep only the newest
			List<Stroke> strokes = new ArrayList<>(pendingRestores);
			strokes.sort(Stroke.DRAW_ORDER);
			queueCompleteStrokes(newestKept(strokes), false);
		}

		if (!pendingPoints.isEmpty())
		{
			List<SyncedStroke> strokes = new ArrayList<>();
			int bytes = 0;
			for (Map.Entry<Stroke, List<StrokePoint>> entry : pendingPoints.entrySet())
			{
				strokes.add(toSynced(entry.getKey(), entry.getValue()));
				bytes += STROKE_BYTES + POINT_BYTES * entry.getValue().size();
				markSent(entry.getKey().id);
			}
			outbox.addLast(new Outgoing(new RsPaintStrokeAppend(strokes), bytes));
		}

		if (!pendingErases.isEmpty())
		{
			outbox.addLast(new Outgoing(new RsPaintStrokeErase(new ArrayList<>(pendingErases)), ID_BYTES * pendingErases.size()));
		}
	}

	// Oversized stroke goes alone
	private void queueCompleteStrokes(List<Stroke> strokes, boolean reset)
	{
		List<SyncedStroke> chunk = new ArrayList<>();
		int chunkBytes = 0;
		for (Stroke stroke : strokes)
		{
			List<StrokePoint> points = sentPoints(stroke);
			if (points.isEmpty())
			{
				continue;
			}

			pendingPoints.remove(stroke);

			int strokeBytes = STROKE_BYTES + POINT_BYTES * points.size();
			if (!chunk.isEmpty() && chunkBytes + strokeBytes > MAX_MESSAGE_BYTES)
			{
				outbox.addLast(new Outgoing(new RsPaintCanvasSync(reset, chunk), chunkBytes));
				reset = false;
				chunk = new ArrayList<>();
				chunkBytes = 0;
			}
			chunk.add(toSynced(stroke, points));
			chunkBytes += strokeBytes;
			markSent(stroke.id);
		}

		// Empty reset still clears receivers
		if (!chunk.isEmpty() || reset)
		{
			outbox.addLast(new Outgoing(new RsPaintCanvasSync(reset, chunk), chunkBytes));
		}
	}

	@Value
	private static class Outgoing
	{
		PartyMessage message;
		int bytes;
	}

	static SyncedStroke toSynced(Stroke stroke, List<StrokePoint> points)
	{
		return new SyncedStroke(stroke.id, stroke.color.getRGB(), stroke.strokeWidth, stroke.order, stroke.plane, new ArrayList<>(points),
			stroke.rainbow ? Boolean.TRUE : null);
	}

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
		stroke.rainbow = synced.isRainbow();
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
		outbox.clear();
		// Receivers cleared by caller or party gone
		sentIds.clear();
		fullSyncPending = false;
		canvasRequestPending = false;
	}

	// Null for local or non-party sender
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

	// Server echoes messages back to the sender
	private boolean isFromLocalMember(PartyMemberMessage message)
	{
		PartyMember local = partyService.getLocalMember();
		return local != null && local.getMemberId() == message.getMemberId();
	}

	// Own request echoes back, full sync already queued in startUp
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

	// Own UserSync echoes back, full sync already queued in onUserJoin
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
			// New party has no canvas yet, skip the gap
			lastFullSync = NEVER;
		});
		hiddenMembers.clear();
	}

	private class MemberCanvas
	{
		final Map<Integer, List<Stroke>> strokesByRegion = new HashMap<>();
		final Map<String, Stroke> strokesById = new HashMap<>();
		// Oldest first
		final TreeSet<Stroke> strokesByOrder = new TreeSet<>(Stroke.DRAW_ORDER);
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
			// Untrusted width, huge values draw slowly
			stroke.strokeWidth = Stroke.validWidth(synced.getStrokeWidth());
			stroke.order = clock.receive(synced.getOrder());
			stroke.plane = synced.getPlane();
			stroke.rainbow = synced.isRainbow();
			addPoints(stroke, synced.getPoints());

			// Same strokes a full sync keeps. Strokes older than all kept ones (undo) are dropped
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

		// Returns points added, capped by MAX_STROKE_POINTS
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

		// The newest always stays
		private void removeOldestOverBudget()
		{
			while (totalPoints > MAX_MEMBER_POINTS && !strokesByOrder.isEmpty())
			{
				remove(strokesByOrder.first().id);
			}
		}
	}

	// Wire and storage format (version 2), short names keep syncs small
	@Value
	static class SyncedStroke
	{
		// Bottom to top
		static final Comparator<SyncedStroke> DRAW_ORDER = Comparator.comparingLong(SyncedStroke::getOrder)
			.thenComparing(SyncedStroke::getId, Comparator.nullsFirst(Comparator.naturalOrder()));

		@SerializedName("i")
		String id;
		@SerializedName("c")
		int color;
		@SerializedName("w")
		double strokeWidth;
		// From StrokeClock, 0 from old plugin versions
		@SerializedName("o")
		long order;
		@SerializedName("z")
		int plane;
		@SerializedName("p")
		@JsonAdapter(StrokeCodec.PointsAdapter.class)
		List<StrokePoint> points;
		// Null when false to keep JSON short. Old versions draw Rainbow.FIRST
		@SerializedName("r")
		Boolean rainbow;

		boolean isRainbow()
		{
			return Boolean.TRUE.equals(rainbow);
		}
	}

	// Simple class names are on the wire

	// Receiver creates missing strokes
	@Value
	@EqualsAndHashCode(callSuper = true)
	public static class RsPaintStrokeAppend extends PartyMemberMessage
	{
		List<SyncedStroke> strokes;
	}

	@Value
	@EqualsAndHashCode(callSuper = true)
	public static class RsPaintStrokeErase extends PartyMemberMessage
	{
		List<String> strokeIds;
	}

	// Reset clears the sender's strokes first
	@Value
	@EqualsAndHashCode(callSuper = true)
	public static class RsPaintCanvasSync extends PartyMemberMessage
	{
		boolean reset;
		List<SyncedStroke> strokes;
	}

	@Value
	@EqualsAndHashCode(callSuper = true)
	public static class RsPaintCanvasClear extends PartyMemberMessage
	{
	}

	@Value
	@EqualsAndHashCode(callSuper = true)
	public static class RsPaintCanvasRequest extends PartyMemberMessage
	{
	}
}

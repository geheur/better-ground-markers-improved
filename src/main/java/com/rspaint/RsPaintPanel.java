package com.rspaint;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.rspaint.PartyCanvasSync.SyncedStroke;
import com.rspaint.RsPaintPlugin.Stroke;
import com.rspaint.RsPaintPlugin.StrokePoint;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.shadowlabel.JShadowedLabel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.SwingUtil;

/** The side panel, styled after the Time Tracking plugin's panel. */
@Slf4j
@Singleton
public class RsPaintPanel extends PluginPanel
{
	// PartyMember uses this name until the party service knows the real name.
	private static final String UNKNOWN_NAME = "<unknown>";

	// The same switch as the plugin list's on/off toggle.
	private static final ImageIcon SWITCH_ON;
	private static final ImageIcon SWITCH_OFF;

	static
	{
		BufferedImage on = ImageUtil.loadImageResource(RsPaintPanel.class, "switcher_on.png");
		SWITCH_ON = new ImageIcon(on);
		SWITCH_OFF = new ImageIcon(ImageUtil.flipImage(ImageUtil.luminanceScale(ImageUtil.grayscaleImage(on), 0.61f), true, false));
	}

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private PartyService partyService;
	@Inject private PartyCanvasSync partySync;
	@Inject private BGMIOverlay overlay;
	@Inject private DrawingJson drawingJson;

	private final JTextArea partyStatus = new WrappingTextArea();
	private final JPanel memberList = new JPanel(new DynamicGridLayout(0, 1, 0, 0));

	// Client thread only.
	private List<MemberRow> shownRows = null;

	@Value
	private static class MemberRow
	{
		long memberId;
		String name;
		boolean local;
	}

	@Inject
	RsPaintPanel()
	{
		super(false);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		partyStatus.setFont(FontManager.getRunescapeSmallFont());
		partyStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		partyStatus.setLineWrap(true);
		partyStatus.setWrapStyleWord(true);
		partyStatus.setEditable(false);
		partyStatus.setFocusable(false);
		partyStatus.setHighlighter(null);
		partyStatus.setOpaque(false);
		partyStatus.setBorder(null);

		memberList.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel display = new JPanel(new BorderLayout());
		display.setBackground(ColorScheme.DARK_GRAY_COLOR);
		display.setBorder(new EmptyBorder(10, 10, 8, 10));
		display.add(partyStatus, BorderLayout.NORTH);
		display.add(memberList, BorderLayout.CENTER);
		display.add(createButtons(), BorderLayout.SOUTH);

		JPanel wrapper = new ViewportWidthPanel();
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.add(display, BorderLayout.NORTH);

		JScrollPane scroll = new JScrollPane(wrapper);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setPreferredSize(new Dimension(16, 0));
		scroll.getVerticalScrollBar().setBorder(new EmptyBorder(0, 9, 0, 0));
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setBorder(null);

		add(scroll, BorderLayout.CENTER);
	}

	/** Builds the rows again on the Swing thread if the party members changed. */
	void refresh()
	{
		clientThread.invokeLater(() -> {
			List<MemberRow> rows = collectRows();
			if (rows.equals(shownRows))
			{
				return;
			}
			shownRows = rows;
			boolean inParty = partyService.isInParty();
			SwingUtilities.invokeLater(() -> rebuild(rows, inParty));
		});
	}

	/** Makes the next refresh build all rows again. */
	void reset()
	{
		clientThread.invokeLater(() -> shownRows = null);
	}

	private List<MemberRow> collectRows()
	{
		List<MemberRow> rows = new ArrayList<>();
		PartyMember local = partyService.getLocalMember();
		rows.add(new MemberRow(local != null ? local.getMemberId() : -1, localName(), true));

		if (partyService.isInParty())
		{
			for (PartyMember member : partyService.getMembers())
			{
				if (local == null || member.getMemberId() != local.getMemberId())
				{
					rows.add(new MemberRow(member.getMemberId(), memberName(member), false));
				}
			}
		}
		return rows;
	}

	private String localName()
	{
		Player player = client.getLocalPlayer();
		return player != null && player.getName() != null ? player.getName() : "You";
	}

	private static String memberName(PartyMember member)
	{
		String name = member.getDisplayName();
		return name == null || name.isEmpty() || UNKNOWN_NAME.equals(name) ? "Unknown member" : name;
	}

	private void rebuild(List<MemberRow> rows, boolean inParty)
	{
		partyStatus.setText(inParty
			? "Party members can see your drawings."
			: "Join a party in the Party plugin to share your drawings.");

		memberList.removeAll();
		for (MemberRow row : rows)
		{
			memberList.add(createRow(row));
		}
		memberList.revalidate();
		memberList.repaint();
	}

	private JPanel createRow(MemberRow row)
	{
		JPanel text = new JPanel(new GridLayout(2, 1));
		text.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		text.setBorder(new EmptyBorder(4, 4, 4, 0));

		JLabel name = new JShadowedLabel(row.getName());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		text.add(name);

		JLabel role = new JLabel(row.isLocal() ? "Your drawings" : "Party member");
		role.setFont(FontManager.getRunescapeSmallFont());
		role.setForeground(Color.GRAY);
		text.add(role);

		JToggleButton show = new JToggleButton();
		show.setIcon(SWITCH_OFF);
		show.setSelectedIcon(SWITCH_ON);
		SwingUtil.removeButtonDecorations(show);
		show.setPreferredSize(new Dimension(25, 15));
		SwingUtil.addModalTooltip(show, "Hide drawings", "Show drawings");
		if (row.isLocal())
		{
			show.setSelected(overlay.isShowOwnStrokes());
			show.addActionListener(e -> overlay.setShowOwnStrokes(show.isSelected()));
		}
		else
		{
			show.setSelected(partySync.isMemberVisible(row.getMemberId()));
			show.addActionListener(e -> partySync.setMemberVisible(row.getMemberId(), show.isSelected()));
		}

		JPanel tile = new JPanel(new BorderLayout());
		tile.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		tile.setBorder(new EmptyBorder(7, 7, 6, 10));
		tile.add(text, BorderLayout.CENTER);
		JPanel showWrapper = new JPanel(new GridBagLayout());
		showWrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		GridBagConstraints exportConstraints = new GridBagConstraints();
		exportConstraints.insets = new Insets(0, 0, 0, 8);
		showWrapper.add(createExportLink(row), exportConstraints);
		showWrapper.add(show);
		tile.add(showWrapper, BorderLayout.EAST);

		JPanel container = new JPanel(new BorderLayout());
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);
		container.setBorder(new EmptyBorder(7, 0, 0, 0));
		container.add(tile, BorderLayout.CENTER);
		return container;
	}

	private JLabel createExportLink(MemberRow row)
	{
		JLabel link = new JLabel("Export");
		link.setFont(FontManager.getRunescapeSmallFont());
		link.setForeground(Color.GRAY);
		link.setToolTipText(row.isLocal() ? "Copy your drawings in the area around you to the clipboard." : "Copy the drawings of " + row.getName() + " to the clipboard.");
		link.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (row.isLocal())
				{
					exportDrawings(overlay::ownStrokes);
				}
				else
				{
					exportDrawings(() -> partySync.memberStrokes(row.getMemberId()));
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				link.setForeground(Color.WHITE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				link.setForeground(Color.GRAY);
			}
		});
		return link;
	}

	private JPanel createButtons()
	{
		JPanel buttons = new JPanel(new DynamicGridLayout(0, 1, 0, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.add(createButton("Import drawings",
			"Add the drawings in the clipboard to your drawings. Party members see them too.", this::importDrawings));
		buttons.add(createButton("Export shown drawings",
			"Copy the drawings of all players whose switch is on to the clipboard.", this::exportShownDrawings));
		buttons.add(createButton("Reset canvas", "Delete your drawings in the area around you.", this::confirmReset));
		return buttons;
	}

	private JPanel createButton(String text, String tooltip, Runnable action)
	{
		JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(Color.WHITE);

		JPanel button = new JPanel(new BorderLayout());
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		button.setBorder(new EmptyBorder(8, 0, 8, 0));
		button.setToolTipText(tooltip);
		button.add(label, BorderLayout.CENTER);
		button.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				button.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});

		JPanel container = new JPanel(new BorderLayout());
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);
		container.setBorder(new EmptyBorder(10, 0, 0, 0));
		container.add(button, BorderLayout.CENTER);
		return container;
	}

	private void exportShownDrawings()
	{
		exportDrawings(() -> {
			List<SyncedStroke> strokes = new ArrayList<>();
			if (overlay.isShowOwnStrokes())
			{
				strokes.addAll(overlay.ownStrokes());
			}
			strokes.addAll(partySync.visibleMemberStrokes());
			return strokes;
		});
	}

	/** Collects the strokes on the client thread, then copies them to the clipboard on the Swing thread. */
	private void exportDrawings(Supplier<List<SyncedStroke>> collect)
	{
		clientThread.invokeLater(() -> {
			List<SyncedStroke> strokes = collect.get();
			SwingUtilities.invokeLater(() -> copyToClipboard(strokes));
		});
	}

	private void copyToClipboard(List<SyncedStroke> strokes)
	{
		if (strokes.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "There are no drawings to export.",
				"Export drawings", JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(drawingJson.write(strokes)), null);
		}
		catch (IllegalStateException e)
		{
			// Another program can hold the clipboard for a short time.
			log.warn("Could not copy drawings to the clipboard", e);
			JOptionPane.showMessageDialog(this, "Could not copy to the clipboard. Try again.",
				"Export drawings", JOptionPane.ERROR_MESSAGE);
			return;
		}

		JOptionPane.showMessageDialog(this,
			"Copied " + strokeCount(strokes.size()) + " to the clipboard. To import them, copy the text, then click Import drawings.",
			"Export drawings", JOptionPane.INFORMATION_MESSAGE);
	}

	private void importDrawings()
	{
		String text;
		try
		{
			text = readClipboardText();
		}
		catch (IllegalStateException | IOException | UnsupportedFlavorException e)
		{
			log.warn("Could not read the clipboard", e);
			JOptionPane.showMessageDialog(this, "Could not read the clipboard. Try again.",
				"Import drawings", JOptionPane.ERROR_MESSAGE);
			return;
		}

		if (text == null || text.trim().isEmpty())
		{
			JOptionPane.showMessageDialog(this, "The clipboard has no text. Copy an export, then click Import drawings.",
				"Import drawings", JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		List<SyncedStroke> strokes;
		try
		{
			strokes = drawingJson.read(text.trim());
		}
		catch (DrawingJson.InvalidDrawingException e)
		{
			log.debug("Could not import drawings from the clipboard", e);
			JOptionPane.showMessageDialog(this, "Could not import the clipboard: " + e.getMessage(),
				"Import drawings", JOptionPane.ERROR_MESSAGE);
			return;
		}

		if (strokes.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "The export in the clipboard has no drawings.",
				"Import drawings", JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		// The clipboard text is not visible, so the player confirms what the import adds.
		int result = JOptionPane.showConfirmDialog(this,
			"Add " + strokeCount(strokes.size()) + " from the clipboard to your drawings?"
				+ (overlay.isShowOwnStrokes() ? "" : " Turn on your switch to see them."),
			"Import drawings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (result == JOptionPane.OK_OPTION)
		{
			clientThread.invokeLater(() -> overlay.importStrokes(strokes));
		}
	}

	/** Returns the text in the clipboard, or null if the clipboard has no text. */
	private static String readClipboardText() throws IOException, UnsupportedFlavorException
	{
		Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
		if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor))
		{
			return null;
		}
		return (String) clipboard.getData(DataFlavor.stringFlavor);
	}

	private static String strokeCount(int count)
	{
		return count + (count == 1 ? " stroke" : " strokes");
	}

	private void confirmReset()
	{
		int result = JOptionPane.showConfirmDialog(this,
			"This deletes your drawings in the area around you. Your drawings in other places stay. You cannot undo this.",
			"Reset canvas", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (result == JOptionPane.OK_OPTION)
		{
			clientThread.invokeLater(overlay::clearCanvas);
		}
	}

	@Subscribe
	public void onUserJoin(UserJoin event)
	{
		refresh();
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		refresh();
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		refresh();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		refresh();
	}

	// Member names can arrive after the member joins.
	@Subscribe
	public void onGameTick(GameTick event)
	{
		refresh();
	}

	/** Measures its wrapped height at the parent's width, not the width from the last layout. */
	private static class WrappingTextArea extends JTextArea
	{
		@Override
		public Dimension getPreferredSize()
		{
			Container parent = getParent();
			int width;
			if (parent != null && parent.getWidth() > 0)
			{
				Insets insets = parent.getInsets();
				width = parent.getWidth() - insets.left - insets.right;
			}
			else
			{
				// Before the first layout: the panel width minus the 10 pixel side borders.
				width = PluginPanel.PANEL_WIDTH - 20;
			}

			if (width > 0 && width != getWidth())
			{
				setSize(width, Short.MAX_VALUE);
			}
			return super.getPreferredSize();
		}
	}

	/** Takes the width of the scroll pane's viewport, so its content wraps instead of overflowing. */
	private static class ViewportWidthPanel extends JPanel implements Scrollable
	{
		ViewportWidthPanel()
		{
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return visibleRect.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			// Fill the viewport when the content is shorter than it, and scroll when it is taller.
			return getParent() != null && getParent().getHeight() > getPreferredSize().height;
		}
	}

	static BufferedImage createIcon()
	{
		BufferedImage brush = ImageUtil.loadImageResource(RsPaintPanel.class, "paintbrush.png");
		return ImageUtil.resizeCanvas(ImageUtil.resizeImage(brush, 16, 16, true), 16, 16);
	}

	/**
	 * Turns strokes into JSON text and back, for export and import through the clipboard.
	 * The JSON holds a format version and a list of strokes. The list is in draw order: the first stroke draws first,
	 * at the bottom, and the last stroke draws on top.
	 */
	@Singleton
	static class DrawingJson
	{
		/**
		 * The format of the strokes in an export. When the format changes, raise this number and add a step to migrate().
		 * Version 1: strokes without a plane. Version 2: each stroke has its plane ("z").
		 */
		private static final int FORMAT_VERSION = 2;
		// The longest text that import reads. Real exports are much shorter.
		private static final int MAX_TEXT_LENGTH = 32 * 1024 * 1024;
		private static final String NOT_AN_EXPORT = "The text is not a drawing export.";

		@Inject private Gson gson;

		/** The text is not a drawing export. The message is for the player. */
		static class InvalidDrawingException extends Exception
		{
			InvalidDrawingException(String message)
			{
				super(message);
			}

			InvalidDrawingException(String message, Throwable cause)
			{
				super(message, cause);
			}
		}

		@Value
		private static class Export
		{
			int version;
			List<SyncedStroke> strokes;
		}

		/** Returns the strokes as JSON, sorted in draw order. */
		String write(List<SyncedStroke> strokes)
		{
			List<SyncedStroke> sorted = new ArrayList<>(strokes);
			sorted.sort(SyncedStroke.DRAW_ORDER);
			return gson.toJson(new Export(FORMAT_VERSION, sorted));
		}

		/** Returns the valid strokes in the text, in the order of the text. Strokes without points are skipped. */
		List<SyncedStroke> read(String text) throws InvalidDrawingException
		{
			if (text.length() > MAX_TEXT_LENGTH)
			{
				throw new InvalidDrawingException("The text is too long.");
			}

			Export export;
			try
			{
				// Read the JSON as a tree first, so that migrate() can change an old format before Gson reads it into classes.
				JsonElement root = gson.fromJson(text, JsonElement.class);
				if (root == null || !root.isJsonObject())
				{
					throw new InvalidDrawingException(NOT_AN_EXPORT);
				}
				int version = formatVersion(root.getAsJsonObject());
				if (version > FORMAT_VERSION)
				{
					throw new InvalidDrawingException("A newer version of the plugin made this export. Update the plugin.");
				}
				migrate(root.getAsJsonObject(), version);
				export = gson.fromJson(root, Export.class);
			}
			catch (JsonParseException e)
			{
				throw new InvalidDrawingException(NOT_AN_EXPORT, e);
			}

			if (export == null || export.getStrokes() == null)
			{
				throw new InvalidDrawingException(NOT_AN_EXPORT);
			}

			List<SyncedStroke> strokes = new ArrayList<>();
			for (SyncedStroke stroke : export.getStrokes())
			{
				SyncedStroke clean = clean(stroke);
				if (clean != null)
				{
					strokes.add(clean);
				}
			}
			return strokes;
		}

		/** Returns the format version of the export. */
		private static int formatVersion(JsonObject root) throws InvalidDrawingException
		{
			JsonElement version = root.get("version");
			if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber() || version.getAsInt() < 1)
			{
				throw new InvalidDrawingException(NOT_AN_EXPORT);
			}
			return version.getAsInt();
		}

		/**
		 * Changes the JSON of an older format version into the current format. Each step changes one version into the next,
		 * so an export of any older version goes through all of the later steps. The steps change the JSON tree,
		 * because the JSON of an old format can fail to read into the current classes.
		 */
		private static void migrate(JsonObject root, int version)
		{
			if (version < 2)
			{
				// Version 1 strokes have no plane, and they showed on all floors. Put them on the ground floor.
				for (JsonObject stroke : strokeObjects(root))
				{
					stroke.addProperty("z", 0);
				}
			}
		}

		private static List<JsonObject> strokeObjects(JsonObject root)
		{
			List<JsonObject> result = new ArrayList<>();
			JsonElement strokes = root.get("strokes");
			if (strokes != null && strokes.isJsonArray())
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
			return new SyncedStroke(stroke.getId(), stroke.getColor(), width, stroke.getOrder(), stroke.getPlane(), points);
		}

		private static float clamp01(float value)
		{
			return Math.max(0f, Math.min(1f, value));
		}
	}
}

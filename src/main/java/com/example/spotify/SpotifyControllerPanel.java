package com.example.spotify;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel for the Spotify Controller plugin. Purely a view: it renders
 * whatever state it's told to and forwards clicks to a Listener — all Spotify
 * API/auth logic lives in SpotifyControllerPlugin/SpotifyApiClient/SpotifyAuthManager.
 * Every mutator here must only be called from the Swing EDT (via
 * SwingUtilities.invokeLater at the call site).
 */
class SpotifyControllerPanel extends PluginPanel
{
	interface Listener
	{
		void onConnectClicked();

		void onDisconnectClicked();

		void onPlayPauseClicked(boolean currentlyPlaying);

		void onPreviousClicked();

		void onNextClicked();

		void onVolumeChanged(int volumePercent);

		void onBrowsePlaylistsRequested();

		void onSearchRequested(String query);

		void onPlaylistOpened(SpotifyPlaylist playlist);

		/**
		 * playlistContextUri is null when the track came from search results
		 * rather than an opened playlist (no context to keep playing through).
		 */
		void onTrackSelected(String trackUri, String playlistContextUri);
	}

	private static final int ART_SIZE = 150;

	private final Listener listener;

	private final JPanel disconnectedPanel = new JPanel();
	private final JPanel connectedPanel = new JPanel();
	private final JButton connectButton = new JButton("Connect to Spotify");
	private final JLabel statusLabel = new JLabel(" ", SwingConstants.CENTER);

	private final JLabel albumArtLabel = new JLabel();
	private final JLabel trackLabel = new JLabel();
	private final JLabel artistLabel = new JLabel();
	private final JLabel progressLabel = new JLabel();
	private final JButton prevButton = new JButton("◀◀");
	private final JButton playPauseButton = new JButton("▶");
	private final JButton nextButton = new JButton("▶▶");
	private final JSlider volumeSlider = new JSlider(0, 100, 100);
	private final JButton disconnectButton = new JButton("Disconnect");

	private final CardLayout cardLayout = new CardLayout();
	private final JPanel cardsPanel = new JPanel(cardLayout);
	private final JButton nowPlayingTabButton = new JButton("Now Playing");
	private final JButton browseTabButton = new JButton("Browse");
	private final JTextField searchField = new JTextField();
	private final JButton searchButton = new JButton("Search");
	private final JButton myPlaylistsButton = new JButton("My Playlists");
	private final JPanel rowsContainer = new JPanel();
	private final JLabel browseHintLabel = new JLabel(" ", SwingConstants.CENTER);

	private static final String CARD_NOW_PLAYING = "nowPlaying";
	private static final String CARD_BROWSE = "browse";

	private String lastAlbumArtUrl;
	private boolean currentlyPlaying;
	private boolean suppressVolumeEvent;
	private List<SpotifyPlaylist> lastPlaylists;

	SpotifyControllerPanel(Listener listener)
	{
		super(false);
		this.listener = listener;
		buildUi();
		showDisconnected();
	}

	private void buildUi()
	{
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		buildDisconnectedPanel();
		buildConnectedPanel();

		add(statusLabel, BorderLayout.NORTH);
		add(disconnectedPanel, BorderLayout.CENTER);

		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setBorder(new EmptyBorder(8, 8, 4, 8));
		statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
	}

	private void buildDisconnectedPanel()
	{
		disconnectedPanel.setLayout(new BorderLayout());
		disconnectedPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		disconnectedPanel.setBorder(new EmptyBorder(20, 10, 10, 10));

		connectButton.addActionListener(e -> listener.onConnectClicked());
		disconnectedPanel.add(connectButton, BorderLayout.NORTH);
	}

	private void buildConnectedPanel()
	{
		connectedPanel.setLayout(new BorderLayout(0, 8));
		connectedPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		connectedPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		JPanel tabBar = new JPanel(new GridLayout(1, 2, 4, 0));
		tabBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		nowPlayingTabButton.addActionListener(e -> cardLayout.show(cardsPanel, CARD_NOW_PLAYING));
		browseTabButton.addActionListener(e -> cardLayout.show(cardsPanel, CARD_BROWSE));
		styleTabButton(nowPlayingTabButton);
		styleTabButton(browseTabButton);
		tabBar.add(nowPlayingTabButton);
		tabBar.add(browseTabButton);

		cardsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		cardsPanel.add(buildNowPlayingCard(), CARD_NOW_PLAYING);
		cardsPanel.add(buildBrowseCard(), CARD_BROWSE);

		disconnectButton.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		disconnectButton.addActionListener(e -> listener.onDisconnectClicked());

		connectedPanel.add(tabBar, BorderLayout.NORTH);
		connectedPanel.add(cardsPanel, BorderLayout.CENTER);
		connectedPanel.add(disconnectButton, BorderLayout.SOUTH);
	}

	private void styleTabButton(JButton button)
	{
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		button.setForeground(ColorScheme.TEXT_COLOR);
		button.setFocusPainted(false);
		button.setFont(button.getFont().deriveFont(11f));
	}

	private JPanel buildNowPlayingCard()
	{
		JPanel card = new JPanel(new BorderLayout(0, 8));
		card.setBackground(ColorScheme.DARK_GRAY_COLOR);

		albumArtLabel.setHorizontalAlignment(SwingConstants.CENTER);
		albumArtLabel.setPreferredSize(new Dimension(ART_SIZE, ART_SIZE));

		JPanel infoPanel = new JPanel(new BorderLayout());
		infoPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		trackLabel.setForeground(ColorScheme.TEXT_COLOR);
		trackLabel.setFont(trackLabel.getFont().deriveFont(Font.BOLD, 13f));
		trackLabel.setHorizontalAlignment(SwingConstants.CENTER);

		artistLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		artistLabel.setFont(artistLabel.getFont().deriveFont(12f));
		artistLabel.setHorizontalAlignment(SwingConstants.CENTER);

		progressLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		progressLabel.setFont(progressLabel.getFont().deriveFont(10f));
		progressLabel.setHorizontalAlignment(SwingConstants.CENTER);

		JPanel textStack = new JPanel(new GridLayout(3, 1));
		textStack.setBackground(ColorScheme.DARK_GRAY_COLOR);
		textStack.add(trackLabel);
		textStack.add(artistLabel);
		textStack.add(progressLabel);

		infoPanel.add(albumArtLabel, BorderLayout.NORTH);
		infoPanel.add(textStack, BorderLayout.CENTER);

		JPanel transportPanel = new JPanel(new GridLayout(1, 3, 6, 0));
		transportPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		transportPanel.setBorder(new EmptyBorder(8, 0, 8, 0));
		prevButton.addActionListener(e -> listener.onPreviousClicked());
		playPauseButton.addActionListener(e -> listener.onPlayPauseClicked(currentlyPlaying));
		nextButton.addActionListener(e -> listener.onNextClicked());
		transportPanel.add(prevButton);
		transportPanel.add(playPauseButton);
		transportPanel.add(nextButton);

		JLabel volumeIcon = new JLabel("Volume");
		volumeIcon.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		volumeIcon.setFont(volumeIcon.getFont().deriveFont(10f));
		volumeIcon.setHorizontalAlignment(SwingConstants.CENTER);

		volumeSlider.setBackground(ColorScheme.DARK_GRAY_COLOR);
		ChangeListener volumeListener = e ->
		{
			if (!suppressVolumeEvent && !volumeSlider.getValueIsAdjusting())
			{
				listener.onVolumeChanged(volumeSlider.getValue());
			}
		};
		volumeSlider.addChangeListener(volumeListener);

		JPanel volumePanel = new JPanel(new BorderLayout());
		volumePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		volumePanel.add(volumeIcon, BorderLayout.NORTH);
		volumePanel.add(volumeSlider, BorderLayout.CENTER);

		JPanel bottomPanel = new JPanel(new BorderLayout(0, 6));
		bottomPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bottomPanel.add(transportPanel, BorderLayout.NORTH);
		bottomPanel.add(volumePanel, BorderLayout.CENTER);

		card.add(infoPanel, BorderLayout.NORTH);
		card.add(bottomPanel, BorderLayout.CENTER);
		return card;
	}

	private JPanel buildBrowseCard()
	{
		JPanel card = new JPanel(new BorderLayout(0, 6));
		card.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel searchRow = new JPanel(new BorderLayout(4, 0));
		searchRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		searchField.addActionListener(e -> triggerSearch());
		searchButton.addActionListener(e -> triggerSearch());
		searchRow.add(searchField, BorderLayout.CENTER);
		searchRow.add(searchButton, BorderLayout.EAST);

		myPlaylistsButton.addActionListener(e -> listener.onBrowsePlaylistsRequested());

		JPanel topControls = new JPanel(new BorderLayout(0, 4));
		topControls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		topControls.add(searchRow, BorderLayout.NORTH);
		topControls.add(myPlaylistsButton, BorderLayout.CENTER);

		rowsContainer.setLayout(new BoxLayout(rowsContainer, BoxLayout.Y_AXIS));
		rowsContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane scrollPane = new JScrollPane(rowsContainer);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);

		browseHintLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		browseHintLabel.setFont(browseHintLabel.getFont().deriveFont(10f));
		browseHintLabel.setText("Search or open My Playlists");
		rowsContainer.add(browseHintLabel);

		card.add(topControls, BorderLayout.NORTH);
		card.add(scrollPane, BorderLayout.CENTER);
		return card;
	}

	private void triggerSearch()
	{
		String query = searchField.getText() == null ? "" : searchField.getText().trim();
		if (!query.isEmpty())
		{
			listener.onSearchRequested(query);
		}
	}

	void showDisconnected()
	{
		removeAll();
		add(statusLabel, BorderLayout.NORTH);
		add(disconnectedPanel, BorderLayout.CENTER);
		connectButton.setEnabled(true);
		connectButton.setText("Connect to Spotify");
		revalidate();
		repaint();
	}

	void showConnecting()
	{
		statusLabel.setText("Connecting to Spotify…");
		connectButton.setEnabled(false);
		connectButton.setText("Connecting…");
	}

	void showConnected()
	{
		removeAll();
		add(statusLabel, BorderLayout.NORTH);
		add(connectedPanel, BorderLayout.CENTER);
		statusLabel.setText(" ");
		cardLayout.show(cardsPanel, CARD_NOW_PLAYING);
		revalidate();
		repaint();
	}

	void showStatusMessage(String message)
	{
		statusLabel.setText(message);
	}

	void updatePlaybackState(SpotifyPlaybackState state)
	{
		currentlyPlaying = state.isPlaying;
		playPauseButton.setText(state.isPlaying ? "⏸" : "▶");

		trackLabel.setText(state.trackName == null || state.trackName.isEmpty() ? "Nothing playing" : state.trackName);
		artistLabel.setText(state.artistName == null ? "" : state.artistName);
		progressLabel.setText(formatMs(state.progressMs) + " / " + formatMs(state.durationMs));

		suppressVolumeEvent = true;
		volumeSlider.setValue(state.volumePercent);
		suppressVolumeEvent = false;

		if (state.albumArtUrl != null && !state.albumArtUrl.equals(lastAlbumArtUrl))
		{
			lastAlbumArtUrl = state.albumArtUrl;
			albumArtLabel.setIcon(null);
		}
		else if (state.albumArtUrl == null)
		{
			lastAlbumArtUrl = null;
			albumArtLabel.setIcon(null);
		}

		statusLabel.setText(" ");
	}

	/**
	 * Called once album art bytes for the current lastAlbumArtUrl have been
	 * downloaded (see SpotifyApiClient.fetchImage). urlItWasFetchedFor guards
	 * against a slow, now-stale fetch clobbering newer art.
	 */
	void setAlbumArt(String urlItWasFetchedFor, BufferedImage image)
	{
		if (!urlItWasFetchedFor.equals(lastAlbumArtUrl))
		{
			return;
		}
		Image scaled = image.getScaledInstance(ART_SIZE, ART_SIZE, Image.SCALE_SMOOTH);
		albumArtLabel.setIcon(new ImageIcon(scaled));
	}

	void showBrowsePlaylists(List<SpotifyPlaylist> playlists)
	{
		lastPlaylists = playlists;
		rowsContainer.removeAll();
		if (playlists.isEmpty())
		{
			addHintRow("No playlists found");
		}
		for (SpotifyPlaylist playlist : playlists)
		{
			addRow(playlist.name, playlist.trackCount + " tracks", () -> listener.onPlaylistOpened(playlist));
		}
		refreshRows();
	}

	void showPlaylistTracks(SpotifyPlaylist playlist, List<SpotifyTrack> tracks)
	{
		rowsContainer.removeAll();
		addRow("◀ Back to Playlists", null, () ->
		{
			if (lastPlaylists != null)
			{
				showBrowsePlaylists(lastPlaylists);
			}
		});
		if (tracks.isEmpty())
		{
			addHintRow("No tracks found");
		}
		for (SpotifyTrack track : tracks)
		{
			addRow(track.name, track.artistName, () -> listener.onTrackSelected(track.uri, playlist.uri));
		}
		refreshRows();
	}

	void showSearchResults(List<SpotifyTrack> tracks)
	{
		rowsContainer.removeAll();
		if (tracks.isEmpty())
		{
			addHintRow("No results found");
		}
		for (SpotifyTrack track : tracks)
		{
			addRow(track.name, track.artistName, () -> listener.onTrackSelected(track.uri, null));
		}
		refreshRows();
	}

	private void addHintRow(String text)
	{
		JLabel hint = new JLabel(text, SwingConstants.CENTER);
		hint.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		hint.setFont(hint.getFont().deriveFont(10f));
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		rowsContainer.add(hint);
	}

	private void addRow(String title, String subtitle, Runnable onClick)
	{
		String html = "<html><body style='width: 140px'>"
			+ "<div>" + escapeHtml(title) + "</div>"
			+ (subtitle != null && !subtitle.isEmpty()
				? "<div style='color:#a5a5a5;font-size:9px'>" + escapeHtml(subtitle) + "</div>" : "")
			+ "</body></html>";

		JButton row = new JButton(html);
		row.setHorizontalAlignment(SwingConstants.LEFT);
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setForeground(ColorScheme.TEXT_COLOR);
		row.setFocusPainted(false);
		row.setBorderPainted(false);
		row.setOpaque(true);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
		row.addActionListener(e -> onClick.run());

		rowsContainer.add(row);
		rowsContainer.add(javax.swing.Box.createVerticalStrut(2));
	}

	private void refreshRows()
	{
		rowsContainer.revalidate();
		rowsContainer.repaint();
	}

	private static String escapeHtml(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String formatMs(long ms)
	{
		long totalSeconds = ms / 1000;
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;
		return String.format("%d:%02d", minutes, seconds);
	}
}

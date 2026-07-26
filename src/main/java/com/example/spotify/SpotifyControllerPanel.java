package com.example.spotify;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
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

	private String lastAlbumArtUrl;
	private boolean currentlyPlaying;
	private boolean suppressVolumeEvent;

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

		albumArtLabel.setHorizontalAlignment(SwingConstants.CENTER);
		albumArtLabel.setPreferredSize(new Dimension(ART_SIZE, ART_SIZE));

		JPanel infoPanel = new JPanel();
		infoPanel.setLayout(new BorderLayout());
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

		disconnectButton.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		disconnectButton.addActionListener(e -> listener.onDisconnectClicked());

		connectedPanel.add(infoPanel, BorderLayout.NORTH);
		connectedPanel.add(bottomPanel, BorderLayout.CENTER);
		connectedPanel.add(disconnectButton, BorderLayout.SOUTH);
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

	private static String formatMs(long ms)
	{
		long totalSeconds = ms / 1000;
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;
		return String.format("%d:%02d", minutes, seconds);
	}
}

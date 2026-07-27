package com.example.spotify;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import okhttp3.OkHttpClient;

/**
 * WHAT THIS FILE IS — sidebar panel for controlling Spotify playback from
 * inside the client (no alt-tab, no keyboard shortcuts). Structurally like
 * bloodmoonrises (ClientToolbar + PluginPanel, no game event handling), but
 * with a background poll loop and network calls instead of static content.
 * All Spotify logic is split out: SpotifyAuthManager (PKCE OAuth + tokens),
 * SpotifyApiClient (playback endpoints), SpotifyControllerPanel (pure view).
 */
@Slf4j
@PluginDescriptor(
	name = "<html><font color=#ff0000>[D] Spotify Controller",
	description = "Control Spotify playback from the RuneLite sidebar",
	tags = {"spotify", "music", "panel"},
	enabledByDefault = false
)
public class SpotifyControllerPlugin extends Plugin
{
	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private SpotifyControllerConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService scheduledExecutorService;

	private SpotifyAuthManager authManager;
	private SpotifyApiClient apiClient;
	private SpotifyControllerPanel panel;
	private SpotifyMiniPlayerWindow miniPlayerWindow;
	private NavigationButton navButton;

	private ScheduledFuture<?> pollTask;
	private final AtomicBoolean pollInFlight = new AtomicBoolean(false);

	@Provides
	SpotifyControllerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SpotifyControllerConfig.class);
	}

	@Override
	protected void startUp()
	{
		authManager = new SpotifyAuthManager(okHttpClient, gson, configManager, config);
		apiClient = new SpotifyApiClient(okHttpClient, gson, authManager);
		panel = new SpotifyControllerPanel(new PanelListener());
		miniPlayerWindow = new SpotifyMiniPlayerWindow(new MiniPlayerListener());

		navButton = NavigationButton.builder()
			.tooltip("Spotify Controller")
			.icon(createIcon())
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		if (authManager.hasStoredSession())
		{
			panel.showConnecting();
			attemptSilentReconnect();
		}
		else
		{
			panel.showDisconnected();
		}
	}

	@Override
	protected void shutDown()
	{
		stopPolling();
		clientToolbar.removeNavigation(navButton);
		authManager.shutDown();
		if (miniPlayerWindow != null)
		{
			miniPlayerWindow.dispose();
			miniPlayerWindow = null;
		}
		panel = null;
		navButton = null;
	}

	private void toggleMiniPlayer()
	{
		boolean nowOpen = !miniPlayerWindow.isOpen();
		if (nowOpen)
		{
			miniPlayerWindow.open(configManager.getConfiguration("spotifycontroller", "backgroundImagePath"));
		}
		else
		{
			miniPlayerWindow.close();
		}
		panel.setMiniPlayerOpen(nowOpen);
	}

	private void attemptSilentReconnect()
	{
		authManager.ensureValidAccessToken(
			token -> onConnected(),
			error -> SwingUtilities.invokeLater(() ->
			{
				panel.showDisconnected();
				panel.showStatusMessage("Spotify session expired — please reconnect");
			})
		);
	}

	private void onConnected()
	{
		SwingUtilities.invokeLater(() -> panel.showConnected());
		startPolling();
	}

	private void startPolling()
	{
		stopPolling();
		pollTask = scheduledExecutorService.scheduleWithFixedDelay(
			this::pollTick, 0, Math.max(1, config.pollIntervalSeconds()), TimeUnit.SECONDS);
	}

	private void stopPolling()
	{
		if (pollTask != null)
		{
			pollTask.cancel(false);
			pollTask = null;
		}
		pollInFlight.set(false);
	}

	private void pollTick()
	{
		if (!pollInFlight.compareAndSet(false, true))
		{
			return;
		}

		apiClient.getPlaybackState(
			state -> SwingUtilities.invokeLater(() ->
			{
				panel.updatePlaybackState(state);
				maybeFetchAlbumArt(state.albumArtUrl);
				if (miniPlayerWindow.isOpen())
				{
					miniPlayerWindow.updatePlaybackState(state);
					miniPlayerWindow.keepOnTop();
				}
			}),
			result ->
			{
				pollInFlight.set(false);
				handlePollResult(result);
			}
		);
	}

	private void handlePollResult(SpotifyApiClient.Result result)
	{
		switch (result)
		{
			case SUCCESS:
				return;
			case NO_ACTIVE_DEVICE:
				SwingUtilities.invokeLater(() -> panel.showStatusMessage("No active device — open Spotify and start playback"));
				return;
			case FORBIDDEN:
				// 403 covers two different real causes: Premium being required for
				// playback control, or the connected session missing a scope/permission
				// for this specific resource (e.g. playlist access before a reconnect).
				SwingUtilities.invokeLater(() -> panel.showStatusMessage(
					"Spotify denied that request — Premium is required for playback control, "
						+ "or try Disconnect/Connect again to refresh permissions"));
				return;
			case RATE_LIMITED:
				SwingUtilities.invokeLater(() -> panel.showStatusMessage("Rate limited by Spotify — backing off"));
				return;
			case NETWORK_ERROR:
				SwingUtilities.invokeLater(() -> panel.showStatusMessage("Connection issue — retrying…"));
				return;
			case AUTH_EXPIRED:
				if (!authManager.hasStoredSession())
				{
					// SpotifyAuthManager already forgot the (invalid) refresh token —
					// the session is dead server-side, not just a transient hiccup.
					stopPolling();
					SwingUtilities.invokeLater(() ->
					{
						panel.showDisconnected();
						panel.showStatusMessage("Spotify session expired — please reconnect");
					});
				}
				else
				{
					SwingUtilities.invokeLater(() -> panel.showStatusMessage("Reconnecting to Spotify…"));
				}
				return;
		}
	}

	private String lastFetchedAlbumArtUrl;

	private void maybeFetchAlbumArt(String albumArtUrl)
	{
		if (albumArtUrl == null || albumArtUrl.equals(lastFetchedAlbumArtUrl))
		{
			return;
		}
		lastFetchedAlbumArtUrl = albumArtUrl;
		apiClient.fetchImage(albumArtUrl,
			bytes ->
			{
				try
				{
					BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
					if (image != null)
					{
						SwingUtilities.invokeLater(() -> panel.setAlbumArt(albumArtUrl, image));
					}
				}
				catch (Exception e)
				{
					log.debug("Failed to decode Spotify album art", e);
				}
			},
			() -> log.debug("Failed to download Spotify album art"));
	}

	private void triggerImmediatePollIfIdle()
	{
		if (pollTask != null)
		{
			scheduledExecutorService.execute(this::pollTick);
		}
	}

	private BufferedImage createIcon()
	{
		BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = icon.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// Spotify-green filled circle with a small white "play" triangle
		g.setColor(new Color(30, 185, 84));
		g.fillOval(0, 0, 16, 16);
		g.setColor(Color.WHITE);
		int[] xs = {6, 6, 12};
		int[] ys = {4, 12, 8};
		g.fillPolygon(xs, ys, 3);
		g.dispose();
		return icon;
	}

	private class PanelListener implements SpotifyControllerPanel.Listener
	{
		@Override
		public void onConnectClicked()
		{
			panel.showConnecting();
			authManager.startAuthFlow(
				() -> onConnected(),
				error -> SwingUtilities.invokeLater(() ->
				{
					panel.showDisconnected();
					panel.showStatusMessage(error);
				})
			);
		}

		@Override
		public void onDisconnectClicked()
		{
			stopPolling();
			authManager.disconnect();
			SwingUtilities.invokeLater(() -> panel.showDisconnected());
		}

		@Override
		public void onPlayPauseClicked(boolean currentlyPlaying)
		{
			if (currentlyPlaying)
			{
				apiClient.pause(result -> onControlResult(result));
			}
			else
			{
				apiClient.play(result -> onControlResult(result));
			}
		}

		@Override
		public void onPreviousClicked()
		{
			apiClient.previous(result -> onControlResult(result));
		}

		@Override
		public void onNextClicked()
		{
			apiClient.next(result -> onControlResult(result));
		}

		@Override
		public void onVolumeChanged(int volumePercent)
		{
			apiClient.setVolume(volumePercent, result -> onControlResult(result));
		}

		@Override
		public void onBrowsePlaylistsRequested()
		{
			SwingUtilities.invokeLater(() -> panel.showStatusMessage("Loading playlists…"));
			apiClient.getPlaylists(
				playlists -> SwingUtilities.invokeLater(() ->
				{
					panel.showStatusMessage(" ");
					panel.showBrowsePlaylists(playlists);
				}),
				this::onBrowseResult
			);
		}

		@Override
		public void onPlaylistOpened(SpotifyPlaylist playlist)
		{
			SwingUtilities.invokeLater(() -> panel.showStatusMessage("Loading tracks…"));
			apiClient.getPlaylistTracks(playlist.id,
				tracks -> SwingUtilities.invokeLater(() ->
				{
					panel.showStatusMessage(" ");
					panel.showPlaylistTracks(playlist, tracks);
				}),
				this::onBrowseResult
			);
		}

		@Override
		public void onTrackSelected(String trackUri, String playlistContextUri)
		{
			apiClient.playTrackInPlaylist(playlistContextUri, trackUri, result -> onControlResult(result));
		}

		@Override
		public void onToggleMiniPlayerClicked()
		{
			toggleMiniPlayer();
		}

		private void onBrowseResult(SpotifyApiClient.Result result)
		{
			if (result != SpotifyApiClient.Result.SUCCESS)
			{
				handlePollResult(result);
			}
		}
	}

	/**
	 * Shared by both PanelListener and MiniPlayerListener — same transport
	 * buttons calling the same apiClient methods, just from two different
	 * pieces of UI, so the result handling only needs to live once.
	 */
	private void onControlResult(SpotifyApiClient.Result result)
	{
		if (result == SpotifyApiClient.Result.SUCCESS)
		{
			// Refresh state promptly instead of waiting for the next poll tick.
			triggerImmediatePollIfIdle();
		}
		else
		{
			handlePollResult(result);
		}
	}

	private class MiniPlayerListener implements SpotifyMiniPlayerWindow.Listener
	{
		@Override
		public void onPlayPauseClicked(boolean currentlyPlaying)
		{
			if (currentlyPlaying)
			{
				apiClient.pause(SpotifyControllerPlugin.this::onControlResult);
			}
			else
			{
				apiClient.play(SpotifyControllerPlugin.this::onControlResult);
			}
		}

		@Override
		public void onPreviousClicked()
		{
			apiClient.previous(SpotifyControllerPlugin.this::onControlResult);
		}

		@Override
		public void onNextClicked()
		{
			apiClient.next(SpotifyControllerPlugin.this::onControlResult);
		}

		@Override
		public void onVolumeChanged(int volumePercent)
		{
			apiClient.setVolume(volumePercent, SpotifyControllerPlugin.this::onControlResult);
		}

		@Override
		public void onChooseBackgroundClicked()
		{
			JFileChooser chooser = new JFileChooser();
			chooser.setFileFilter(new FileNameExtensionFilter(
				"Images and GIFs", "png", "jpg", "jpeg", "gif", "bmp"));
			if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
			{
				String path = chooser.getSelectedFile().getAbsolutePath();
				configManager.setConfiguration("spotifycontroller", "backgroundImagePath", path);
				miniPlayerWindow.setBackgroundImage(path);
			}
		}

		@Override
		public void onResetBackgroundClicked()
		{
			configManager.unsetConfiguration("spotifycontroller", "backgroundImagePath");
			miniPlayerWindow.setBackgroundImage(null);
		}

		@Override
		public void onCloseClicked()
		{
			miniPlayerWindow.close();
			panel.setMiniPlayerOpen(false);
		}
	}
}

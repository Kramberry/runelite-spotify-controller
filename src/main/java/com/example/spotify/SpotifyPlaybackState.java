package com.example.spotify;

/**
 * Snapshot of GET /v1/me/player, trimmed to what the panel renders. Decouples
 * the panel from raw Gson/JSON navigation.
 */
class SpotifyPlaybackState
{
	final String trackName;
	final String artistName;
	final String albumArtUrl;
	final long progressMs;
	final long durationMs;
	final boolean isPlaying;
	final int volumePercent;

	SpotifyPlaybackState(String trackName, String artistName, String albumArtUrl,
		long progressMs, long durationMs, boolean isPlaying, int volumePercent)
	{
		this.trackName = trackName;
		this.artistName = artistName;
		this.albumArtUrl = albumArtUrl;
		this.progressMs = progressMs;
		this.durationMs = durationMs;
		this.isPlaying = isPlaying;
		this.volumePercent = volumePercent;
	}
}

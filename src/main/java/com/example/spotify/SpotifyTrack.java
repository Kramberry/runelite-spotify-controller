package com.example.spotify;

class SpotifyTrack
{
	final String id;
	final String name;
	final String artistName;
	final String uri;
	final long durationMs;
	final String albumArtUrl;

	SpotifyTrack(String id, String name, String artistName, String uri, long durationMs, String albumArtUrl)
	{
		this.id = id;
		this.name = name;
		this.artistName = artistName;
		this.uri = uri;
		this.durationMs = durationMs;
		this.albumArtUrl = albumArtUrl;
	}
}

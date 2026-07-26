package com.example.spotify;

class SpotifyPlaylist
{
	final String id;
	final String name;
	final String uri;
	final String imageUrl;
	final int trackCount;

	SpotifyPlaylist(String id, String name, String uri, String imageUrl, int trackCount)
	{
		this.id = id;
		this.name = name;
		this.uri = uri;
		this.imageUrl = imageUrl;
		this.trackCount = trackCount;
	}
}

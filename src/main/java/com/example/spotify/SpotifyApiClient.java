package com.example.spotify;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Thin async wrapper around the Spotify "player" endpoints. Every call first
 * asks SpotifyAuthManager for a valid access token (refreshing if needed),
 * then fires the actual request via OkHttp's enqueue() — never execute(),
 * since RuneLite's OkHttpClient rejects blocking calls from the client thread
 * or the Swing EDT and callers here may be either.
 */
@Slf4j
class SpotifyApiClient
{
	enum Result
	{
		SUCCESS,
		NO_ACTIVE_DEVICE,
		AUTH_EXPIRED,
		FORBIDDEN,
		RATE_LIMITED,
		NETWORK_ERROR
	}

	private static final String PLAYER_URL = "https://api.spotify.com/v1/me/player";
	private static final String PLAYLISTS_URL = "https://api.spotify.com/v1/me/playlists";
	private static final String PLAYLIST_URL = "https://api.spotify.com/v1/playlists/";
	private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final SpotifyAuthManager authManager;

	SpotifyApiClient(OkHttpClient httpClient, Gson gson, SpotifyAuthManager authManager)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.authManager = authManager;
	}

	void getPlaybackState(Consumer<SpotifyPlaybackState> onState, Consumer<Result> onResult)
	{
		withToken(onResult, token ->
		{
			Request request = new Request.Builder()
				.url(PLAYER_URL)
				.header("Authorization", "Bearer " + token)
				.get()
				.build();

			enqueue(request, onResult, response ->
			{
				if (response.code() == 204)
				{
					onResult.accept(Result.NO_ACTIVE_DEVICE);
					return;
				}
				try
				{
					String bodyString = response.body() != null ? response.body().string() : "";
					if (bodyString.isEmpty())
					{
						onResult.accept(Result.NO_ACTIVE_DEVICE);
						return;
					}
					JsonObject json = gson.fromJson(bodyString, JsonObject.class);
					onState.accept(parsePlaybackState(json));
					onResult.accept(Result.SUCCESS);
				}
				catch (Exception e)
				{
					log.warn("Failed to parse Spotify playback state", e);
					onResult.accept(Result.NETWORK_ERROR);
				}
			});
		});
	}

	void play(Consumer<Result> onResult)
	{
		putNoBody(PLAYER_URL + "/play", onResult);
	}

	void pause(Consumer<Result> onResult)
	{
		putNoBody(PLAYER_URL + "/pause", onResult);
	}

	void next(Consumer<Result> onResult)
	{
		postNoBody(PLAYER_URL + "/next", onResult);
	}

	void previous(Consumer<Result> onResult)
	{
		postNoBody(PLAYER_URL + "/previous", onResult);
	}

	void setVolume(int volumePercent, Consumer<Result> onResult)
	{
		HttpUrl url = HttpUrl.parse(PLAYER_URL + "/volume")
			.newBuilder()
			.addQueryParameter("volume_percent", String.valueOf(volumePercent))
			.build();
		putNoBody(url.toString(), onResult);
	}

	void getPlaylists(Consumer<List<SpotifyPlaylist>> onPlaylists, Consumer<Result> onResult)
	{
		String url = HttpUrl.parse(PLAYLISTS_URL).newBuilder()
			.addQueryParameter("limit", "50")
			.build()
			.toString();
		getJson(url, json -> onPlaylists.accept(parsePlaylists(json)), onResult);
	}

	/**
	 * Uses "Get Playlist" (GET /v1/playlists/{id}) rather than the dedicated
	 * .../tracks sub-resource — Spotify's docs flag that sub-resource as
	 * deprecated, and newly-registered apps got 403s calling it. The full
	 * playlist object embeds the same track list, just nested one level in.
	 */
	void getPlaylistTracks(String playlistId, Consumer<List<SpotifyTrack>> onTracks, Consumer<Result> onResult)
	{
		getJson(PLAYLIST_URL + playlistId, json -> onTracks.accept(parsePlaylistTracks(json)), onResult);
	}

	void playTrackInPlaylist(String playlistUri, String trackUri, Consumer<Result> onResult)
	{
		JsonObject body = new JsonObject();
		body.addProperty("context_uri", playlistUri);
		JsonObject offset = new JsonObject();
		offset.addProperty("uri", trackUri);
		body.add("offset", offset);
		putJson(PLAYER_URL + "/play", body, onResult);
	}

	/**
	 * Album art is served from Spotify's public image CDN — no auth header needed.
	 */
	void fetchImage(String url, Consumer<byte[]> onBytes, Runnable onError)
	{
		Request request = new Request.Builder().url(url).get().build();
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.run();
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						onError.run();
						return;
					}
					onBytes.accept(r.body().bytes());
				}
				catch (IOException e)
				{
					onError.run();
				}
			}
		});
	}

	private void putNoBody(String url, Consumer<Result> onResult)
	{
		withToken(onResult, token ->
		{
			Request request = new Request.Builder()
				.url(url)
				.header("Authorization", "Bearer " + token)
				.put(RequestBody.create(null, new byte[0]))
				.build();
			enqueue(request, onResult, response -> onResult.accept(Result.SUCCESS));
		});
	}

	private void postNoBody(String url, Consumer<Result> onResult)
	{
		withToken(onResult, token ->
		{
			Request request = new Request.Builder()
				.url(url)
				.header("Authorization", "Bearer " + token)
				.post(RequestBody.create(null, new byte[0]))
				.build();
			enqueue(request, onResult, response -> onResult.accept(Result.SUCCESS));
		});
	}

	private void getJson(String url, Consumer<JsonObject> onJson, Consumer<Result> onResult)
	{
		withToken(onResult, token ->
		{
			Request request = new Request.Builder()
				.url(url)
				.header("Authorization", "Bearer " + token)
				.get()
				.build();
			enqueue(request, onResult, response ->
			{
				String bodyString = response.body() != null ? response.body().string() : "";
				try
				{
					onJson.accept(gson.fromJson(bodyString, JsonObject.class));
				}
				catch (Exception e)
				{
					// Distinguishes a real client-side parsing bug (logged with the
					// body that broke it) from an actual network/HTTP failure, both
					// of which used to collapse into the same generic message.
					log.warn("Failed to parse Spotify response from {}: {}", request.url(), bodyString, e);
					onResult.accept(Result.NETWORK_ERROR);
				}
			});
		});
	}

	private void putJson(String url, JsonObject body, Consumer<Result> onResult)
	{
		withToken(onResult, token ->
		{
			Request request = new Request.Builder()
				.url(url)
				.header("Authorization", "Bearer " + token)
				.put(RequestBody.create(JSON_MEDIA_TYPE, gson.toJson(body)))
				.build();
			enqueue(request, onResult, response -> onResult.accept(Result.SUCCESS));
		});
	}

	private void withToken(Consumer<Result> onResult, Consumer<String> withToken)
	{
		authManager.ensureValidAccessToken(
			withToken::accept,
			error -> onResult.accept(Result.AUTH_EXPIRED)
		);
	}

	private interface SuccessHandler
	{
		void handle(Response response) throws IOException;
	}

	private void enqueue(Request request, Consumer<Result> onResult, SuccessHandler onSuccess)
	{
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				// RuneLite's default log level is INFO, so this must be warn (not
				// debug) to actually land in client.log for later diagnosis.
				log.warn("Spotify request failed: {}", call.request().url(), e);
				onResult.accept(Result.NETWORK_ERROR);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.code() == 404)
					{
						onResult.accept(Result.NO_ACTIVE_DEVICE);
						return;
					}
					if (r.code() == 401)
					{
						onResult.accept(Result.AUTH_EXPIRED);
						return;
					}
					if (r.code() == 403)
					{
						// 403 here doesn't always mean "Premium required" — Spotify also
						// returns it for missing scopes/permissions on a specific
						// resource. Log the real reason so it's diagnosable.
						try
						{
							log.warn("Spotify 403 on {}: {}", r.request().url(),
								r.body() != null ? r.body().string() : "(no body)");
						}
						catch (IOException ignored)
						{
							// best-effort diagnostics only
						}
						onResult.accept(Result.FORBIDDEN);
						return;
					}
					if (r.code() == 429)
					{
						onResult.accept(Result.RATE_LIMITED);
						return;
					}
					if (!r.isSuccessful())
					{
						try
						{
							log.warn("Spotify {} on {}: {}", r.code(), r.request().url(),
								r.body() != null ? r.body().string() : "(no body)");
						}
						catch (IOException ignored)
						{
							// best-effort diagnostics only
						}
						onResult.accept(Result.NETWORK_ERROR);
						return;
					}
					onSuccess.handle(r);
				}
				catch (IOException e)
				{
					log.warn("Spotify request failed while reading response", e);
					onResult.accept(Result.NETWORK_ERROR);
				}
			}
		});
	}

	private static SpotifyPlaybackState parsePlaybackState(JsonObject json)
	{
		JsonObject itemJson = json.has("item") && !json.get("item").isJsonNull() ? json.getAsJsonObject("item") : null;
		SpotifyTrack track = parseTrackObject(itemJson);

		long progressMs = json.has("progress_ms") && !json.get("progress_ms").isJsonNull()
			? json.get("progress_ms").getAsLong() : 0;
		boolean isPlaying = json.has("is_playing") && json.get("is_playing").getAsBoolean();
		int volumePercent = 100;
		if (json.has("device") && !json.get("device").isJsonNull())
		{
			JsonObject device = json.getAsJsonObject("device");
			if (device.has("volume_percent") && !device.get("volume_percent").isJsonNull())
			{
				volumePercent = device.get("volume_percent").getAsInt();
			}
		}

		return new SpotifyPlaybackState(
			track != null ? track.name : "",
			track != null ? track.artistName : "",
			track != null ? track.albumArtUrl : null,
			progressMs,
			track != null ? track.durationMs : 0,
			isPlaying,
			volumePercent);
	}

	/**
	 * Shared by the now-playing item, playlist-tracks entries, and search
	 * results — all three are the same Spotify "track object" shape.
	 */
	private static SpotifyTrack parseTrackObject(JsonObject item)
	{
		if (item == null)
		{
			return null;
		}

		String id = item.has("id") && !item.get("id").isJsonNull() ? item.get("id").getAsString() : "";
		String name = item.has("name") && !item.get("name").isJsonNull() ? item.get("name").getAsString() : "";
		String uri = item.has("uri") && !item.get("uri").isJsonNull() ? item.get("uri").getAsString() : "";
		long durationMs = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0;

		String artistName = "";
		if (item.has("artists") && item.get("artists").isJsonArray())
		{
			JsonArray artists = item.getAsJsonArray("artists");
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < artists.size(); i++)
			{
				if (!artists.get(i).isJsonObject())
				{
					continue;
				}
				JsonObject artist = artists.get(i).getAsJsonObject();
				if (!artist.has("name") || artist.get("name").isJsonNull())
				{
					continue;
				}
				if (sb.length() > 0)
				{
					sb.append(", ");
				}
				sb.append(artist.get("name").getAsString());
			}
			artistName = sb.toString();
		}

		String albumArtUrl = null;
		if (item.has("album") && item.get("album").isJsonObject())
		{
			JsonObject album = item.getAsJsonObject("album");
			if (album.has("images") && album.get("images").isJsonArray())
			{
				JsonArray images = album.getAsJsonArray("images");
				if (images.size() > 0 && images.get(0).isJsonObject())
				{
					albumArtUrl = images.get(0).getAsJsonObject().get("url").getAsString();
				}
			}
		}

		return new SpotifyTrack(id, name, artistName, uri, durationMs, albumArtUrl);
	}

	private static List<SpotifyPlaylist> parsePlaylists(JsonObject json)
	{
		List<SpotifyPlaylist> result = new ArrayList<>();
		if (!json.has("items") || !json.get("items").isJsonArray())
		{
			return result;
		}

		for (JsonElement e : json.getAsJsonArray("items"))
		{
			if (!e.isJsonObject())
			{
				continue;
			}
			JsonObject p = e.getAsJsonObject();
			String id = p.has("id") && !p.get("id").isJsonNull() ? p.get("id").getAsString() : "";
			String name = p.has("name") ? p.get("name").getAsString() : "";
			String uri = p.has("uri") && !p.get("uri").isJsonNull() ? p.get("uri").getAsString() : "";

			String imageUrl = null;
			if (p.has("images") && p.get("images").isJsonArray())
			{
				JsonArray images = p.getAsJsonArray("images");
				if (images.size() > 0 && images.get(0).isJsonObject())
				{
					imageUrl = images.get(0).getAsJsonObject().get("url").getAsString();
				}
			}

			result.add(new SpotifyPlaylist(id, name, uri, imageUrl, extractTrackCount(p)));
		}
		return result;
	}

	/**
	 * Spotify's own docs disagreed across pages on whether a playlist's track
	 * collection is keyed "tracks" (the long-standing field) or "items" (a
	 * claimed rename we couldn't independently confirm) — check both.
	 */
	private static JsonObject extractTracksContainer(JsonObject playlist)
	{
		if (playlist.has("tracks") && playlist.get("tracks").isJsonObject())
		{
			return playlist.getAsJsonObject("tracks");
		}
		if (playlist.has("items") && playlist.get("items").isJsonObject())
		{
			return playlist.getAsJsonObject("items");
		}
		return null;
	}

	private static int extractTrackCount(JsonObject playlist)
	{
		JsonObject container = extractTracksContainer(playlist);
		return container != null && container.has("total") ? container.get("total").getAsInt() : 0;
	}

	/**
	 * json here is a full Playlist object (from "Get Playlist") — its track
	 * list is nested one level in, under the same ambiguous "tracks"/"items"
	 * key handled by extractTracksContainer above.
	 */
	private static List<SpotifyTrack> parsePlaylistTracks(JsonObject json)
	{
		List<SpotifyTrack> result = new ArrayList<>();
		JsonObject container = extractTracksContainer(json);
		if (container == null || !container.has("items") || !container.get("items").isJsonArray())
		{
			return result;
		}

		for (JsonElement e : container.getAsJsonArray("items"))
		{
			if (!e.isJsonObject())
			{
				continue;
			}
			JsonObject entry = e.getAsJsonObject();

			// Same "track" vs "item" ambiguity as extractTrackCount above.
			JsonObject trackJson = null;
			if (entry.has("track") && entry.get("track").isJsonObject())
			{
				trackJson = entry.getAsJsonObject("track");
			}
			else if (entry.has("item") && entry.get("item").isJsonObject())
			{
				trackJson = entry.getAsJsonObject("item");
			}

			SpotifyTrack track = parseTrackObject(trackJson);
			if (track != null && !track.uri.isEmpty())
			{
				result.add(track);
			}
		}
		return result;
	}
}

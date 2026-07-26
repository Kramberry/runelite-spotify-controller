package com.example.spotify;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
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
		JsonObject item = json.has("item") && !json.get("item").isJsonNull() ? json.getAsJsonObject("item") : null;

		String trackName = "";
		String artistName = "";
		String albumArtUrl = null;
		long durationMs = 0;

		if (item != null)
		{
			trackName = item.has("name") ? item.get("name").getAsString() : "";
			durationMs = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0;

			if (item.has("artists") && item.get("artists").isJsonArray())
			{
				JsonArray artists = item.getAsJsonArray("artists");
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < artists.size(); i++)
				{
					if (i > 0)
					{
						sb.append(", ");
					}
					sb.append(artists.get(i).getAsJsonObject().get("name").getAsString());
				}
				artistName = sb.toString();
			}

			if (item.has("album") && item.getAsJsonObject("album").has("images"))
			{
				JsonArray images = item.getAsJsonObject("album").getAsJsonArray("images");
				if (images.size() > 0)
				{
					albumArtUrl = images.get(0).getAsJsonObject().get("url").getAsString();
				}
			}
		}

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

		return new SpotifyPlaybackState(trackName, artistName, albumArtUrl, progressMs, durationMs, isPlaying, volumePercent);
	}
}

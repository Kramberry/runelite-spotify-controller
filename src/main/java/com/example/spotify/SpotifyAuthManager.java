package com.example.spotify;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Owns the Spotify Authorization Code + PKCE flow (no client secret needed),
 * the loopback callback listener, and the current access/refresh token pair.
 * Every network call goes through OkHttp's async enqueue() — RuneLite's
 * provided OkHttpClient throws if a request executes on the client thread or
 * the Swing EDT, and this class's callers may be either.
 */
@Slf4j
class SpotifyAuthManager
{
	private static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
	private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
	private static final int CALLBACK_PORT = 8888;
	private static final String REDIRECT_URI = "http://127.0.0.1:" + CALLBACK_PORT + "/callback";
	private static final String SCOPES =
		"user-read-playback-state user-modify-playback-state user-read-currently-playing";
	private static final long AUTH_TIMEOUT_MS = 120_000;
	private static final long EXPIRY_SAFETY_MARGIN_MS = 60_000;

	private static final String CONFIG_GROUP = "spotifycontroller";
	private static final String REFRESH_TOKEN_KEY = "refreshToken";

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ConfigManager configManager;
	private final SpotifyControllerConfig config;

	private volatile String accessToken;
	private volatile long accessTokenExpiresAt;
	private volatile String refreshToken;

	private HttpServer callbackServer;
	private ExecutorService callbackServerExecutor;

	SpotifyAuthManager(OkHttpClient httpClient, Gson gson, ConfigManager configManager, SpotifyControllerConfig config)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.configManager = configManager;
		this.config = config;

		String stored = configManager.getConfiguration(CONFIG_GROUP, REFRESH_TOKEN_KEY);
		this.refreshToken = (stored == null || stored.isEmpty()) ? null : stored;
	}

	/**
	 * True if we have a refresh token from a previous session. Doesn't guarantee
	 * it's still valid — that's only known once a refresh actually succeeds.
	 */
	boolean hasStoredSession()
	{
		return refreshToken != null;
	}

	/**
	 * Forgets local credentials. Spotify's PKCE flow has no revoke endpoint, so
	 * this only ends the session on this machine, not server-side.
	 */
	void disconnect()
	{
		accessToken = null;
		accessTokenExpiresAt = 0;
		refreshToken = null;
		configManager.unsetConfiguration(CONFIG_GROUP, REFRESH_TOKEN_KEY);
		stopCallbackServer();
	}

	/**
	 * Kicks off the browser-based PKCE authorization flow. onSuccess/onError are
	 * invoked from the callback HTTP server's own executor thread or an OkHttp
	 * callback thread — never the client thread or EDT — so callers must hop to
	 * the right thread themselves (SwingUtilities.invokeLater for UI work).
	 */
	void startAuthFlow(Runnable onSuccess, Consumer<String> onError)
	{
		String clientId = config.clientId();
		if (clientId == null || clientId.trim().isEmpty())
		{
			onError.accept("Set your Spotify Client ID in the plugin config first");
			return;
		}

		stopCallbackServer();

		String codeVerifier = randomUrlSafeString(64);
		String codeChallenge = pkceChallenge(codeVerifier);
		String state = randomUrlSafeString(16);

		try
		{
			callbackServerExecutor = Executors.newSingleThreadExecutor();
			callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", CALLBACK_PORT), 0);
			callbackServer.setExecutor(callbackServerExecutor);
			callbackServer.createContext("/callback", exchange ->
				handleCallback(exchange, state, codeVerifier, clientId, onSuccess, onError));
			callbackServer.start();
		}
		catch (BindException e)
		{
			stopCallbackServer();
			onError.accept("Port " + CALLBACK_PORT + " is already in use — close whatever is using it and try again");
			return;
		}
		catch (IOException e)
		{
			stopCallbackServer();
			onError.accept("Couldn't start local callback listener: " + e.getMessage());
			return;
		}

		// If the user never completes (or abandons) the browser consent screen,
		// don't leave the loopback listener bound forever.
		Thread timeoutThread = new Thread(() ->
		{
			try
			{
				Thread.sleep(AUTH_TIMEOUT_MS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return;
			}
			if (callbackServer != null)
			{
				stopCallbackServer();
				onError.accept("Timed out waiting for Spotify authorization");
			}
		}, "spotify-auth-timeout");
		timeoutThread.setDaemon(true);
		timeoutThread.start();

		String authorizeUrl = AUTHORIZE_URL
			+ "?response_type=code"
			+ "&client_id=" + urlEncode(clientId)
			+ "&redirect_uri=" + urlEncode(REDIRECT_URI)
			+ "&code_challenge_method=S256"
			+ "&code_challenge=" + urlEncode(codeChallenge)
			+ "&scope=" + urlEncode(SCOPES)
			+ "&state=" + urlEncode(state);

		try
		{
			Desktop.getDesktop().browse(URI.create(authorizeUrl));
		}
		catch (IOException e)
		{
			stopCallbackServer();
			onError.accept("Couldn't open the browser for Spotify login: " + e.getMessage());
		}
	}

	private void handleCallback(HttpExchange exchange, String expectedState, String codeVerifier, String clientId,
		Runnable onSuccess, Consumer<String> onError)
	{
		try
		{
			Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
			respondHtml(exchange, params.containsKey("error")
				? "Spotify authorization failed. You can close this window."
				: "Spotify connected. You can close this window and return to RuneLite.");

			String error = params.get("error");
			if (error != null)
			{
				onError.accept("Spotify authorization was denied (" + error + ")");
				return;
			}

			String returnedState = params.get("state");
			if (returnedState == null || !returnedState.equals(expectedState))
			{
				onError.accept("Spotify authorization failed a security check (state mismatch) — try again");
				return;
			}

			String code = params.get("code");
			if (code == null)
			{
				onError.accept("Spotify didn't return an authorization code");
				return;
			}

			exchangeCodeForToken(code, codeVerifier, clientId, onSuccess, onError);
		}
		catch (Exception e)
		{
			log.warn("Spotify callback handling failed", e);
			onError.accept("Unexpected error handling Spotify's response");
		}
		finally
		{
			stopCallbackServer();
		}
	}

	private void exchangeCodeForToken(String code, String codeVerifier, String clientId,
		Runnable onSuccess, Consumer<String> onError)
	{
		FormBody body = new FormBody.Builder()
			.add("grant_type", "authorization_code")
			.add("code", code)
			.add("redirect_uri", REDIRECT_URI)
			.add("client_id", clientId)
			.add("code_verifier", codeVerifier)
			.build();

		Request request = new Request.Builder().url(TOKEN_URL).post(body).build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.accept("Network error contacting Spotify: " + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						onError.accept("Spotify rejected the login (HTTP " + r.code() + ")");
						return;
					}
					applyTokenResponse(r);
					onSuccess.run();
				}
				catch (Exception e)
				{
					log.warn("Failed to parse Spotify token response", e);
					onError.accept("Couldn't read Spotify's response");
				}
			}
		});
	}

	/**
	 * Ensures a non-expired access token is available, refreshing first if
	 * needed, then hands it to onToken. Always async — never blocks the caller.
	 */
	void ensureValidAccessToken(Consumer<String> onToken, Consumer<String> onError)
	{
		if (accessToken != null && System.currentTimeMillis() < accessTokenExpiresAt - EXPIRY_SAFETY_MARGIN_MS)
		{
			onToken.accept(accessToken);
			return;
		}

		String currentRefreshToken = refreshToken;
		if (currentRefreshToken == null)
		{
			onError.accept("Not connected to Spotify");
			return;
		}

		String clientId = config.clientId();
		FormBody body = new FormBody.Builder()
			.add("grant_type", "refresh_token")
			.add("refresh_token", currentRefreshToken)
			.add("client_id", clientId)
			.build();

		Request request = new Request.Builder().url(TOKEN_URL).post(body).build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.accept("Network error refreshing Spotify session: " + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						// A rejected refresh token means the session is dead server-side
						// (revoked or expired) — forget it locally so the panel prompts
						// the user to reconnect rather than retrying forever.
						if (r.code() == 400 || r.code() == 401)
						{
							disconnect();
						}
						onError.accept("Spotify session expired — please reconnect (HTTP " + r.code() + ")");
						return;
					}
					applyTokenResponse(r);
					onToken.accept(accessToken);
				}
				catch (Exception e)
				{
					log.warn("Failed to parse Spotify refresh response", e);
					onError.accept("Couldn't read Spotify's response");
				}
			}
		});
	}

	private void applyTokenResponse(Response response) throws IOException
	{
		String bodyString = response.body() != null ? response.body().string() : "";
		JsonObject json = gson.fromJson(bodyString, JsonObject.class);

		accessToken = json.get("access_token").getAsString();
		int expiresInSeconds = json.has("expires_in") ? json.get("expires_in").getAsInt() : 3600;
		accessTokenExpiresAt = System.currentTimeMillis() + expiresInSeconds * 1000L;

		// Spotify's PKCE refresh can rotate the refresh token — persist the new
		// one whenever it sends one, otherwise keep using the existing one.
		if (json.has("refresh_token"))
		{
			refreshToken = json.get("refresh_token").getAsString();
			configManager.setConfiguration(CONFIG_GROUP, REFRESH_TOKEN_KEY, refreshToken);
		}
	}

	void shutDown()
	{
		stopCallbackServer();
	}

	private void stopCallbackServer()
	{
		if (callbackServer != null)
		{
			callbackServer.stop(0);
			callbackServer = null;
		}
		if (callbackServerExecutor != null)
		{
			callbackServerExecutor.shutdownNow();
			callbackServerExecutor = null;
		}
	}

	private static void respondHtml(HttpExchange exchange, String message) throws IOException
	{
		byte[] bytes = ("<html><body style=\"font-family:sans-serif\">" + message + "</body></html>")
			.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = exchange.getResponseBody())
		{
			os.write(bytes);
		}
	}

	private static Map<String, String> parseQuery(String query)
	{
		Map<String, String> result = new java.util.HashMap<>();
		if (query == null)
		{
			return result;
		}
		for (String pair : query.split("&"))
		{
			List<String> parts = List.of(pair.split("=", 2));
			String key = decode(parts.get(0));
			String value = parts.size() > 1 ? decode(parts.get(1)) : "";
			result.put(key, value);
		}
		return result;
	}

	private static String decode(String s)
	{
		return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
	}

	private static String urlEncode(String s)
	{
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String randomUrlSafeString(int numBytes)
	{
		byte[] bytes = new byte[numBytes];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String pkceChallenge(String codeVerifier)
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}
}

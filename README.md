# RuneLite Spotify Controller

A RuneLite sidebar plugin for controlling Spotify playback (play/pause, skip, volume, now-playing track/artist/album art) without alt-tabbing out of the game.

Requires a **Spotify Premium** account — Spotify's playback-control API endpoints don't work on free accounts.

## One-time setup

1. Go to the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) and create an app.
   - Redirect URI: `http://127.0.0.1:8888/callback` (must match exactly — Spotify requires the literal loopback IP, not `localhost`)
   - APIs used: Web API
   - No client secret is needed — this plugin uses the Authorization Code + PKCE flow.
2. Copy the app's **Client ID**.
3. Build and sideload the plugin (see [RuneLite's plugin development docs](https://github.com/runelite/runelite/wiki/Building-with-IntelliJ-IDEA) or your preferred sideloader).
4. Open the plugin's config in RuneLite and paste the Client ID into the `clientId` field.
5. Click **Connect to Spotify** in the sidebar panel and approve access in the browser that opens.

## How it works

- `SpotifyAuthManager` — Authorization Code + PKCE flow: opens the system browser for consent, runs a temporary local HTTP listener on `127.0.0.1:8888` to catch the redirect, exchanges the code for tokens, and handles refresh.
- `SpotifyApiClient` — async wrapper over the Spotify Web API's player endpoints (`/v1/me/player`, `/play`, `/pause`, `/next`, `/previous`, `/volume`).
- `SpotifyControllerPanel` — the sidebar UI (album art, track/artist, transport controls, volume slider).
- `SpotifyControllerPlugin` — wires everything together and polls playback state on an interval.

Playback control requires an active Spotify device (i.e. Spotify open and either playing or recently playing somewhere) — the panel will show "No active device" otherwise.

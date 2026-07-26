package com.example.spotify;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("spotifycontroller")
public interface SpotifyControllerConfig extends Config
{
	@ConfigItem(
		keyName = "clientId",
		name = "Spotify Client ID",
		description = "Client ID from your app at developer.spotify.com/dashboard (PKCE flow, no client secret needed)",
		position = 1
	)
	default String clientId()
	{
		return "";
	}

	@ConfigItem(
		keyName = "pollIntervalSeconds",
		name = "Poll interval (seconds)",
		description = "How often to refresh the now-playing panel from Spotify",
		position = 2
	)
	default int pollIntervalSeconds()
	{
		return 3;
	}

	// Storage only, never shown in the config UI. Written/read directly via
	// ConfigManager.setConfiguration/getConfiguration rather than through this
	// interface, same as BloodMoonRisesPanel's hidden currentStep key.
	@ConfigItem(
		keyName = "refreshToken",
		name = "",
		description = "",
		hidden = true
	)
	default String refreshToken()
	{
		return "";
	}
}

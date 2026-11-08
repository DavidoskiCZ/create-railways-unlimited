package com.davzelenec.railwaysunlimited.client;

import com.davzelenec.railwaysunlimited.network.PlayerSettingsPayload;
import com.davzelenec.railwaysunlimited.snap.PlayerSettings;
import com.davzelenec.railwaysunlimited.snap.SnapSettings;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/** The client's side of the per-save settings: apply a change at once and have the server store it. */
public final class ClientSnap {
	private ClientSnap() {}

	public static void chooseStep(int step) {
		change(SnapSettings.getClientSettings().withSnapStep(step));
	}

	public static void setMaxLength(int length) {
		change(SnapSettings.getClientSettings().withMaxLength(length));
	}

	public static void setShowJoints(boolean show) {
		change(SnapSettings.getClientSettings().withShowJoints(show));
	}

	public static void setGaugePreview(boolean show) {
		change(SnapSettings.getClientSettings().withGaugePreview(show));
	}

	public static void setClearGhosts(boolean clear) {
		change(SnapSettings.getClientSettings().withClearGhosts(clear));
	}

	private static void change(PlayerSettings settings) {
		SnapSettings.setClientSettings(settings);
		if (Minecraft.getInstance().getConnection() != null)
			PacketDistributor.sendToServer(new PlayerSettingsPayload(SnapSettings.getClientSettings()));
	}

	/** Leaving a world: the next one starts from its own stored values (or the defaults). */
	public static void reset() {
		SnapSettings.setClientSettings(PlayerSettings.DEFAULT);
	}
}

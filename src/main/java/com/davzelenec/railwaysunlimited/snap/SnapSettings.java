package com.davzelenec.railwaysunlimited.snap;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

/**
 * The angle step (in degrees) that straight track directions snap to. 45 is Create's own behaviour.
 * <p>
 * The server keeps each player's step in the save ({@link WorldSettings}) and tells the client when the player
 * joins; the client changes it through the step screen, which asks the server to store it.
 */
public final class SnapSettings {
	public static final List<Integer> STEPS = List.of(5, 15, 30, 45);
	public static final int DEFAULT_STEP = 45;

	private static final Set<UUID> SERVER_STRAIGHT = ConcurrentHashMap.newKeySet();
	private static volatile PlayerSettings clientSettings = PlayerSettings.DEFAULT;
	private static volatile boolean clientStraight = false;
	private static volatile boolean clientKeepHeight = false;
	private static volatile boolean clientKeepGrade = false;
	private static final Set<UUID> SERVER_KEEP_HEIGHT = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> SERVER_KEEP_GRADE = ConcurrentHashMap.newKeySet();

	private SnapSettings() {}

	public static boolean isValid(int step) {
		return STEPS.contains(step);
	}

	public static int sanitize(int step) {
		return isValid(step) ? step : DEFAULT_STEP;
	}

	public static int getStep(Player player) {
		if (player.level().isClientSide)
			return clientSettings.snapStep();
		MinecraftServer server = player.getServer();
		return server == null ? DEFAULT_STEP : WorldSettings.get(server).getSnapStep(player.getUUID());
	}

	/** How far one track placement of this player may reach (Create's "too far" limit). */
	public static int getMaxLength(Player player) {
		if (player.level().isClientSide)
			return clientSettings.maxLength();
		MinecraftServer server = player.getServer();
		return server == null ? PlayerSettings.DEFAULT_MAX_LENGTH
			: WorldSettings.get(server).get(player.getUUID()).maxLength();
	}

	/** The settings the client currently knows, as last sent by the server or chosen in the settings screen. */
	public static PlayerSettings getClientSettings() {
		return clientSettings;
	}

	public static void setClientSettings(PlayerSettings settings) {
		clientSettings = settings.sanitized();
	}

	public static int getClientStep() {
		return clientSettings.snapStep();
	}

	public static void setClientStep(int step) {
		clientSettings = clientSettings.withSnapStep(step);
	}

	public static void setServerStep(Player player, int step) {
		MinecraftServer server = player.getServer();
		if (server != null)
			WorldSettings.get(server).setSnapStep(player.getUUID(), step);
	}

	/** Whether the player holds the "straight track" key, which continues the started track straight on. */
	public static boolean isStraightMode(Player player) {
		if (player.level().isClientSide)
			return clientStraight;
		return SERVER_STRAIGHT.contains(player.getUUID());
	}

	public static void setClientStraight(boolean held) {
		clientStraight = held;
	}

	/** Keep-height key held: the new track ends at the start's height, whatever block is aimed at. */
	public static boolean isKeepHeight(Player player) {
		if (player.level().isClientSide)
			return clientKeepHeight;
		return SERVER_KEEP_HEIGHT.contains(player.getUUID());
	}

	/** Keep-grade key held: the new track continues at the start's grade (e.g. on up a hill). */
	public static boolean isKeepGrade(Player player) {
		if (player.level().isClientSide)
			return clientKeepGrade;
		return SERVER_KEEP_GRADE.contains(player.getUUID());
	}

	public static void setClientKeys(boolean keepHeight, boolean keepGrade) {
		clientKeepHeight = keepHeight;
		clientKeepGrade = keepGrade;
	}

	public static void setServerStraight(Player player, boolean held, boolean keepHeight, boolean keepGrade) {
		UUID id = player.getUUID();
		if (held)
			SERVER_STRAIGHT.add(id);
		else
			SERVER_STRAIGHT.remove(id);
		// keeping the height or grade works for every placement, not only straight track
		if (keepHeight)
			SERVER_KEEP_HEIGHT.add(id);
		else
			SERVER_KEEP_HEIGHT.remove(id);
		if (keepGrade)
			SERVER_KEEP_GRADE.add(id);
		else
			SERVER_KEEP_GRADE.remove(id);
	}

	public static void forget(Player player) {
		SERVER_STRAIGHT.remove(player.getUUID());
		SERVER_KEEP_HEIGHT.remove(player.getUUID());
		SERVER_KEEP_GRADE.remove(player.getUUID());
	}
}

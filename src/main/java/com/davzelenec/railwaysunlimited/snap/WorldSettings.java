package com.davzelenec.railwaysunlimited.snap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-player settings stored in the save (world or server), so every world starts with Create's defaults and each
 * player on a server keeps their own choices.
 */
public class WorldSettings extends SavedData {
	private static final String NAME = "railways_unlimited_settings";
	private static final Factory<WorldSettings> FACTORY = new Factory<>(WorldSettings::new, WorldSettings::load, null);

	private final Map<UUID, PlayerSettings> players = new HashMap<>();

	public static WorldSettings get(MinecraftServer server) {
		return server.overworld()
			.getDataStorage()
			.computeIfAbsent(FACTORY, NAME);
	}

	public PlayerSettings get(UUID player) {
		return players.getOrDefault(player, PlayerSettings.DEFAULT);
	}

	public void set(UUID player, PlayerSettings settings) {
		players.put(player, settings.sanitized());
		setDirty();
	}

	public int getSnapStep(UUID player) {
		return get(player).snapStep();
	}

	public void setSnapStep(UUID player, int step) {
		set(player, get(player).withSnapStep(step));
	}

	private static WorldSettings load(CompoundTag tag, HolderLookup.Provider registries) {
		WorldSettings settings = new WorldSettings();
		CompoundTag players = tag.getCompound("Players");
		for (String key : players.getAllKeys()) {
			try {
				settings.players.put(UUID.fromString(key), PlayerSettings.read(players.getCompound(key)));
			} catch (IllegalArgumentException ignored) {
			}
		}
		// saves from 0.1.0 development builds stored only the step
		CompoundTag oldSteps = tag.getCompound("SnapSteps");
		for (String key : oldSteps.getAllKeys()) {
			try {
				UUID player = UUID.fromString(key);
				settings.players.putIfAbsent(player, PlayerSettings.DEFAULT.withSnapStep(oldSteps.getInt(key)));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return settings;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		CompoundTag players = new CompoundTag();
		this.players.forEach((player, settings) -> players.put(player.toString(), settings.write()));
		tag.put("Players", players);
		return tag;
	}
}

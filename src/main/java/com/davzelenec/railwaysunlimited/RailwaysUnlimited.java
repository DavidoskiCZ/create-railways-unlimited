package com.davzelenec.railwaysunlimited;

import org.slf4j.Logger;

import com.davzelenec.railwaysunlimited.compat.TrackGraphCleanup;
import com.davzelenec.railwaysunlimited.config.RUClientConfig;
import com.davzelenec.railwaysunlimited.network.ConnectToLinePayload;
import com.davzelenec.railwaysunlimited.network.PlayerSettingsPayload;
import com.davzelenec.railwaysunlimited.network.StraightModePayload;
import com.davzelenec.railwaysunlimited.snap.SnapSettings;
import com.davzelenec.railwaysunlimited.snap.WorldSettings;
import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;

@Mod(RailwaysUnlimited.MOD_ID)
public class RailwaysUnlimited {
	public static final String MOD_ID = "railways_unlimited";
	public static final Logger LOGGER = LogUtils.getLogger();

	public RailwaysUnlimited(IEventBus modBus, ModContainer container) {
		RUBlocks.register(modBus);
		container.registerConfig(ModConfig.Type.CLIENT, RUClientConfig.SPEC);
		modBus.addListener(RegisterPayloadHandlersEvent.class, RailwaysUnlimited::registerPayloads);
		NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
			// settings live in the save: tell the joining player what they chose in this world
			if (event.getEntity() instanceof ServerPlayer player)
				PacketDistributor.sendToPlayer(player,
					new PlayerSettingsPayload(WorldSettings.get(player.server).get(player.getUUID())));
		});
		NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class,
			event -> SnapSettings.forget(event.getEntity()));
		NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post.class, event -> {
			// debug setting: keep the track graph around this player free of nodes without track
			if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 20 != 0)
				return;
			if (!WorldSettings.get(player.server).get(player.getUUID()).clearGhosts())
				return;
			int removed = TrackGraphCleanup.clearGhostsAround(player.level(), player.position(), 32);
			if (removed > 0)
				LOGGER.info("Removed {} track graph nodes without track near {}", removed, player.getScoreboardName());
		});
	}

	private static void registerPayloads(RegisterPayloadHandlersEvent event) {
		event.registrar("1")
			.playBidirectional(PlayerSettingsPayload.TYPE, PlayerSettingsPayload.STREAM_CODEC,
				new DirectionalPayloadHandler<>(PlayerSettingsPayload::handleOnClient,
					PlayerSettingsPayload::handleOnServer))
			.playToServer(StraightModePayload.TYPE, StraightModePayload.STREAM_CODEC, StraightModePayload::handle)
			.playToServer(ConnectToLinePayload.TYPE, ConnectToLinePayload.STREAM_CODEC, ConnectToLinePayload::handle);
	}

	public static ResourceLocation asResource(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}

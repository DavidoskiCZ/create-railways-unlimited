package com.davzelenec.railwaysunlimited.client;

import org.lwjgl.glfw.GLFW;

import com.davzelenec.railwaysunlimited.RailwaysUnlimited;
import com.davzelenec.railwaysunlimited.network.StraightModePayload;
import com.davzelenec.railwaysunlimited.snap.SnapSettings;
import net.neoforged.neoforge.network.PacketDistributor;
import com.mojang.blaze3d.platform.InputConstants;
import com.simibubi.create.AllTags;
import com.simibubi.create.content.equipment.goggles.GogglesItem;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = RailwaysUnlimited.MOD_ID, dist = Dist.CLIENT)
public class RailwaysUnlimitedClient {
	public static final KeyMapping SNAP_MENU = new KeyMapping("key.railways_unlimited.snap_menu",
		InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.railways_unlimited");

	public static final KeyMapping STRAIGHT_TRACK = new KeyMapping("key.railways_unlimited.straight_track",
		InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, "key.categories.railways_unlimited");

	/** Held while placing: end at the start's height. GLFW names keys by their US position; on a Czech (QWERTZ)
	 * keyboard this is the key labelled Y, right above Alt. */
	public static final KeyMapping KEEP_HEIGHT = new KeyMapping("key.railways_unlimited.keep_height",
		InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.railways_unlimited");
	/** Held while placing: continue at the start's grade. */
	public static final KeyMapping KEEP_GRADE = new KeyMapping("key.railways_unlimited.keep_grade",
		InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, "key.categories.railways_unlimited");

	private static boolean straightHeld = false;
	private static boolean keepHeightHeld = false;
	private static boolean keepGradeHeld = false;

	public RailwaysUnlimitedClient(IEventBus modBus, ModContainer container) {
		container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

		modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
			event.register(SNAP_MENU);
			event.register(STRAIGHT_TRACK);
			event.register(KEEP_HEIGHT);
			event.register(KEEP_GRADE);
		});
		modBus.addListener(RegisterGuiLayersEvent.class, event -> {
			event.registerAbove(VanillaGuiLayers.HOTBAR, RailwaysUnlimited.asResource("placement_angle"),
				AngleOverlay.LAYER);
			event.registerAbove(VanillaGuiLayers.HOTBAR, RailwaysUnlimited.asResource("turn_hint"),
				TurnHintOverlay.LAYER);
		});
		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> onClientTick());
		// per-save settings: forget the last world's values; the server sends this world's on join
		NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> {
			ClientSnap.reset();
			straightHeld = false;
			keepHeightHeld = false;
			keepGradeHeld = false;
		});
	}

	private static void onClientTick() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		JointDebugView.tick();

		// the server needs to know the placement keys (straight, keep height, keep grade) when it handles the click
		boolean straight = player != null && mc.screen == null && STRAIGHT_TRACK.isDown();
		boolean inGame = player != null && mc.screen == null;
		// keep height and keep grade exclude each other: the one held first stays on while both are down
		boolean heightDown = inGame && KEEP_HEIGHT.isDown();
		boolean gradeDown = inGame && KEEP_GRADE.isDown();
		boolean keepHeight = heightDown && !(gradeDown && keepGradeHeld);
		boolean keepGrade = gradeDown && !keepHeight;
		if (straight != straightHeld || keepHeight != keepHeightHeld || keepGrade != keepGradeHeld) {
			straightHeld = straight;
			keepHeightHeld = keepHeight;
			keepGradeHeld = keepGrade;
			SnapSettings.setClientStraight(straight);
			SnapSettings.setClientKeys(keepHeight, keepGrade);
			if (mc.getConnection() != null)
				PacketDistributor.sendToServer(new StraightModePayload(straight, keepHeight, keepGrade));
		}

		while (SNAP_MENU.consumeClick()) {
			if (player == null || mc.screen != null)
				continue;
			boolean holdingTrack = AllTags.AllBlockTags.TRACKS.matches(player.getMainHandItem())
				|| AllTags.AllBlockTags.TRACKS.matches(player.getOffhandItem());
			if (!holdingTrack)
				continue;
			if (!GogglesItem.isWearingGoggles(player)) {
				player.displayClientMessage(Component.translatable("railways_unlimited.message.need_goggles")
					.withStyle(ChatFormatting.GOLD), true);
				continue;
			}
			mc.setScreen(new SnapScreen());
		}
	}
}

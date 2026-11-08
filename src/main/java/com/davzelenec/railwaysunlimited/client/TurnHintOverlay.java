package com.davzelenec.railwaysunlimited.client;

import com.davzelenec.railwaysunlimited.snap.SnapSettings;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllTags;

import net.createmod.catnip.theme.Color;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * The key tip line under the action bar while connecting track, in the style of Create's own tip (which it
 * replaces): maximized turn, straight track, keep height and keep grade. A key lights up while it is in effect.
 */
public final class TurnHintOverlay {
	public static final LayeredDraw.Layer LAYER = TurnHintOverlay::render;
	private static final int GREEN = new Color(0x4ADB4A).getRGB();

	private TurnHintOverlay() {}

	private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui || mc.gameMode == null
			|| mc.gameMode.getPlayerMode() == GameType.SPECTATOR)
			return;
		ItemStack held = mc.player.getMainHandItem();
		if (!AllTags.AllBlockTags.TRACKS.matches(held) || !held.has(AllDataComponents.TRACK_CONNECTING_FROM))
			return;

		// keep height / keep grade light up by what is in effect: only one of them applies at a time
		Component keys = Component.translatable("railways_unlimited.hint.hold",
			tip("railways_unlimited.hint.maximized_turn", mc.options.keySprint, mc.options.keySprint.isDown()),
			tip("railways_unlimited.hint.straight", RailwaysUnlimitedClient.STRAIGHT_TRACK,
				RailwaysUnlimitedClient.STRAIGHT_TRACK.isDown()),
			tip("railways_unlimited.hint.keep_height", RailwaysUnlimitedClient.KEEP_HEIGHT,
				SnapSettings.isKeepHeight(mc.player)),
			tip("railways_unlimited.hint.keep_grade", RailwaysUnlimitedClient.KEEP_GRADE,
				SnapSettings.isKeepGrade(mc.player)));
		drawLine(graphics, mc, keys, 61);
	}

	private static Component tip(String key, KeyMapping keyMapping, boolean active) {
		return Component.translatable(key, keyMapping.getTranslatedKeyMessage()
			.copy()
			.withStyle(active ? ChatFormatting.WHITE : ChatFormatting.GRAY));
	}

	private static void drawLine(GuiGraphics graphics, Minecraft mc, Component text, int fromBottom) {
		int x = (graphics.guiWidth() - mc.font.width(text)) / 2;
		graphics.drawString(mc.font, text, x, graphics.guiHeight() - fromBottom, GREEN, false);
	}
}

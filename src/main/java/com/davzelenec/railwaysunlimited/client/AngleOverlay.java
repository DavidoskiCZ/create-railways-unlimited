package com.davzelenec.railwaysunlimited.client;

import java.util.ArrayList;
import java.util.List;

import com.davzelenec.railwaysunlimited.config.RUClientConfig;
import com.davzelenec.railwaysunlimited.mixin.client.PlacementInfoAccessor;
import com.davzelenec.railwaysunlimited.placement.AngledTrackPlacement;
import com.davzelenec.railwaysunlimited.placement.PlacementPlan;
import com.davzelenec.railwaysunlimited.placement.TrackGeometry;
import com.davzelenec.railwaysunlimited.snap.SnapSettings;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllTags;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackPlacement;

import net.minecraft.core.BlockPos;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.foundation.gui.RemovedGuiUtils;
import com.simibubi.create.infrastructure.config.AllConfigs;
import com.simibubi.create.infrastructure.config.CClient;

import net.createmod.catnip.gui.element.BoxElement;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.createmod.catnip.theme.Color;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Goggle-style tooltip that shows the angle track will be placed at. Purely informational.
 * Shown while wearing Engineer's Goggles, holding track and looking at a block.
 */
public final class AngleOverlay {
	public static final LayeredDraw.Layer LAYER = AngleOverlay::render;

	private AngleOverlay() {}

	private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.options.hideGui || mc.screen != null || mc.gameMode == null
			|| mc.gameMode.getPlayerMode() == GameType.SPECTATOR)
			return;
		if (!RUClientConfig.SHOW_OVERLAY.get())
			return;
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)
			return;
		// Create's own goggle tooltip is shown for this block; do not draw over it
		if (mc.level != null && mc.level.getBlockEntity(hit.getBlockPos()) instanceof IHaveGoggleInformation)
			return;

		ItemStack track = heldTrack(player);
		if (track == null || !GogglesItem.isWearingGoggles(player))
			return;

		int step = SnapSettings.getClientStep();
		PlacementPlan plan = PlacementPreview.lastPlan;
		Vec3 direction;
		double grade;
		if (plan != null && plan.endDirection != null) {
			direction = plan.endDirection;
			grade = plan.valid ? plan.grade : estimatedGrade(player, hit);
		} else {
			// Create places this one itself: estimate from the start's height to the aimed block
			direction = TrackGeometry.fromYaw(AngledTrackPlacement.snappedLookYaw(player, step));
			grade = estimatedGrade(player, hit);
		}

		List<Component> lines = new ArrayList<>();
		// indented like Create's goggle tooltips, so the item icon does not cover the title
		lines.add(Component.literal("    ").append(Component.translatable("railways_unlimited.overlay.title")));
		lines.add(line("railways_unlimited.overlay.angle", TrackGeometry.displayAngle(direction) + "°"));
		lines.add(line("railways_unlimited.overlay.step", step + "°"));
		lines.add(line("railways_unlimited.overlay.grade", formatGrade(grade)));

		draw(graphics, mc, track, lines);
	}

	/** "+8.5 %", "-2.0 %", or just "0 %" for flat track (no "-0.0 %" from rounding noise). */
	static String formatGrade(double grade) {
		double percent = Math.round(grade * 1000) / 10.0;
		if (percent == 0)
			return "0 %";
		return String.format("%+.1f %%", percent);
	}

	/**
	 * Grade of what Create is about to build: the average grade of its previewed curve (the same measure keeping the
	 * grade continues with), else from the start of the connection to the aimed block; 0 when nothing is connected.
	 */
	private static double estimatedGrade(LocalPlayer player, BlockHitResult hit) {
		TrackPlacement.ConnectingFrom from = player.getMainHandItem().get(AllDataComponents.TRACK_CONNECTING_FROM);
		if (from == null)
			return 0;
		if (TrackPlacement.cached instanceof PlacementInfoAccessor info && info.railwaysUnlimited$isValid()) {
			BezierConnection curve = info.railwaysUnlimited$getCurve();
			if (curve == null)
				return 0;
			Vec3 a = curve.starts.getFirst();
			Vec3 b = curve.starts.getSecond();
			double run = TrackGeometry.horizontal(b.subtract(a)).length();
			// Create's curve runs from the start of the connection to the target
			return run < 1e-3 ? 0 : (b.y - a.y) / run;
		}
		return estimatedFromStart(from, player, hit);
	}

	private static double estimatedFromStart(TrackPlacement.ConnectingFrom from, LocalPlayer player,
											 BlockHitResult hit) {
		BlockPos pos = hit.getBlockPos();
		if (!player.level().getBlockState(pos).canBeReplaced()
			&& !(player.level().getBlockState(pos).getBlock() instanceof ITrackBlock))
			pos = pos.relative(hit.getDirection());
		Vec3 target = Vec3.atBottomCenterOf(pos);
		double run = TrackGeometry.horizontal(target.subtract(from.end())).length();
		return run < 1e-3 ? 0 : (target.y - from.end().y) / run;
	}

	private static Component line(String key, String value) {
		return Component.literal(" ")
			.append(Component.translatable(key).withStyle(ChatFormatting.GRAY))
			.append(Component.literal(" " + value).withStyle(ChatFormatting.WHITE));
	}

	private static ItemStack heldTrack(LocalPlayer player) {
		ItemStack main = player.getMainHandItem();
		if (AllTags.AllBlockTags.TRACKS.matches(main))
			return main;
		ItemStack off = player.getOffhandItem();
		if (AllTags.AllBlockTags.TRACKS.matches(off))
			return off;
		return null;
	}

	private static void draw(GuiGraphics graphics, Minecraft mc, ItemStack icon, List<Component> lines) {
		List<FormattedText> tooltip = new ArrayList<>(lines);
		int textWidth = 0;
		for (Component line : lines)
			textWidth = Math.max(textWidth, mc.font.width(line));
		int tooltipHeight = 8 + (lines.size() > 1 ? 2 + (lines.size() - 1) * 10 : 0);

		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		CClient cfg = AllConfigs.client();
		int posX = Math.min(width / 2 + cfg.overlayOffsetX.get(), width - textWidth - 20);
		int posY = Math.min(height / 2 + cfg.overlayOffsetY.get(), height - tooltipHeight - 20);

		boolean custom = cfg.overlayCustomColor.get();
		Color background = custom ? new Color(cfg.overlayBackgroundColor.get())
			: BoxElement.COLOR_VANILLA_BACKGROUND.copy().scaleAlpha(.75f);
		Color borderTop = custom ? new Color(cfg.overlayBorderColorTop.get())
			: BoxElement.COLOR_VANILLA_BORDER.getFirst().copy();
		Color borderBottom = custom ? new Color(cfg.overlayBorderColorBot.get())
			: BoxElement.COLOR_VANILLA_BORDER.getSecond().copy();

		PoseStack poseStack = graphics.pose();
		poseStack.pushPose();
		GuiGameElement.of(icon)
			.at(posX + 10, posY - 16, 450)
			.render(graphics);
		RemovedGuiUtils.drawHoveringText(graphics, tooltip, posX, posY, width, height, -1, background.getRGB(),
			borderTop.getRGB(), borderBottom.getRGB(), mc.font);
		poseStack.popPose();
	}
}

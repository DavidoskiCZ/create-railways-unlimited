package com.davzelenec.railwaysunlimited.client;

import org.jetbrains.annotations.Nullable;

import com.davzelenec.railwaysunlimited.block.AngledTrackBlock;
import com.davzelenec.railwaysunlimited.network.ConnectToLinePayload;
import com.davzelenec.railwaysunlimited.placement.TrackGeometry;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllTags;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlockOutline;
import com.simibubi.create.content.trains.track.TrackBlockOutline.BezierPointSelection;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Anchors sit under the curves they join, so a click aimed at an angled track hits Create's curve instead, which
 * refuses track items ("Cannot start connection from a Turn"). Such clicks are turned into a click on the anchor at
 * the nearer end, or, while connecting, into joining the middle of an angled straight.
 */
public final class AnchorClicks {
	private static final double MAX_DISTANCE_TO_END = 4;
	/** While connecting, aiming this close to an end joins that end instead of the middle of the line. */
	private static final double END_SNAP = 1.5;

	public record Target(BlockPos pos, Vec3 point, double distance, boolean reachable) {}

	/** A straight curve under the crosshair, stored at {@code bePos} and leading to {@code curveKey}. */
	public record LineTarget(BlockPos bePos, BlockPos curveKey, Vec3 aim) {}

	private AnchorClicks() {}

	@Nullable
	private static BezierConnection selectedCurve() {
		BezierPointSelection selection = TrackBlockOutline.result;
		if (selection == null)
			return null;
		return selection.blockEntity()
			.getConnections()
			.get(selection.loc().curveTarget());
	}

	private static boolean holdingTrack(LocalPlayer player) {
		return AllTags.AllBlockTags.TRACKS.matches(player.getMainHandItem());
	}

	private static boolean connecting(LocalPlayer player) {
		ItemStack held = player.getMainHandItem();
		return holdingTrack(player) && held.has(AllDataComponents.TRACK_CONNECTING_FROM);
	}

	/** The anchor at the nearer end of the curve under the crosshair, if the player holds track. */
	@Nullable
	public static Target findTarget() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		BezierConnection bc = selectedCurve();
		if (bc == null || player == null || mc.level == null || !holdingTrack(player))
			return null;

		Vec3 hit = TrackBlockOutline.result.vec();
		boolean first = hit.distanceToSqr(bc.starts.getFirst()) <= hit.distanceToSqr(bc.starts.getSecond());
		BlockPos anchorPos = bc.bePositions.get(first);
		Vec3 anchorPoint = bc.starts.get(first);
		if (!AngledTrackBlock.isAnchor(mc.level.getBlockState(anchorPos)))
			return null;
		double distance = hit.distanceTo(anchorPoint);
		boolean reachable = distance <= MAX_DISTANCE_TO_END && player.canInteractWithBlock(anchorPos, 1);
		return new Target(anchorPos, anchorPoint, distance, reachable);
	}

	/** While connecting: the angled straight under the crosshair, unless the aim is right at one of its ends. */
	@Nullable
	public static LineTarget findLineTarget() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		BezierConnection bc = selectedCurve();
		if (bc == null || player == null || !connecting(player) || !TrackGeometry.isStraight(bc))
			return null;
		// only a real track end is joined as an end; a joint in the middle of a line is just a point on the line
		Target end = findTarget();
		if (end != null && end.reachable() && end.distance() <= END_SNAP && mc.level != null
			&& AngledTrackBlock.connections(mc.level, end.pos()).size() <= 1)
			return null;
		BezierPointSelection selection = TrackBlockOutline.result;
		return new LineTarget(selection.blockEntity().getBlockPos(), selection.loc().curveTarget(), selection.vec());
	}

	/** @return true when the click was handled here */
	public static boolean redirect(InputEvent.InteractionKeyMappingTriggered event) {
		if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND)
			return false;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null || player.isShiftKeyDown())
			return false;

		LineTarget line = findLineTarget();
		if (line != null) {
			PacketDistributor.sendToServer(new ConnectToLinePayload(line.bePos(), line.curveKey(), line.aim(),
				mc.options.keySprint.isDown()));
			player.swing(InteractionHand.MAIN_HAND);
			event.setSwingHand(false);
			return true;
		}

		Target target = findTarget();
		if (target == null)
			return false;
		if (!target.reachable()) {
			player.displayClientMessage(Component.translatable("railways_unlimited.message.click_closer_to_end")
				.withStyle(ChatFormatting.GOLD), true);
			event.setSwingHand(false);
			return true;
		}

		BlockHitResult anchorHit = new BlockHitResult(target.point(), Direction.UP, target.pos(), false);
		mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, anchorHit);
		player.swing(InteractionHand.MAIN_HAND);
		return true;
	}
}

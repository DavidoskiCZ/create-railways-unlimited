package com.davzelenec.railwaysunlimited.client;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.davzelenec.railwaysunlimited.RailwaysUnlimited;
import com.davzelenec.railwaysunlimited.block.AngledTrackBlock;
import com.davzelenec.railwaysunlimited.mixin.client.TrackPlacementAccessor;
import com.davzelenec.railwaysunlimited.placement.AngledTrackPlacement;
import com.davzelenec.railwaysunlimited.placement.PlacementPlan;
import com.davzelenec.railwaysunlimited.placement.TrackGeometry;
import com.davzelenec.railwaysunlimited.snap.SnapSettings;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllTags;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackPlacement;
import com.simibubi.create.content.equipment.blueprint.BlueprintOverlayRenderer;

import net.createmod.catnip.data.Couple;

import net.createmod.catnip.data.Pair;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Client-side preview of an angled placement, drawn instead of Create's own while this mod is in charge. */
public final class PlacementPreview {
	private static final int COLOR_VALID = 0x95CD41;
	private static final int COLOR_INVALID = 0xEA5C2B;
	private static final Vec3 RAIL_HEIGHT = new Vec3(0, 4 / 16f, 0);
	/** Distance of each rail from the track's middle, as Create draws its own preview (Steam 'n' Rails' values). */
	private static final double RAIL_OFFSET_STANDARD = 15 / 16d;
	private static final double RAIL_OFFSET_WIDE = RAIL_OFFSET_STANDARD + 0.5;
	private static final double RAIL_OFFSET_NARROW = RAIL_OFFSET_STANDARD - 0.4375;

	/** The plan currently previewed, or null when Create (or nobody) is placing. Read by the goggle overlay. */
	@Nullable
	public static PlacementPlan lastPlan;

	private static final Map<Integer, Integer> drawnSegments = new HashMap<>();
	private static int drawnCurves = 0;
	private static int drawnAnchors = 0;
	private static int drawnBlocks = 0;

	private PlacementPreview() {}

	/** @return true when this mod handles the preview and Create's must not run */
	public static boolean tick() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			lastPlan = null;
			return false;
		}
		AngledTrackPlacement.clientSprintHeld = mc.options.keySprint.isDown();

		// connecting into the middle of an angled straight (a curve; there may be no block under the crosshair)
		AnchorClicks.LineTarget line = player.isShiftKeyDown() ? null : AnchorClicks.findLineTarget();
		if (line != null) {
			PlacementPlan plan = AngledTrackPlacement.planIntoLine(player.level(), player, line.bePos(),
				line.curveKey(), line.aim(), player.getMainHandItem(), AngledTrackPlacement.clientSprintHeld,
				SnapSettings.getClientStep());
			lastPlan = plan;
			logRefusal(plan, line.bePos());
			trace("line(" + line.bePos() + "->" + line.curveKey() + " aim=" + line.aim() + ")", line.bePos(), plan);
			TrackPlacementAccessor.railwaysUnlimited$setExtraTipWarmup(0);
			if (plan.message != null)
				player.displayClientMessage(plan.message.copy()
					.withStyle(plan.valid ? ChatFormatting.GREEN : ChatFormatting.RED), true);
			draw(plan);
			return true;
		}

		if (!(mc.hitResult instanceof BlockHitResult hit) || mc.hitResult.getType() != BlockHitResult.Type.BLOCK) {
			ItemStack held = player.getMainHandItem();
			if (AllTags.AllBlockTags.TRACKS.matches(held) && held.has(AllDataComponents.TRACK_CONNECTING_FROM)
				&& SnapSettings.isStraightMode(player) && !player.isShiftKeyDown()) {
				// straight mode aimed into the air: continue at the start's height/grade
				PlacementPlan plan = AngledTrackPlacement.planStraight(player.level(), player,
					AngledTrackPlacement.airAim(player), AngledTrackPlacement.straightTargetY(player, null), held);
				lastPlan = plan;
				if (plan.message != null)
					player.displayClientMessage(plan.message.copy()
						.withStyle(plan.valid ? ChatFormatting.GREEN : plan.neutralMessage ? ChatFormatting.WHITE
							: ChatFormatting.RED), true);
				draw(plan);
				return true;
			}
			if (held.has(AllDataComponents.TRACK_CONNECTING_FROM))
				trace("no-block-hit", BlockPos.ZERO, null);
			clear();
			return false;
		}

		ItemStack stack = player.getMainHandItem();
		if (!AllTags.AllBlockTags.TRACKS.matches(stack)) {
			clear();
			return false;
		}

		Level level = player.level();
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		boolean placing = !(state.getBlock() instanceof ITrackBlock);
		if (placing && !state.canBeReplaced()) {
			pos = pos.relative(hit.getDirection());
			BlockState above = level.getBlockState(pos);
			if (above.getBlock() instanceof ITrackBlock) {
				state = above;
				placing = false;
			}
		}

		// aiming at an angled curve near its end: preview the same anchor a click would use
		boolean connecting = stack.has(AllDataComponents.TRACK_CONNECTING_FROM);
		AnchorClicks.Target anchor = connecting ? AnchorClicks.findTarget() : null;
		if (anchor != null && anchor.reachable()) {
			pos = anchor.pos();
			state = level.getBlockState(pos);
			placing = false;
		}

		if (!stack.has(AllDataComponents.TRACK_CONNECTING_FROM)) {
			// show the short angled piece a first click would place; Create has nothing to preview here
			if (player.isShiftKeyDown() || !placing || !level.getBlockState(pos).canBeReplaced()) {
				clear();
				return false;
			}
			if (!AngledTrackPlacement.shouldPlaceStarter(player, stack, state)) {
				// Create places a single block at 0 or 45 degrees here; preview it the same way for consistency
				PlacementPlan single = singleBlockPreview(player, pos, stack);
				lastPlan = single;
				draw(single);
				return false;
			}
			PlacementPlan plan = AngledTrackPlacement.planStarter(level, player, pos, stack,
				SnapSettings.getClientStep());
			lastPlan = plan;
			draw(plan);
			return false;
		}

		PlacementPlan plan;
		if (SnapSettings.isStraightMode(player)) {
			plan = AngledTrackPlacement.planStraight(level, player, hit.getLocation(),
				AngledTrackPlacement.straightTargetY(player, hit), stack);
		} else {
			if (!AngledTrackPlacement.shouldHandle(level, player, stack, pos, placing ? null : state)) {
				trace("create-handles placing=" + placing, pos, null);
				clear();
				return false;
			}
			PlacementPlan viaLine = !placing && AngledTrackBlock.isAnchor(state)
				? AngledTrackPlacement.planIntoAnchorLine(level, player, pos, stack,
					AngledTrackPlacement.clientSprintHeld, SnapSettings.getClientStep())
				: null;
			plan = viaLine != null ? viaLine
				: AngledTrackPlacement.plan(level, player, pos, placing ? null : state, stack,
					AngledTrackPlacement.clientSprintHeld, SnapSettings.getClientStep());
			trace((viaLine != null ? "anchor-line" : "regular") + " placing=" + placing + " anchorTarget="
				+ (anchor != null ? anchor.pos() + "/" + anchor.reachable() : "none"), pos, plan);
		}
		lastPlan = plan;
		logRefusal(plan, pos);
		// Create's own "hold for maximized turn" tip must not linger from its last preview
		TrackPlacementAccessor.railwaysUnlimited$setExtraTipWarmup(0);

		if (plan.message != null)
			player.displayClientMessage(plan.message.copy()
				.withStyle(plan.valid ? ChatFormatting.GREEN : plan.neutralMessage ? ChatFormatting.WHITE
					: ChatFormatting.RED), true);

		draw(plan);
		return true;
	}

	/** The one track block Create would place, drawn like the other previews. */
	private static PlacementPlan singleBlockPreview(LocalPlayer player, BlockPos pos, ItemStack stack) {
		Vec3 heading = TrackGeometry.fromYaw(AngledTrackPlacement.snappedLookYaw(player, 45));
		Vec3 grid = new Vec3(Math.round(heading.x), 0, Math.round(heading.z));
		Vec3 center = Vec3.atBottomCenterOf(pos);
		Vec3 from = center.subtract(grid.scale(0.5));
		Vec3 to = center.add(grid.scale(0.5));
		Vec3 axis = grid.normalize();
		PlacementPlan plan = new PlacementPlan();
		plan.material = TrackMaterial.fromItem(stack.getItem());
		plan.curves.add(new BezierConnection(Couple.create(pos, pos), Couple.create(from, to),
			Couple.create(axis, axis.scale(-1)), Couple.create(new Vec3(0, 1, 0), new Vec3(0, 1, 0)), true, false,
			plan.material));
		plan.endDirection = axis;
		plan.valid = true;
		return plan;
	}

	private static String lastLogged = "";

	private static String lastTrace = "";

	/** With the joint debug view on: log which way the preview went, once per change, for diagnosing. */
	static void trace(String route, BlockPos target, @Nullable PlacementPlan plan) {
		if (!SnapSettings.getClientSettings().showJoints())
			return;
		Minecraft mc = Minecraft.getInstance();
		StringBuilder entry = new StringBuilder(route).append(" target=").append(target);
		if (mc.level != null && AngledTrackBlock.isAnchor(mc.level.getBlockState(target))) {
			entry.append(" anchor{");
			for (BezierConnection bc : AngledTrackBlock.connections(mc.level, target).values())
				entry.append(" ->").append(bc.getKey()).append(TrackGeometry.isStraight(bc) ? " straight" : " curve")
					.append(" dir=").append(TrackGeometry.horizontalDir(bc.axes.getFirst()));
			entry.append(" }");
		}
		if (plan != null)
			entry.append(" plan{valid=").append(plan.valid).append(" curves=").append(plan.curves.size())
				.append(" split=").append(plan.split != null).append(" end=").append(plan.endPos).append(" msg=")
				.append(plan.message == null ? "-" : plan.message.getString()).append(" debug=").append(plan.debug)
				.append("}");
		String text = entry.toString();
		if (text.equals(lastTrace))
			return;
		lastTrace = text;
		RailwaysUnlimited.LOGGER.info("[preview] {}", text);
	}

	/** Writes each new reason a previewed placement is refused to the log once, for diagnosing. */
	private static void logRefusal(PlacementPlan plan, BlockPos target) {
		if (plan.valid || plan.neutralMessage || plan.message == null)
			return;
		String entry = plan.message.getString() + " [" + plan.debug + "]";
		if (entry.equals(lastLogged))
			return;
		lastLogged = entry;
		RailwaysUnlimited.LOGGER.info("Preview refused at {}: {}", target, entry);
	}

	private static void clear() {
		lastPlan = null;
		removeStale(0, 0);
		for (int b = 0; b < drawnBlocks; b++)
			Outliner.getInstance().remove(Pair.of("railways_unlimited_block", b));
		drawnBlocks = 0;
	}

	/** Whether the previewed placement contains a real turn, for which Sprint widens the curve. */
	public static boolean previewHasTurn() {
		PlacementPlan plan = lastPlan;
		if (plan == null || !plan.valid)
			return false;
		for (BezierConnection bc : plan.curves)
			if (!TrackGeometry.isStraight(bc))
				return true;
		return false;
	}

	/** In survival, the tracks this placement costs, shown above the hotbar the way Create shows its own. */
	private static void showTrackCost(PlacementPlan plan) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || player.isCreative() || plan.material == null || plan.requiredTracks <= 0)
			return;
		Item track = plan.material.getBlock().asItem();
		boolean enough = AngledTrackPlacement.countTracks(player, track) >= plan.requiredTracks;
		if (!plan.valid && enough)
			return;
		TrackPlacement.PlacementInfo info = new TrackPlacement.PlacementInfo(plan.material);
		info.requiredTracks = plan.requiredTracks;
		info.hasRequiredTracks = enough;
		BlueprintOverlayRenderer.displayTrackRequirements(info, player.getOffhandItem());
	}

	/**
	 * How far each rail is drawn from the middle for this track: Steam 'n' Rails' wide and narrow gauge differ, and a
	 * monorail has one rail in the middle (0). Always standard when the setting is off.
	 */
	static double railOffset(@Nullable TrackMaterial material) {
		if (material == null || !SnapSettings.getClientSettings().gaugePreview())
			return RAIL_OFFSET_STANDARD;
		return switch (material.trackType.id.toString()) {
			case "railways:wide_gauge" -> RAIL_OFFSET_WIDE;
			case "railways:narrow_gauge", "railways:universal" -> RAIL_OFFSET_NARROW;
			case "railways:monorail" -> 0;
			default -> RAIL_OFFSET_STANDARD;
		};
	}

	private static void draw(PlacementPlan plan) {
		int color = plan.valid ? COLOR_VALID : COLOR_INVALID;
		Outliner outliner = Outliner.getInstance();
		double railOffset = railOffset(plan.material);
		showTrackCost(plan);

		for (int c = 0; c < plan.curves.size(); c++) {
			BezierConnection bc = plan.curves.get(c);
			int segments = Math.max(1, bc.getSegmentCount());
			Vec3 previous1 = null;
			Vec3 previous2 = null;
			for (int i = 0; i <= segments; i++) {
				double t = i / (double) segments;
				Vec3 position = bc.getPosition(t);
				Vec3 ahead = bc.getPosition(Math.min(1, t + 0.01));
				Vec3 behind = bc.getPosition(Math.max(0, t - 0.01));
				Vec3 derivative = ahead.subtract(behind).normalize();
				Vec3 side = bc.getNormal(t).cross(derivative).scale(railOffset);
				Vec3 rail1 = position.add(side).add(RAIL_HEIGHT);
				Vec3 rail2 = position.subtract(side).add(RAIL_HEIGHT);
				if (previous1 != null) {
					outliner.showLine(Pair.of(key(c), i * 2), previous1, rail1)
						.colored(color)
						.disableLineNormals()
						.lineWidth(1 / 16f);
					outliner.showLine(Pair.of(key(c), i * 2 + 1), previous2, rail2)
						.colored(color)
						.disableLineNormals()
						.lineWidth(1 / 16f);
				}
				previous1 = rail1;
				previous2 = rail2;
			}
			Integer before = drawnSegments.put(c, segments);
			if (before != null)
				for (int i = segments + 1; i <= before; i++) {
					outliner.remove(Pair.of(key(c), i * 2));
					outliner.remove(Pair.of(key(c), i * 2 + 1));
				}
		}

		for (int a = 0; a < plan.anchorPoints.size(); a++) {
			Vec3 point = plan.anchorPoints.get(a).add(RAIL_HEIGHT);
			outliner.showAABB(Pair.of("railways_unlimited_anchor", a), new AABB(point, point).inflate(0.2))
				.colored(color)
				.lineWidth(1 / 16f);
		}

		// Create track blocks that will be placed (straight parts next to Create track)
		int blocks = 0;
		for (PlacementPlan.NewBlock block : plan.newBlocks) {
			if (AngledTrackBlock.isAnchor(block.state()))
				continue;
			BlockPos pos = block.pos();
			outliner.showAABB(Pair.of("railways_unlimited_block", blocks++),
					new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 2 / 16d, pos.getZ() + 1))
				.colored(color)
				.lineWidth(1 / 16f);
		}
		for (int b = blocks; b < drawnBlocks; b++)
			outliner.remove(Pair.of("railways_unlimited_block", b));
		drawnBlocks = blocks;

		removeStale(plan.curves.size(), plan.anchorPoints.size());
	}

	private static void removeStale(int curves, int anchors) {
		Outliner outliner = Outliner.getInstance();
		for (int c = curves; c < drawnCurves; c++) {
			Integer segments = drawnSegments.remove(c);
			if (segments == null)
				continue;
			for (int i = 0; i <= segments; i++) {
				outliner.remove(Pair.of(key(c), i * 2));
				outliner.remove(Pair.of(key(c), i * 2 + 1));
			}
		}
		for (int a = anchors; a < drawnAnchors; a++)
			outliner.remove(Pair.of("railways_unlimited_anchor", a));
		drawnCurves = curves;
		drawnAnchors = anchors;
	}

	private static String key(int curve) {
		return "railways_unlimited_curve_" + curve;
	}
}

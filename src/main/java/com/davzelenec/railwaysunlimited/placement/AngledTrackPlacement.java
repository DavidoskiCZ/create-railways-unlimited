package com.davzelenec.railwaysunlimited.placement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.davzelenec.railwaysunlimited.RUBlocks;
import com.davzelenec.railwaysunlimited.RailwaysUnlimited;
import com.davzelenec.railwaysunlimited.block.AngledTrackBlock;
import com.davzelenec.railwaysunlimited.compat.TrackGraphCleanup;
import com.davzelenec.railwaysunlimited.snap.SnapSettings;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.AllTags;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackPlacement.ConnectingFrom;
import com.simibubi.create.content.trains.track.TrackPropagator;
import com.simibubi.create.content.trains.track.TrackShape;
import com.simibubi.create.foundation.block.ProperWaterloggedBlock;

import net.createmod.catnip.data.Couple;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Track placement for straight runs at any multiple of the player's angle step.
 * <p>
 * A placement is a chain of Create curves: an optional straight from the start, a turn (or S-bend), and an optional
 * straight into the end. Straights at angles Create has no block for are "straight curves" between anchors. Heights
 * follow one constant grade between the ends; where the grade of an existing end differs, the first or last piece
 * eases between the two grades, so consecutive sloped pieces join into one continuous slope.
 */
public final class AngledTrackPlacement {
	public static final double MAX_GRADE = 1 / 3d;
	private static final Vec3 UP = new Vec3(0, 1, 0);

	/** Set by the client every tick; used for the client-side copy of a placement. */
	public static volatile boolean clientSprintHeld = false;

	private AngledTrackPlacement() {}

	// ---------------------------------------------------------------- deciding who places

	/**
	 * Whether this mod places the track instead of Create. Create keeps everything it can already do on its own:
	 * both ends on its own track, or a new end at a multiple of 45 degrees.
	 */
	public static boolean shouldHandle(Level level, Player player, ItemStack stack, BlockPos targetPos,
									   @Nullable BlockState existingTarget) {
		ConnectingFrom from = stack.get(AllDataComponents.TRACK_CONNECTING_FROM);
		if (from == null)
			return false;
		if (AngledTrackBlock.isAnchor(level.getBlockState(from.pos())))
			return true;
		if (existingTarget != null)
			return AngledTrackBlock.isAnchor(existingTarget);
		// keeping the height or grade is this mod's; Create always ends at the clicked block, flat
		if (SnapSettings.isKeepHeight(player) || SnapSettings.isKeepGrade(player))
			return true;
		int step = SnapSettings.getStep(player);
		if (step == 45)
			return false;
		return Math.floorMod(Math.round(snappedLookYaw(player, step)), 45) != 0;
	}

	public static double snappedLookYaw(Player player, int step) {
		Vec3 look = TrackGeometry.horizontal(player.getLookAngle());
		double yaw = look.lengthSqr() < 1e-6 ? player.getYRot() + 90 : TrackGeometry.yawOf(look);
		return TrackGeometry.snapYaw(yaw, step);
	}

	// ---------------------------------------------------------------- item interaction

	/** Called at the start of Create's track item use. Returns null to let Create handle the click. */
	@Nullable
	public static InteractionResult handleUseOn(UseOnContext context) {
		Player player = context.getPlayer();
		ItemStack stack = context.getItemInHand();
		if (player == null || context.getHand() == InteractionHand.OFF_HAND)
			return null;
		boolean connecting = stack.has(AllDataComponents.TRACK_CONNECTING_FROM);
		// sneak-clicking stays Create's: it clears the selection
		if (player.isShiftKeyDown())
			return null;

		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		BlockState state = level.getBlockState(pos);
		boolean placing = !(state.getBlock() instanceof ITrackBlock);
		if (placing && !state.canBeReplaced()) {
			pos = pos.relative(context.getClickedFace());
			// clicking the ground right under an anchor (or other track) targets that track
			BlockState above = level.getBlockState(pos);
			if (above.getBlock() instanceof ITrackBlock) {
				state = above;
				placing = false;
			}
		}

		PlacementPlan plan;
		if (connecting && SnapSettings.isStraightMode(player)) {
			// straight mode key held while connecting: continue the started track straight ahead, up to the aim
			BlockHitResult clicked = new BlockHitResult(context.getClickLocation(), context.getClickedFace(),
				context.getClickedPos(), context.isInside());
			plan = planStraight(level, player, context.getClickLocation(), straightTargetY(player, clicked), stack);
		} else if (!connecting) {
			// first track of a new line: Create would place a single block at 0 or 45 degrees
			if (!shouldPlaceStarter(player, stack, state))
				return null;
			plan = planStarter(level, player, pos, stack, SnapSettings.getStep(player));
		} else {
			if (!shouldHandle(level, player, stack, pos, placing ? null : state))
				return null;
			boolean extend = level.isClientSide ? clientSprintHeld
				: stack.getOrDefault(AllDataComponents.TRACK_EXTENDED_CURVE, false);
			stack.remove(AllDataComponents.TRACK_EXTENDED_CURVE);
			PlacementPlan viaLine = !placing && AngledTrackBlock.isAnchor(state)
				? planIntoAnchorLine(level, player, pos, stack, extend, SnapSettings.getStep(player)) : null;
			plan = viaLine != null ? viaLine
				: plan(level, player, pos, placing ? null : state, stack, extend, SnapSettings.getStep(player));
		}
		return finish(level, player, stack, plan, pos);
	}

	/** A click into the air: in straight-track mode, continue the started track at its height. Null otherwise. */
	@Nullable
	public static InteractionResult handleUseInAir(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (hand != InteractionHand.MAIN_HAND || player.isShiftKeyDown()
			|| !stack.has(AllDataComponents.TRACK_CONNECTING_FROM) || !SnapSettings.isStraightMode(player))
			return null;
		PlacementPlan plan = planStraight(level, player, airAim(player), straightTargetY(player, null), stack);
		return finish(level, player, stack, plan, player.blockPosition());
	}

	/** Shared end of a placement: report a refusal, or take the tracks, place everything and clear the selection. */
	public static InteractionResult finish(Level level, Player player, ItemStack stack, PlacementPlan plan,
										   BlockPos soundPos) {
		BlockPos pos = soundPos;
		if (!plan.valid) {
			if (!level.isClientSide)
				RailwaysUnlimited.LOGGER.info("Angled track refused for {} at {}: {} [{}]", player.getName()
					.getString(), pos, plan.message == null ? "?" : plan.message.getString(), plan.debug);
			if (!level.isClientSide && plan.message != null)
				player.displayClientMessage(plan.message.copy()
					.withStyle(plan.neutralMessage ? ChatFormatting.WHITE : ChatFormatting.RED), true);
			AllSoundEvents.DENY.playFrom(player, 1, 1);
			return InteractionResult.FAIL;
		}

		if (level.isClientSide)
			return InteractionResult.SUCCESS;

		if (!player.isCreative() && !consumeTracks(player, stack.getItem(), plan.requiredTracks)) {
			player.displayClientMessage(createText("track.not_enough_tracks").copy()
				.withStyle(ChatFormatting.RED), true);
			AllSoundEvents.DENY.playFrom(player, 1, 1);
			return InteractionResult.FAIL;
		}

		execute(level, plan);

		ItemStack held = player.getMainHandItem();
		if (AllTags.AllBlockTags.TRACKS.matches(held)) {
			held.remove(AllDataComponents.TRACK_CONNECTING_FROM);
			held.remove(AllDataComponents.TRACK_EXTENDED_CURVE);
		}

		SoundType sound = plan.material.getBlock().defaultBlockState().getSoundType();
		level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F,
			sound.getPitch() * 0.8F);
		return InteractionResult.SUCCESS;
	}

	// ---------------------------------------------------------------- the first piece of a new line

	/** Length of the short straight placed when a new line starts at an angle Create has no block for. */
	public static final double STARTER_LENGTH = 2;

	public static boolean shouldPlaceStarter(Player player, ItemStack stack, BlockState clicked) {
		if (stack.has(AllDataComponents.TRACK_CONNECTING_FROM) || clicked.getBlock() instanceof ITrackBlock)
			return false;
		int step = SnapSettings.getStep(player);
		if (step == 45)
			return false;
		return Math.floorMod(Math.round(snappedLookYaw(player, step)), 45) != 0;
	}

	/** A short straight piece between two anchors, starting in the clicked block and running where the player looks. */
	public static PlacementPlan planStarter(Level level, Player player, BlockPos pos, ItemStack stack, int step) {
		PlacementPlan plan = new PlacementPlan();
		TrackMaterial material = TrackMaterial.fromItem(stack.getItem());
		plan.material = material;
		if (!level.getBlockState(pos).canBeReplaced())
			return plan.fail(ownText("blocked"));

		Vec3 dir = TrackGeometry.fromYaw(snappedLookYaw(player, step));
		Vec3 start = Vec3.atBottomCenterOf(pos);
		Vec3 end = start.add(dir.scale(STARTER_LENGTH));
		BlockPos endPos = BlockPos.containing(end.x, Math.floor(end.y + 1e-6), end.z);
		BlockState there = level.getBlockState(endPos);
		if (endPos.equals(pos) || !there.canBeReplaced() && !AllBlocks.FAKE_TRACK.has(there)
			|| nodeTaken(level, start) || nodeTaken(level, end))
			return plan.fail(ownText("anchor_blocked"));

		plan.startPos = pos;
		plan.endPos = endPos;
		plan.endDirection = dir;
		BlockState anchorState = RUBlocks.ANGLED_TRACK.get().defaultBlockState();
		plan.newBlocks.add(new PlacementPlan.NewBlock(pos, anchorState));
		plan.newBlocks.add(new PlacementPlan.NewBlock(endPos, anchorState));
		plan.anchorPoints.add(start);
		plan.anchorPoints.add(end);

		BezierConnection bc = new BezierConnection(Couple.create(pos, endPos), Couple.create(start, end),
			Couple.create(dir, dir.scale(-1)), Couple.create(UP, UP), true, false, material);
		plan.curves.add(bc);
		plan.requiredTracks = bc.getTrackItemCost();

		if (!player.isCreative() && countTracks(player, stack.getItem()) < plan.requiredTracks)
			return plan.fail(createText("track.not_enough_tracks"));
		plan.valid = true;
		return plan;
	}

	// ---------------------------------------------------------------- forced straight (straight-track key)

	/**
	 * Continues the selected track straight on, at its own heading, as far as the point the player aims at lies
	 * along it. Off-line aiming only changes the length, never the direction.
	 *
	 * @param targetY the height the straight should end at (the aimed block, or the start's own height), or null
	 *                to continue at the start's grade
	 */
	public static PlacementPlan planStraight(Level level, Player player, Vec3 aim, @Nullable Double targetY,
											 ItemStack stack) {
		PlacementPlan plan = new PlacementPlan();
		ConnectingFrom from = stack.get(AllDataComponents.TRACK_CONNECTING_FROM);
		if (from == null)
			return plan.failNeutral(createText("track.second_point"));
		TrackMaterial material = TrackMaterial.fromItem(stack.getItem());
		plan.material = material;
		BlockPos pos1 = from.pos();
		BlockState state1 = level.getBlockState(pos1);
		if (!(state1.getBlock() instanceof ITrackBlock track1) || !state1.hasProperty(TrackBlock.HAS_BE))
			return plan.fail(createText("track.original_missing"));
		boolean startAnchor = AngledTrackBlock.isAnchor(state1);

		Vec3 axis1 = from.axis();
		if (!startAnchor && Math.abs(axis1.y) > 1e-6)
			return plan.fail(ownText("slope_endpoint"));
		Vec3 start = from.end();
		if (TrackGeometry.horizontal(axis1).dot(aim.subtract(start)) < 0) {
			axis1 = axis1.scale(-1);
			start = track1.getCurveStart(level, pos1, state1, axis1);
		}
		Vec3 u = TrackGeometry.horizontalDir(axis1);
		if (u == Vec3.ZERO)
			return plan.fail(createText("track.original_missing"));
		double startGrade = startGrade(level, pos1, state1, u, axis1);
		double distance = TrackGeometry.horizontal(aim.subtract(start)).dot(u);
		if (distance > SnapSettings.getMaxLength(player))
			return plan.fail(createText("track.too_far"));

		plan.startPos = pos1;
		plan.endDirection = u;
		plan.grade = startGrade;
		Set<BlockPos> used = new HashSet<>();
		used.add(pos1);

		// flat only when nothing climbs: keeping the grade (targetY null) of a slope must keep descending
		boolean level0 = Math.abs(startGrade) < 1e-6 && (targetY == null || Math.abs(targetY - start.y) < 1e-6);
		if (!startAnchor && level0) {
			// Create's own flat straight: just its track blocks
			Vec3 grid = gridVector(u);
			int blocks = (int) Math.round(distance / grid.length());
			if (blocks < 1)
				return plan.failNeutral(createText("track.second_point"));
			for (int i = 1; i <= blocks; i++)
				if (!addCreateTrack(level, pos1.offset(gridOffset(grid, i)), createTrackState(material, grid), plan,
					used))
					return plan.fail(ownText("anchor_blocked"));
			plan.endPos = pos1.offset(gridOffset(grid, blocks));
		} else {
			// a straight curve to a new anchor; climbing to the aimed block if one was aimed at
			if (distance < 1)
				return plan.failNeutral(createText("track.second_point"));
			Vec3 end = null;
			BlockPos endPos = null;
			double endGrade = startGrade;
			for (double nudge : new double[] {0, 0.5, -0.5, 1, -1}) {
				double length = distance + nudge;
				if (length < 1)
					continue;
				double y = targetY == null ? start.y + startGrade * length : targetY;
				Vec3 candidate = start.add(u.scale(length)).add(0, y - start.y, 0);
				BlockPos candidatePos = BlockPos.containing(candidate.x, Math.floor(candidate.y + 1e-6), candidate.z);
				BlockState there = level.getBlockState(candidatePos);
				if (used.contains(candidatePos) || !there.canBeReplaced() && !AllBlocks.FAKE_TRACK.has(there)
					|| nodeTaken(level, candidate))
					continue;
				end = candidate;
				endPos = candidatePos;
				endGrade = targetY == null ? startGrade : (y - start.y) / length;
				break;
			}
			if (end == null)
				return plan.fail(ownText("anchor_blocked"));
			if (Math.abs(endGrade) > MAX_GRADE + 1e-6)
				return plan.fail(createText("track.too_steep"));

			plan.endPos = endPos;
			plan.grade = endGrade;
			plan.newBlocks.add(new PlacementPlan.NewBlock(endPos, RUBlocks.ANGLED_TRACK.get().defaultBlockState()));
			plan.anchorPoints.add(end);
			// a Create block's own track is flat (Create's slopes end flat), so the curve eases into the grade there
			double leavingGrade = leavingGrade(level, pos1, state1, u, axis1, startGrade,
				(end.y - start.y) / TrackGeometry.horizontal(end.subtract(start)).length());
			if (startAnchor)
				plan.retangent = retangentBehind(level, pos1, u, leavingGrade);
			// the new end is free: it lies flat like Create's, and tilts again once track continues from it
			BezierConnection bc = new BezierConnection(Couple.create(pos1, endPos), Couple.create(start, end),
				Couple.create(TrackGeometry.withGrade(u, leavingGrade), TrackGeometry.withGrade(u, 0).scale(-1)),
				Couple.create(UP, UP), true, false, material);
			plan.curves.add(bc);
			plan.requiredTracks += bc.getTrackItemCost();
		}

		if (!player.isCreative() && countTracks(player, stack.getItem()) < plan.requiredTracks)
			return plan.fail(createText("track.not_enough_tracks"));
		plan.valid = true;
		plan.message = createText("track.valid_connection");
		return plan;
	}

	/**
	 * Height a straight should end at for this click:
	 * <ul>
	 * <li>with the keep-grade key: null, continue at the start's grade (on up a hill)</li>
	 * <li>with the keep-height key, or aiming into the air: the start's own height</li>
	 * <li>otherwise: the track level of the aimed block</li>
	 * </ul>
	 */
	@Nullable
	public static Double straightTargetY(Player player, @Nullable BlockHitResult hit) {
		if (SnapSettings.isKeepGrade(player))
			return null;
		ConnectingFrom from = player.getMainHandItem().get(AllDataComponents.TRACK_CONNECTING_FROM);
		if (SnapSettings.isKeepHeight(player) || hit == null || hit.getType() != HitResult.Type.BLOCK)
			return from == null ? null : from.end().y;
		Level level = player.level();
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		if (!state.canBeReplaced() && !(state.getBlock() instanceof ITrackBlock))
			pos = pos.relative(hit.getDirection());
		return (double) pos.getY();
	}

	/** Where along the started track an aim into the air points: the look ray meeting the start's height. */
	public static Vec3 airAim(Player player) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		ConnectingFrom from = player.getMainHandItem().get(AllDataComponents.TRACK_CONNECTING_FROM);
		double planeY = from != null ? from.end().y : eye.y;
		double distance = SnapSettings.getMaxLength(player);
		if (look.y < -1e-3) {
			double t = (planeY - eye.y) / look.y;
			if (t > 0)
				distance = Math.min(distance, t);
		}
		return eye.add(look.scale(distance));
	}

	// ---------------------------------------------------------------- planning

	private record Piece(Vec3 from, Vec3 to, Vec3 dirFrom, Vec3 dirTo, double length, boolean curve) {}

	/**
	 * @param existingTarget the track block at {@code pos2}, or null when a new end is placed there
	 */
	public static PlacementPlan plan(Level level, Player player, BlockPos pos2, @Nullable BlockState existingTarget,
									 ItemStack stack, boolean maximise, int step) {
		return plan(level, player, pos2, existingTarget, (LineJoin) null, stack, maximise, step);
	}

	/**
	 * Joining the middle of an angled straight (a curve stored at {@code bePos}, leading to {@code curveKey}): the
	 * turn joins the line where it fits, and the line is cut in two at a new anchor there.
	 */
	public static PlacementPlan planIntoLine(Level level, Player player, BlockPos bePos, BlockPos curveKey, Vec3 aim,
											 ItemStack stack, boolean maximise, int step) {
		LineJoin join = LineJoin.find(level, bePos, curveKey, aim);
		if (join == null)
			return new PlacementPlan().fail(ownText("not_a_line"));
		return plan(level, player, bePos, null, join, stack, maximise, step);
	}

	/**
	 * Aiming at an anchor that lies on an angled straight: join the line through it, like aiming at the line next to
	 * it. Returns null when the anchor has no straight track or no join along it works (the anchor is then joined as
	 * a track end instead).
	 */
	@Nullable
	public static PlacementPlan planIntoAnchorLine(Level level, Player player, BlockPos anchorPos, ItemStack stack,
												   boolean maximise, int step) {
		Vec3 point = AngledTrackBlock.getAnchorPoint(level, anchorPos);
		PlacementPlan refused = null;
		for (BezierConnection bc : AngledTrackBlock.connections(level, anchorPos).values()) {
			if (!TrackGeometry.isStraight(bc))
				continue;
			PlacementPlan plan = planIntoLine(level, player, anchorPos, bc.getKey(), point, stack, maximise, step);
			if (plan.valid)
				return plan;
			if (refused == null)
				refused = plan;
		}
		// a line through the anchor exists, but the turn does not fit on it: only fall back for track ends
		return refused != null && AngledTrackBlock.connections(level, anchorPos).size() > 1 ? refused : null;
	}

	/**
	 * An angled straight to join: all collinear straight pieces chained by anchors, seen as one line from
	 * {@code origin} along {@code dir}, and how far along it the player aims.
	 */
	record LineJoin(List<BezierConnection> pieces, Vec3 origin, Vec3 dir, double grade, double aimAlong,
					Set<BlockPos> lineBlocks) {
		/**
		 * A joint this close to an existing anchor uses that anchor: two joints side by side would collide, and the
		 * turn (always symmetric) only changes by this much.
		 */
		private static final double SNAP_TO_ANCHOR = 1.0;

		/** A point on the line: the piece to cut there, or the anchor that already sits there. */
		record Spot(Vec3 point, @Nullable BezierConnection pieceToSplit, @Nullable BlockPos existingAnchor) {}

		@Nullable
		static LineJoin find(Level level, BlockPos bePos, BlockPos curveKey, Vec3 aim) {
			if (!(level.getBlockEntity(bePos) instanceof TrackBlockEntity tbe)
				|| !(tbe.getConnections().get(curveKey) instanceof BezierConnection hit)
				|| !TrackGeometry.isStraight(hit))
				return null;
			Vec3 dir = TrackGeometry.horizontalDir(hit.starts.getSecond().subtract(hit.starts.getFirst()));
			double grade = gradeAlong(hit);

			// chain collinear straight pieces through anchors, each oriented along dir
			List<BezierConnection> pieces = new ArrayList<>();
			pieces.add(hit);
			for (int guard = 0; guard < 64; guard++) {
				BezierConnection last = pieces.get(pieces.size() - 1);
				BlockPos next = last.bePositions.getSecond();
				BezierConnection ahead = AngledTrackBlock.isAnchor(level.getBlockState(next))
					? straightAhead(level, next, dir) : null;
				if (ahead == null || Math.abs(gradeAlong(ahead) - grade) > 1e-3)
					break;
				pieces.add(ahead);
			}
			for (int guard = 0; guard < 64; guard++) {
				BezierConnection first = pieces.get(0);
				BlockPos previous = first.bePositions.getFirst();
				BezierConnection behind = AngledTrackBlock.isAnchor(level.getBlockState(previous))
					? straightAhead(level, previous, dir.scale(-1)) : null;
				if (behind == null)
					break;
				BezierConnection forward = behind.secondary();
				if (Math.abs(gradeAlong(forward) - grade) > 1e-3)
					break;
				pieces.add(0, forward);
			}

			Set<BlockPos> blocks = new HashSet<>();
			for (BezierConnection piece : pieces) {
				blocks.add(piece.bePositions.getFirst());
				blocks.add(piece.bePositions.getSecond());
			}
			Vec3 origin = pieces.get(0).starts.getFirst();
			double aimAlong = TrackGeometry.horizontal(aim.subtract(origin)).dot(dir);
			return new LineJoin(pieces, origin, dir, grade, aimAlong, blocks);
		}

		double length() {
			double total = 0;
			for (BezierConnection piece : pieces)
				total += pieceLength(piece);
			return total;
		}

		/** The spot this far along the line, or null when it is off the line. */
		@Nullable
		Spot spotAt(double along) {
			double offset = 0;
			for (BezierConnection piece : pieces) {
				double length = pieceLength(piece);
				if (along > offset + length + 1e-6) {
					offset += length;
					continue;
				}
				double local = along - offset;
				if (local < -1e-6)
					return null;
				if (local < SNAP_TO_ANCHOR)
					return new Spot(piece.starts.getFirst(), null, piece.bePositions.getFirst());
				if (local > length - SNAP_TO_ANCHOR)
					return new Spot(piece.starts.getSecond(), null, piece.bePositions.getSecond());
				Vec3 start = piece.starts.getFirst();
				Vec3 point = new Vec3(start.x + dir.x * local, start.y + grade * local, start.z + dir.z * local);
				return new Spot(point, piece, null);
			}
			return null;
		}

		private static double pieceLength(BezierConnection piece) {
			return TrackGeometry.horizontal(piece.starts.getSecond().subtract(piece.starts.getFirst())).length();
		}

		private static double gradeAlong(BezierConnection bc) {
			return TrackGeometry.grade(bc.starts.getSecond().subtract(bc.starts.getFirst()));
		}
	}

	private static PlacementPlan plan(Level level, Player player, BlockPos pos2, @Nullable BlockState existingTarget,
									  @Nullable LineJoin join, ItemStack stack, boolean maximise, int step) {
		PlacementPlan plan = new PlacementPlan();
		ConnectingFrom from = stack.get(AllDataComponents.TRACK_CONNECTING_FROM);
		if (from == null)
			return plan.failNeutral(createText("track.second_point"));

		TrackMaterial material = TrackMaterial.fromItem(stack.getItem());
		plan.material = material;
		BlockPos pos1 = from.pos();
		BlockState state1 = level.getBlockState(pos1);
		plan.startPos = pos1;
		plan.endPos = pos2;

		if (pos1.equals(pos2))
			return plan.failNeutral(createText("track.second_point"));
		int maxLength = SnapSettings.getMaxLength(player);
		if (pos1.distSqr(pos2) > (double) maxLength * maxLength)
			return plan.fail(createText("track.too_far"));
		if (!(state1.getBlock() instanceof ITrackBlock track1) || !state1.hasProperty(TrackBlock.HAS_BE))
			return plan.fail(createText("track.original_missing"));

		// ---- start: point S, heading u, grade g1

		Vec3 axis1 = from.axis();
		if (!AngledTrackBlock.isAnchor(state1) && Math.abs(axis1.y) > 1e-6)
			return plan.fail(ownText("slope_endpoint"));
		Vec3 start = from.end();
		Vec3 roughTarget = Vec3.atBottomCenterOf(pos2);
		if (TrackGeometry.horizontal(axis1).dot(roughTarget.subtract(start)) < 0) {
			axis1 = axis1.scale(-1);
			start = track1.getCurveStart(level, pos1, state1, axis1);
		}
		Vec3 u = TrackGeometry.horizontalDir(axis1);
		if (u == Vec3.ZERO)
			return plan.fail(createText("track.original_missing"));
		double g1 = startGrade(level, pos1, state1, u, axis1);

		// An anchor that already continues straight in this direction: start where that straight ends instead of
		// laying new track back over it (Create does the same when a connection starts mid-line).
		if (AngledTrackBlock.isAnchor(state1))
			for (int guard = 0; guard < 64; guard++) {
				BezierConnection ahead = straightAhead(level, pos1, u);
				if (ahead == null)
					break;
				pos1 = ahead.bePositions.getSecond();
				start = ahead.starts.getSecond();
				state1 = level.getBlockState(pos1);
				g1 = -TrackGeometry.grade(ahead.axes.getSecond());
				plan.startPos = pos1;
				if (!AngledTrackBlock.isAnchor(state1))
					return plan.fail(ownText("anchor_blocked"));
			}

		// ---- end: point T, arriving heading w, grade g2 (null when free)

		Vec3 end;
		Vec3 w;
		Double g2 = null;
		BlockState anchorState = RUBlocks.ANGLED_TRACK.get().defaultBlockState();
		boolean endIsCreateTrack;

		Set<BlockPos> used = new HashSet<>();
		used.add(pos1);
		used.add(pos2);

		if (join != null) {
			// Like Create joining a straight line: the turn is the smallest that fits (with Sprint the widest, up to
			// the aimed point), symmetric, and it ends where it meets the line. The aim only picks the line and,
			// with Sprint, how far along it the turn may reach.
			Vec3 d = join.dir();
			boolean forward = u.dot(d) >= 0;
			w = forward ? d : d.scale(-1);
			double sign = forward ? 1 : -1;
			Vec3 r = TrackGeometry.horizontal(join.origin().subtract(start));
			double det = u.x * d.z - u.z * d.x;
			if (Math.abs(det) < 1e-6)
				return plan.fail(createText("track.too_sharp"));
			double a = (r.x * d.z - r.z * d.x) / det;
			double meetAlong = -(u.x * r.z - r.x * u.z) / det;
			double theta = Math.acos(Math.max(-1, Math.min(1, u.dot(w))));
			if (Math.toDegrees(theta) > 90.5)
				return plan.fail(createText("track.turn_90"));
			double minTangent = Math.max(7 * Math.tan(theta / 2), 3.25);
			double aimTangent = (join.aimAlong() - meetAlong) * sign;
			double tangent = maximise ? Math.min(a, aimTangent) : minTangent;
			plan.debug = String.format("line from %s dir %s start=%s u=%s a=%.2f meet=%.2f aim=%.2f/%.2f turn=%.1f "
				+ "tangent=%.2f", join.origin(), d, start, u, a, meetAlong, join.aimAlong(), join.length(),
				Math.toDegrees(theta), tangent);
			if (a < minTangent - 1e-6 || aimTangent < minTangent - 1e-6 || tangent < minTangent - 1e-6)
				return plan.fail(createText("track.too_sharp"));
			used.addAll(join.lineBlocks());

			// Both joints of the turn need free spots (or exact existing anchors). Where the ideal one is taken, the
			// turn grows (without Sprint) or shrinks (with Sprint) in half-block steps; it always stays symmetric.
			Vec3 sh0 = TrackGeometry.horizontal(start);
			Vec3 meet = sh0.add(u.scale(a));
			LineJoin.Spot spot = join.spotAt(meetAlong + sign * tangent);
			for (int k = 0; k <= 48; k++) {
				double t = maximise ? tangent - 0.5 * k : tangent + 0.5 * k;
				if (t < minTangent - 1e-6 || t > a + 1e-6)
					break;
				LineJoin.Spot candidate = join.spotAt(meetAlong + sign * t);
				if (candidate == null)
					continue;
				double effective = TrackGeometry.horizontal(candidate.point()).subtract(meet).dot(w);
				double startTangent = Math.min(a, effective);
				boolean startOk = a - startTangent < 1
					|| jointUsable(level, sh0.add(u.scale(a - startTangent)), start.y, used);
				boolean endOk = candidate.existingAnchor() != null
					|| jointUsable(level, TrackGeometry.horizontal(candidate.point()), candidate.point().y, used);
				if (startOk && endOk) {
					spot = candidate;
					break;
				}
			}
			if (spot == null)
				return plan.fail(ownText("line_too_short"));

			end = spot.point();
			g2 = forward ? join.grade() : -join.grade();
			endIsCreateTrack = false;
			if (spot.existingAnchor() != null) {
				pos2 = spot.existingAnchor();
			} else {
				BezierConnection piece = spot.pieceToSplit();
				if (!jointUsable(level, TrackGeometry.horizontal(end), end.y, used)) {
					plan.debug += " | line joint at " + end + " is blocked";
					return plan.fail(ownText("anchor_blocked"));
				}
				pos2 = jointBlock(TrackGeometry.horizontal(end), end.y);
				used.add(pos2);
				plan.newBlocks.add(new PlacementPlan.NewBlock(pos2, anchorState));
				plan.anchorPoints.add(end);

				// the outer ends keep their tangents (a free end stays flat)
				Vec3 axis = TrackGeometry.withGrade(d, join.grade());
				BezierConnection firstHalf = new BezierConnection(Couple.create(piece.bePositions.getFirst(), pos2),
					Couple.create(piece.starts.getFirst(), end), Couple.create(piece.axes.getFirst(), axis.scale(-1)),
					piece.normals.copy(), true, piece.hasGirder, piece.getMaterial());
				BezierConnection secondHalf = new BezierConnection(Couple.create(pos2, piece.bePositions.getSecond()),
					Couple.create(end, piece.starts.getSecond()), Couple.create(axis, piece.axes.getSecond()),
					piece.normals.copy(), true, piece.hasGirder, piece.getMaterial());
				plan.split = new PlacementPlan.Split(piece, firstHalf, secondHalf);
			}
		} else if (existingTarget != null) {
			if (!(existingTarget.getBlock() instanceof ITrackBlock track2)
				|| !existingTarget.hasProperty(TrackBlock.HAS_BE))
				return plan.fail(createText("track.original_missing"));
			if (level.getBlockEntity(pos2) instanceof TrackBlockEntity tbe && tbe.isTilted())
				return plan.fail(createText("track.turn_start"));
			boolean endAnchor = AngledTrackBlock.isAnchor(existingTarget);
			endIsCreateTrack = !endAnchor;
			Vec3 axis2 = track2.getNearestTrackAxis(level, pos2, existingTarget, player.getLookAngle()).getFirst();
			if (!endAnchor && Math.abs(axis2.y) > 1e-6)
				return plan.fail(ownText("slope_endpoint"));
			// a2 leaves the end towards the new track. Like Create, aim it at the point where the new track meets the
			// target's line, so clicking anywhere on a straight line joins it where the turn fits.
			Vec3 a2 = axis2;
			Vec3 center2 = Vec3.atBottomCenterOf(pos2);
			Vec3 lineDir = TrackGeometry.horizontalDir(axis2);
			double det = u.x * lineDir.z - u.z * lineDir.x;
			Vec3 towards = start.subtract(center2);
			if (Math.abs(det) > 1e-6) {
				Vec3 r = TrackGeometry.horizontal(center2.subtract(start));
				double a = (r.x * lineDir.z - r.z * lineDir.x) / det;
				towards = start.add(u.scale(a)).subtract(center2);
			}
			if (TrackGeometry.horizontal(a2).dot(towards) < 0)
				a2 = a2.scale(-1);
			end = track2.getCurveStart(level, pos2, existingTarget, a2);
			w = TrackGeometry.horizontalDir(a2).scale(-1);
			g2 = -TrackGeometry.grade(a2);
			if (w == Vec3.ZERO)
				return plan.fail(createText("track.original_missing"));
		} else {
			BlockState there = level.getBlockState(pos2);
			if (!there.canBeReplaced())
				return plan.fail(ownText("blocked"));
			double yaw = snappedLookYaw(player, step);
			w = TrackGeometry.fromYaw(yaw);
			Vec3 center2 = Vec3.atBottomCenterOf(pos2);
			if (w.dot(TrackGeometry.horizontal(center2.subtract(start))) < 0) {
				w = w.scale(-1);
				yaw += 180;
			}
			// with the height or grade kept, the end is placed at a computed height: always an anchor
			boolean keepsLevel = SnapSettings.isKeepHeight(player) || SnapSettings.isKeepGrade(player);
			if (!keepsLevel && Math.floorMod(Math.round(yaw), 45) == 0) {
				// Create can express this end with one of its own track blocks
				Vec3 gridAxis = new Vec3(Math.round(w.x), 0, Math.round(w.z));
				w = gridAxis.normalize();
				end = center2.add(gridAxis.scale(-0.5));
				g2 = 0d;
				plan.newBlocks.add(new PlacementPlan.NewBlock(pos2, createTrackState(material, gridAxis)));
				plan.requiredTracks++;
				endIsCreateTrack = true;
			} else {
				endIsCreateTrack = false;
				end = center2;
				plan.newBlocks.add(new PlacementPlan.NewBlock(pos2, anchorState));
				plan.anchorPoints.add(end);
			}
		}
		plan.endDirection = w;

		// ---- horizontal layout

		List<Piece> pieces = new ArrayList<>();
		Vec3 sh = TrackGeometry.horizontal(start);
		Vec3 th = TrackGeometry.horizontal(end);
		Vec3 r = th.subtract(sh);
		double cross = u.x * w.z - u.z * w.x;
		double dot = u.dot(w);

		if (Math.abs(cross) < 1e-6) {
			if (dot < 0)
				return plan.fail(ownText("opposing"));
			double forward = r.dot(u);
			double lateral = r.subtract(u.scale(forward)).length();
			if (forward <= 1e-3)
				return plan.fail(createText("track.too_sharp"));
			if (lateral < 1e-3) {
				pieces.add(new Piece(sh, th, u, u, forward, false));
			} else {
				double needed = lateral <= 1 ? 3 : lateral * 2;
				if (forward < needed - 1e-6)
					return plan.fail(createText("track.too_sharp"));
				double straight = maximise ? 0 : (forward - needed) / 2;
				if (straight < 1)
					straight = 0;
				// joining a line: the S-bend ends exactly at the joint, any straight goes before it
				double before = join == null ? straight : maximise ? 0 : Math.max(0, forward - needed);
				if (before < 1)
					before = 0;
				Vec3 p1 = sh.add(u.scale(before));
				Vec3 p2 = join == null ? th.subtract(w.scale(straight)) : th;
				addStraight(pieces, sh, p1, u);
				pieces.add(curvePiece(p1, p2, u, w, material));
				addStraight(pieces, p2, th, w);
			}
		} else {
			double a = (r.x * w.z - r.z * w.x) / cross;
			double b = (u.x * r.z - u.z * r.x) / cross;
			double theta = Math.acos(Math.max(-1, Math.min(1, dot)));
			if (Math.toDegrees(theta) > 90.5)
				return plan.fail(createText("track.turn_90"));
			double minTangent = Math.max(7 * Math.tan(theta / 2), 3.25);

			plan.debug = (plan.debug.isEmpty() ? "" : plan.debug + " | ") + String.format("start=%s u=%s end=%s w=%s a=%.2f b=%.2f turn=%.1f minTangent=%.2f", start,
				u, end, w, a, b, Math.toDegrees(theta), minTangent);
			if (a < minTangent - 1e-6 || b < minTangent - 1e-6)
				return plan.fail(createText("track.too_sharp"));
			double tangent = maximise ? Math.min(a, b) : minTangent;
			// Joining a line: the turn ends exactly at the joint and is always symmetric (round); any straight goes
			// before it on the start side.
			double firstTangent = join == null ? tangent : Math.min(a, b);
			boolean startIsAnchor = AngledTrackBlock.isAnchor(state1);
			// The joints between the turn and the straights need free spots. Where the ideal spot is taken, a
			// slightly longer tangent on either side moves the joint (the turn gets a little wider there).
			double t1 = a - firstTangent < 1 ? a : firstTangent;
			double t2 = join != null || b - tangent < 1 ? b : tangent;
			// (joining a line keeps the turn symmetric: an uneven turn would swing out into an S)
			search:
			for (int k = 0; k <= (join != null ? 0 : 24); k++) {
				double extra = k * 0.5;
				double[][] options = k == 0 ? new double[][] {{0, 0}}
					: new double[][] {{extra, 0}, {0, extra}, {extra, extra}};
				for (double[] option : options) {
					double c1 = firstTangent + option[0];
					double c2 = join != null ? b : tangent + option[1];
					if (c1 > a + 1e-6 || c2 > b + 1e-6)
						continue;
					c1 = a - c1 < 1 ? a : c1;
					c2 = b - c2 < 1 ? b : c2;
					boolean firstOk = !startIsAnchor || a - c1 < 1e-3
						|| jointUsable(level, sh.add(u.scale(a - c1)), start.y, used);
					boolean secondOk = endIsCreateTrack || b - c2 < 1e-3
						|| jointUsable(level, th.subtract(w.scale(b - c2)), end.y, used);
					if (firstOk && secondOk) {
						t1 = c1;
						t2 = c2;
						break search;
					}
				}
			}
			Vec3 p1 = sh.add(u.scale(a - t1));
			Vec3 p2 = th.subtract(w.scale(b - t2));
			addStraight(pieces, sh, p1, u);
			pieces.add(curvePiece(p1, p2, u, w, material));
			addStraight(pieces, p2, th, w);
		}

		// ---- a straight that would run along an existing angled line up to one of its anchors joins at that anchor

		if (!endIsCreateTrack && pieces.size() > 1 && !pieces.get(pieces.size() - 1).curve) {
			Piece last = pieces.get(pieces.size() - 1);
			BlockPos existing = existingAnchorAt(level, last.from, end.y);
			if (existing != null) {
				pieces.remove(pieces.size() - 1);
				pos2 = existing;
				end = AngledTrackBlock.getAnchorPoint(level, existing);
				used.add(existing);
				plan.newBlocks.removeIf(block -> AngledTrackBlock.isAnchor(block.state()));
				plan.anchorPoints.clear();
			}
		}
		if (AngledTrackBlock.isAnchor(state1) && pieces.size() > 1 && !pieces.get(0).curve) {
			Piece first = pieces.get(0);
			BlockPos existing = existingAnchorAt(level, first.to, start.y);
			if (existing != null) {
				pieces.remove(0);
				pos1 = existing;
				start = AngledTrackBlock.getAnchorPoint(level, existing);
				used.add(existing);
			}
		}

		// ---- straights next to Create track are built from Create track blocks, like Create does itself.
		// This also lets a turn join an existing straight line: the blocks merge with the track already there.

		BlockPos startBe = pos1;
		BlockPos endBe = pos2;
		if (!AngledTrackBlock.isAnchor(state1) && pieces.size() > 1 && !pieces.get(0).curve) {
			Vec3 grid = gridVector(u);
			Piece straight = pieces.get(0);
			int blocks = (int) Math.floor(straight.length / grid.length() + 1e-6);
			if (blocks > 0) {
				for (int i = 1; i <= blocks; i++)
					if (!addCreateTrack(level, pos1.offset(gridOffset(grid, i)), createTrackState(material, grid),
						plan, used))
						return plan.fail(ownText("anchor_blocked"));
				startBe = pos1.offset(gridOffset(grid, blocks));
				start = start.add(grid.scale(blocks));
				pieces.remove(0);
				shortenFirst(pieces, TrackGeometry.horizontal(start), straight, u, material);
			}
		}
		if (endIsCreateTrack && pieces.size() > 1 && !pieces.get(pieces.size() - 1).curve) {
			Vec3 grid = gridVector(w.scale(-1));
			Piece straight = pieces.get(pieces.size() - 1);
			int blocks = (int) Math.floor(straight.length / grid.length() + 1e-6);
			if (blocks > 0) {
				for (int i = 1; i <= blocks; i++)
					if (!addCreateTrack(level, pos2.offset(gridOffset(grid, i)), createTrackState(material, grid),
						plan, used))
						return plan.fail(ownText("anchor_blocked"));
				endBe = pos2.offset(gridOffset(grid, blocks));
				end = end.add(grid.scale(blocks));
				pieces.remove(pieces.size() - 1);
				shortenLast(pieces, TrackGeometry.horizontal(end), straight, w, material);
			}
		}
		// ---- kept height or grade: the new end's height follows from the start, not from the clicked block

		boolean keepHeight = SnapSettings.isKeepHeight(player);
		boolean keepGrade = SnapSettings.isKeepGrade(player);
		if (existingTarget == null && join == null && (keepHeight || keepGrade)) {
			double run = 0;
			for (Piece piece : pieces)
				run += piece.length;
			double y = keepHeight ? start.y : start.y + g1 * run;
			BlockPos keptPos = jointBlock(TrackGeometry.horizontal(end), y);
			BlockPos clickedPos = pos2;
			Vec3 clickedEnd = end;
			if (!keptPos.equals(clickedPos)) {
				BlockState there = level.getBlockState(keptPos);
				if (!there.canBeReplaced() && !AllBlocks.FAKE_TRACK.has(there) || !used.add(keptPos)) {
					plan.debug += " | kept-height end at " + keptPos + " is blocked";
					return plan.fail(ownText("blocked"));
				}
				plan.newBlocks.removeIf(block -> block.pos().equals(clickedPos));
				plan.newBlocks.add(new PlacementPlan.NewBlock(keptPos, anchorState));
			}
			plan.anchorPoints.removeIf(point -> point.equals(clickedEnd));
			end = new Vec3(end.x, y, end.z);
			plan.anchorPoints.add(end);
			if (nodeTaken(level, end))
				return plan.fail(ownText("anchor_blocked"));
			pos2 = keptPos;
			endBe = keptPos;
		}

		plan.startPos = startBe;
		plan.endPos = endBe;

		// ---- heights: one grade g in the middle, easing from g1 (and into g2 when the end is fixed)

		// a new free end lies flat like Create's free slope ends; it tilts again once track continues from it
		if (g2 == null)
			g2 = 0d;
		int n = pieces.size();
		double[] grades = new double[n + 1];
		// a Create block's own track is flat: leave it flat and let Create tilt the block to the average grade of
		// the slopes on both sides (tryApplySmoothing in execute), else that tilt would come on top of g1
		double rise = end.y - start.y;
		double totalRun = 0;
		for (Piece piece : pieces)
			totalRun += piece.length;
		grades[0] = leavingGrade(level, pos1, state1, u, axis1, g1, totalRun < 1e-6 ? 0 : rise / totalRun);
		if (n == 1) {
			grades[1] = g2 != null ? g2 : rise / pieces.get(0).length;
		} else {
			double first = pieces.get(0).length;
			double last = pieces.get(n - 1).length;
			double middle = 0;
			for (int i = 1; i < n - 1; i++)
				middle += pieces.get(i).length;
			double constant = first * grades[0] / 2 + (g2 != null ? last * g2 / 2 : 0);
			double perGrade = first / 2 + middle + (g2 != null ? last / 2 : last);
			double g = (rise - constant) / perGrade;
			for (int i = 1; i < n; i++)
				grades[i] = g;
			grades[n] = g2 != null ? g2 : g;
		}
		// like Create, the limit is on the average grade; easing in and out (flat free ends) makes the middle
		// steeper, as in Create's own slopes, which is allowed up to twice the limit
		if (totalRun > 1e-6 && Math.abs(rise / totalRun) > MAX_GRADE + 1e-6)
			return plan.fail(createText("track.too_steep"));
		for (double grade : grades)
			if (Math.abs(grade) > 2 * MAX_GRADE + 1e-6)
				return plan.fail(createText("track.too_steep"));
		// the grade it is built with: its average, which is also what keeping the grade continues with
		plan.grade = totalRun < 1e-6 ? 0 : rise / totalRun;
		if (AngledTrackBlock.isAnchor(state1))
			plan.retangent = retangentBehind(level, pos1, u, grades[0]);

		List<Vec3> points = new ArrayList<>(n + 1);
		points.add(start);
		double y = start.y;
		for (int i = 0; i < n; i++) {
			Piece piece = pieces.get(i);
			y += piece.length * (grades[i] + grades[i + 1]) / 2;
			points.add(i == n - 1 ? end : new Vec3(piece.to.x, y, piece.to.z));
		}

		// ---- anchors between the pieces

		List<BlockPos> bePositions = new ArrayList<>(n + 1);
		bePositions.add(startBe);
		for (int i = 1; i < n; i++) {
			Vec3 point = points.get(i);
			BlockPos anchorPos = BlockPos.containing(point.x, Math.floor(point.y + 1e-6), point.z);
			// a joint exactly on an existing anchor (e.g. from an earlier placement from the same start) reuses it
			BlockPos existing = existingAnchorAt(level, TrackGeometry.horizontal(point), point.y);
			if (existing != null && used.add(existing)) {
				Vec3 exact = AngledTrackBlock.getAnchorPoint(level, existing);
				points.set(i, exact);
				bePositions.add(existing);
				continue;
			}
			String blocked = null;
			BlockState there = level.getBlockState(anchorPos);
			if (!used.add(anchorPos))
				blocked = "block already used by this placement";
			else if (!there.canBeReplaced() && !AllBlocks.FAKE_TRACK.has(there))
				blocked = "occupied by " + there;
			else if (nodeTaken(level, point))
				blocked = "track graph node already there";
			if (blocked != null) {
				plan.debug += String.format(" | joint %d at %s (%s): %s", i, anchorPos, point, blocked);
				return plan.fail(ownText("anchor_blocked"));
			}
			plan.newBlocks.add(new PlacementPlan.NewBlock(anchorPos, anchorState));
			plan.anchorPoints.add(point);
			bePositions.add(anchorPos);
		}
		bePositions.add(endBe);
		if (existingTarget == null && plan.anchorPoints.contains(end) && nodeTaken(level, end))
			return plan.fail(ownText("anchor_blocked"));

		// ---- the curves themselves

		for (int i = 0; i < n; i++) {
			Piece piece = pieces.get(i);
			Vec3 axisFrom = TrackGeometry.withGrade(piece.dirFrom, grades[i]);
			Vec3 axisTo = TrackGeometry.withGrade(piece.dirTo, grades[i + 1]).scale(-1);
			BezierConnection bc = new BezierConnection(Couple.create(bePositions.get(i), bePositions.get(i + 1)),
				Couple.create(points.get(i), points.get(i + 1)), Couple.create(axisFrom, axisTo),
				Couple.create(UP, UP), true, false, material);
			plan.curves.add(bc);
			plan.requiredTracks += bc.getTrackItemCost();
		}

		if (!player.isCreative() && countTracks(player, stack.getItem()) < plan.requiredTracks)
			return plan.fail(createText("track.not_enough_tracks"));

		plan.valid = true;
		plan.message = createText("track.valid_connection");
		return plan;
	}

	/**
	 * The grade new track continues with from a start point heading {@code u}: the grade the piece arriving from
	 * behind was built with, i.e. its average rise over run. Its end tangent does not tell: free ends lie flat (like
	 * Create's slopes), and Create's slopes ease in and out. Without such a piece, the selected axis' grade.
	 */
	private static double startGrade(Level level, BlockPos pos, BlockState state, Vec3 u, Vec3 axis) {
		BezierConnection behind = behindPiece(level, pos, u);
		if (behind != null) {
			double run = horizontalLength(behind);
			if (run > 1e-3)
				return (behind.starts.getFirst().y - behind.starts.getSecond().y) / run;
		}
		return TrackGeometry.grade(axis);
	}

	/**
	 * The grade new track leaves its start with.
	 * <ul>
	 * <li>A Create block: its own (flat) track; Create tilts the block afterwards if slopes meet there.</li>
	 * <li>An anchor inside existing track: the tangent that track already has there.</li>
	 * <li>A free anchor end (lying flat): tilted only as far as the hill really goes on. Up into a gentler climb, the
	 * gentler grade; into level track, or over a crest or through a dip, it stays flat, so the hill keeps its eased
	 * end.</li>
	 * </ul>
	 *
	 * @param built    the grade the piece behind was built with
	 * @param newGrade the average grade of the new track
	 */
	private static double leavingGrade(Level level, BlockPos pos, BlockState state, Vec3 u, Vec3 axis, double built,
									   double newGrade) {
		if (!AngledTrackBlock.isAnchor(state))
			return TrackGeometry.grade(axis);
		BezierConnection behind = behindPiece(level, pos, u);
		if (behind == null)
			return built;
		if (AngledTrackBlock.connections(level, pos).size() != 1)
			return -TrackGeometry.grade(behind.axes.getFirst());
		if (built * newGrade <= 0)
			return 0;
		return Math.signum(built) * Math.min(Math.abs(built), Math.abs(newGrade));
	}

	/** The curve at {@code pos} arriving from behind a start heading {@code u}, if there is one. */
	@Nullable
	private static BezierConnection behindPiece(Level level, BlockPos pos, Vec3 u) {
		if (!(level.getBlockEntity(pos) instanceof TrackBlockEntity tbe))
			return null;
		BezierConnection behind = null;
		double best = -0.999;
		for (BezierConnection bc : tbe.getConnections().values()) {
			double alignment = TrackGeometry.horizontalDir(bc.axes.getFirst()).dot(u);
			if (alignment < best) {
				best = alignment;
				behind = bc;
			}
		}
		return behind;
	}

	/** Length of a curve seen from above (a fresh copy: Create does not refresh a tilted curve's cached shape). */
	private static double horizontalLength(BezierConnection bc) {
		if (TrackGeometry.isStraight(bc))
			return TrackGeometry.horizontal(bc.starts.getSecond().subtract(bc.starts.getFirst())).length();
		BezierConnection fresh = bc.clone();
		double length = 0;
		Vec3 previous = fresh.getPosition(0);
		for (int i = 1; i <= 32; i++) {
			Vec3 next = fresh.getPosition(i / 32.0);
			length += TrackGeometry.horizontal(next.subtract(previous)).length();
			previous = next;
		}
		return length;
	}

	/**
	 * When new track continues from a free anchor end (lying flat, like Create's free slope ends), the piece arriving
	 * there is tilted to the grade the new track leaves with, so the hill runs on through the joint.
	 */
	@Nullable
	private static PlacementPlan.Retangent retangentBehind(Level level, BlockPos anchor, Vec3 u, double leaving) {
		if (!AngledTrackBlock.isAnchor(level.getBlockState(anchor)))
			return null;
		if (AngledTrackBlock.connections(level, anchor).size() != 1)
			return null;
		BezierConnection behind = behindPiece(level, anchor, u);
		if (behind == null || Math.abs(-TrackGeometry.grade(behind.axes.getFirst()) - leaving) < 1e-6)
			return null;
		Vec3 back = TrackGeometry.horizontalDir(behind.axes.getFirst());
		return new PlacementPlan.Retangent(anchor, behind, TrackGeometry.withGrade(back, -leaving));
	}

	/** A straight curve leaving this anchor exactly in direction {@code dir}, if there is one. */
	@Nullable
	private static BezierConnection straightAhead(Level level, BlockPos anchor, Vec3 dir) {
		for (BezierConnection bc : AngledTrackBlock.connections(level, anchor).values())
			if (TrackGeometry.isStraight(bc) && TrackGeometry.horizontalDir(bc.axes.getFirst()).dot(dir) > 0.9999)
				return bc;
		return null;
	}

	/** The block a joint at this horizontal point and height would occupy. */
	private static BlockPos jointBlock(Vec3 horizontalPoint, double y) {
		return BlockPos.containing(horizontalPoint.x, Math.floor(y + 1e-6), horizontalPoint.z);
	}

	/** An existing anchor whose point is exactly this joint, which a new straight along its line can reuse. */
	@Nullable
	private static BlockPos existingAnchorAt(Level level, Vec3 horizontalPoint, double y) {
		BlockPos pos = jointBlock(horizontalPoint, y);
		if (!AngledTrackBlock.isAnchor(level.getBlockState(pos)))
			return null;
		Vec3 point = TrackGeometry.horizontal(AngledTrackBlock.getAnchorPoint(level, pos));
		return point.distanceTo(horizontalPoint) < 0.05 ? pos : null;
	}

	/** Whether a new anchor can go at this joint, or an existing anchor sits exactly there. */
	private static boolean jointUsable(Level level, Vec3 horizontalPoint, double y, Set<BlockPos> used) {
		if (existingAnchorAt(level, horizontalPoint, y) != null)
			return true;
		BlockPos pos = jointBlock(horizontalPoint, y);
		if (used.contains(pos))
			return false;
		BlockState there = level.getBlockState(pos);
		if (!there.canBeReplaced() && !AllBlocks.FAKE_TRACK.has(there))
			return false;
		return !nodeTaken(level, new Vec3(horizontalPoint.x, y, horizontalPoint.z));
	}

	private static void addStraight(List<Piece> pieces, Vec3 from, Vec3 to, Vec3 dir) {
		double length = to.subtract(from).length();
		if (length > 1e-3)
			pieces.add(new Piece(from, to, dir, dir, length, false));
	}

	private static Piece curvePiece(Vec3 from, Vec3 to, Vec3 dirFrom, Vec3 dirTo, TrackMaterial material) {
		return new Piece(from, to, dirFrom, dirTo, curveLength(from, to, dirFrom, dirTo, material), true);
	}

	/** After the first straight was replaced by blocks up to {@code newStart}, the leftover joins the next piece. */
	private static void shortenFirst(List<Piece> pieces, Vec3 newStart, Piece removed, Vec3 dir,
									 TrackMaterial material) {
		double rest = removed.to.subtract(newStart).length();
		if (rest < 1e-3)
			return;
		Piece next = pieces.get(0);
		if (next.curve)
			pieces.set(0, curvePiece(newStart, next.to, next.dirFrom, next.dirTo, material));
		else
			pieces.add(0, new Piece(newStart, removed.to, dir, dir, rest, false));
	}

	private static void shortenLast(List<Piece> pieces, Vec3 newEnd, Piece removed, Vec3 dir,
									TrackMaterial material) {
		double rest = newEnd.subtract(removed.from).length();
		if (rest < 1e-3)
			return;
		int last = pieces.size() - 1;
		Piece previous = pieces.get(last);
		if (previous.curve)
			pieces.set(last, curvePiece(previous.from, newEnd, previous.dirFrom, previous.dirTo, material));
		else
			pieces.add(new Piece(removed.from, newEnd, dir, dir, rest, false));
	}

	/** Create's block step for a heading of 0 or 45 degrees: length 1 or the square root of 2. */
	private static Vec3 gridVector(Vec3 dir) {
		return new Vec3(Math.round(dir.x), 0, Math.round(dir.z));
	}

	private static BlockPos gridOffset(Vec3 grid, int times) {
		return BlockPos.containing(grid.x * times, 0, grid.z * times);
	}

	private static BlockState createTrackState(TrackMaterial material, Vec3 gridAxis) {
		return material.getBlock()
			.defaultBlockState()
			.setValue(TrackBlock.SHAPE, shapeFor(gridAxis))
			.setValue(TrackBlock.HAS_BE, false);
	}

	/**
	 * Places one of Create's own track blocks, merging with track that is already there the way Create does.
	 * @return false when the position is blocked
	 */
	private static boolean addCreateTrack(Level level, BlockPos pos, BlockState state, PlacementPlan plan,
										  Set<BlockPos> used) {
		BlockState existing = level.getBlockState(pos);
		if (!used.add(pos)) {
			plan.debug += " | Create track at " + pos + ": block already used by this placement";
			return false;
		}
		if (AngledTrackBlock.isAnchor(existing)) {
			plan.debug += " | Create track at " + pos + ": an angled track anchor is there";
			return false;
		}
		if (existing.getBlock() instanceof ITrackBlock track) {
			if (existing.getBlock() == state.getBlock() && existing.hasProperty(TrackBlock.SHAPE)
				&& existing.getValue(TrackBlock.SHAPE) == state.getValue(TrackBlock.SHAPE))
				return true;
			BlockState merged = track.overlay(level, pos, existing, state);
			if (merged != existing)
				plan.newBlocks.add(new PlacementPlan.NewBlock(pos, merged));
			return true;
		}
		if (!existing.canBeReplaced() && !AllBlocks.FAKE_TRACK.has(existing)) {
			plan.debug += " | Create track at " + pos + ": occupied by " + existing;
			return false;
		}
		plan.newBlocks.add(new PlacementPlan.NewBlock(pos, state));
		plan.requiredTracks++;
		return true;
	}

	private static double curveLength(Vec3 from, Vec3 to, Vec3 dirFrom, Vec3 dirTo, TrackMaterial material) {
		BezierConnection flat = new BezierConnection(Couple.create(BlockPos.ZERO, BlockPos.ZERO),
			Couple.create(from, to), Couple.create(dirFrom, dirTo.scale(-1)), Couple.create(UP, UP), true, false,
			material);
		return flat.getLength();
	}

	private static TrackShape shapeFor(Vec3 gridAxis) {
		if (gridAxis.x == 0)
			return TrackShape.ZO;
		if (gridAxis.z == 0)
			return TrackShape.XO;
		return gridAxis.x * gridAxis.z > 0 ? TrackShape.PD : TrackShape.ND;
	}

	/**
	 * Whether a graph node of real track already sits where a new joint would go. Checked on both sides, so previews
	 * agree. Nodes no track holds any more are ignored (and removed on the server).
	 */
	private static boolean nodeTaken(Level level, Vec3 point) {
		return TrackGraphCleanup.purgeStale(level, point);
	}

	// ---------------------------------------------------------------- placing

	public static void execute(Level level, PlacementPlan plan) {
		if (plan.retangent != null)
			retangent(level, plan.retangent);
		for (PlacementPlan.NewBlock block : plan.newBlocks)
			level.setBlock(block.pos(), ProperWaterloggedBlock.withWater(level, block.state(), block.pos()),
				Block.UPDATE_ALL);

		// the blocks the curves attach to need Create's track block entity
		if (!plan.curves.isEmpty())
			for (BlockPos pos : List.of(plan.startPos, plan.endPos)) {
				BlockState state = level.getBlockState(pos);
				if (state.hasProperty(TrackBlock.HAS_BE) && !state.getValue(TrackBlock.HAS_BE))
					level.setBlock(pos, state.setValue(TrackBlock.HAS_BE, true), Block.UPDATE_ALL);
			}

		if (plan.split != null)
			splitLine(level, plan.split);

		for (BezierConnection bc : plan.curves) {
			if (!(level.getBlockEntity(bc.bePositions.getFirst()) instanceof TrackBlockEntity first)
				|| !(level.getBlockEntity(bc.bePositions.getSecond()) instanceof TrackBlockEntity second)) {
				RailwaysUnlimited.LOGGER.warn("Missing track block entity while placing angled track at {}",
					bc.bePositions);
				continue;
			}
			first.addConnection(bc);
			second.addConnection(bc.secondary());
		}

		// like Create: a Create track block between two slopes is tilted to their average grade, so the hill runs
		// through it instead of flattening out (anchors carry the grade in their curves already)
		for (BezierConnection bc : plan.curves)
			for (BlockPos pos : List.of(bc.bePositions.getFirst(), bc.bePositions.getSecond()))
				if (!AngledTrackBlock.isAnchor(level.getBlockState(pos))
					&& level.getBlockEntity(pos) instanceof TrackBlockEntity tbe)
					tbe.tilt.tryApplySmoothing();
	}

	/**
	 * After a curve left this anchor: if one curve remains, the anchor is a free end again and that curve lies flat
	 * there, like Create's free slope ends (the reverse of tilting it when track continued from here).
	 */
	public static void flattenIfFreeEnd(Level level, BlockPos anchor) {
		if (level.isClientSide || !AngledTrackBlock.isAnchor(level.getBlockState(anchor)))
			return;
		var connections = AngledTrackBlock.connections(level, anchor);
		if (connections.size() != 1)
			return;
		BezierConnection remaining = connections.values().iterator().next();
		Vec3 axis = remaining.axes.getFirst();
		if (Math.abs(TrackGeometry.grade(axis)) < 1e-9)
			return;
		retangent(level, new PlacementPlan.Retangent(anchor, remaining,
			TrackGeometry.withGrade(TrackGeometry.horizontalDir(axis), 0)));
	}

	/** Gives the curve ending at a free anchor a new tangent there (in both block entities holding it). */
	private static void retangent(Level level, PlacementPlan.Retangent change) {
		BezierConnection old = change.curve();
		BlockPos otherPos = old.bePositions.getSecond();
		if (!(level.getBlockEntity(change.anchor()) instanceof TrackBlockEntity anchor)
			|| !(level.getBlockEntity(otherPos) instanceof TrackBlockEntity other)
			|| !anchor.getConnections().containsKey(otherPos))
			return;
		BezierConnection tilted = new BezierConnection(old.bePositions,
			Couple.create(old.starts.getFirst(), old.starts.getSecond()),
			Couple.create(change.axisAtAnchor(), old.axes.getSecond()),
			Couple.create(old.normals.getFirst(), old.normals.getSecond()), old.isPrimary(), old.hasGirder,
			old.getMaterial());
		if (old.smoothing != null)
			tilted.smoothing = old.smoothing.copy();
		// replacing by key keeps both ends holding track all the time (an anchor without track removes itself)
		anchor.addConnection(tilted);
		other.addConnection(tilted.secondary());
		TrackPropagator.onRailAdded(level, change.anchor(), level.getBlockState(change.anchor()));
	}

	/**
	 * Replaces a straight curve by two halves meeting at the new anchor. The halves are added before the original is
	 * removed, so neither end is ever left without track (an anchor without track removes itself).
	 */
	private static void splitLine(Level level, PlacementPlan.Split split) {
		BlockPos fromPos = split.original().bePositions.getFirst();
		BlockPos toPos = split.original().bePositions.getSecond();
		BlockPos middlePos = split.firstHalf().bePositions.getSecond();
		if (!(level.getBlockEntity(fromPos) instanceof TrackBlockEntity from)
			|| !(level.getBlockEntity(toPos) instanceof TrackBlockEntity to)
			|| !(level.getBlockEntity(middlePos) instanceof TrackBlockEntity middle)) {
			RailwaysUnlimited.LOGGER.warn("Could not split angled track between {} and {}", fromPos, toPos);
			return;
		}
		from.addConnection(split.firstHalf());
		middle.addConnection(split.firstHalf().secondary());
		middle.addConnection(split.secondHalf());
		to.addConnection(split.secondHalf().secondary());
		from.removeConnection(toPos);
		to.removeConnection(fromPos);
		// removing the original also cleared the helper blocks Create keeps along curves; restore them for the halves
		from.manageFakeTracksAlong(split.firstHalf(), false);
		middle.manageFakeTracksAlong(split.secondHalf(), false);
	}

	// ---------------------------------------------------------------- inventory

	public static int countTracks(Player player, Item item) {
		Inventory inv = player.getInventory();
		int count = 0;
		for (ItemStack stack : inv.items)
			if (stack.is(item))
				count += stack.getCount();
		for (ItemStack stack : inv.offhand)
			if (stack.is(item))
				count += stack.getCount();
		return count;
	}

	/** Takes tracks from the rest of the inventory first so the held stack stays in hand as long as possible. */
	private static boolean consumeTracks(Player player, Item item, int amount) {
		if (countTracks(player, item) < amount)
			return false;
		Inventory inv = player.getInventory();
		List<ItemStack> order = new ArrayList<>();
		for (int i = 0; i < inv.items.size(); i++)
			if (i != inv.selected)
				order.add(inv.items.get(i));
		order.addAll(inv.offhand);
		order.add(inv.items.get(inv.selected));
		int remaining = amount;
		for (ItemStack stack : order) {
			if (remaining <= 0)
				break;
			if (!stack.is(item))
				continue;
			int taken = Math.min(remaining, stack.getCount());
			stack.shrink(taken);
			remaining -= taken;
		}
		return true;
	}

	// ---------------------------------------------------------------- text

	private static Component createText(String key) {
		return Component.translatable("create." + key);
	}

	private static Component ownText(String key) {
		return Component.translatable(RailwaysUnlimited.MOD_ID + ".track." + key);
	}
}

package com.davzelenec.railwaysunlimited.gametest;

import java.util.List;
import java.util.Map;

import com.davzelenec.railwaysunlimited.RailwaysUnlimited;
import com.davzelenec.railwaysunlimited.block.AngledTrackBlock;
import com.davzelenec.railwaysunlimited.compat.TrackGraphCleanup;
import com.davzelenec.railwaysunlimited.placement.AngledTrackPlacement;
import com.davzelenec.railwaysunlimited.placement.PlacementPlan;
import com.davzelenec.railwaysunlimited.placement.TrackGeometry;
import com.davzelenec.railwaysunlimited.snap.SnapSettings;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlockItem;
import com.simibubi.create.content.trains.track.TrackPlacement;
import com.simibubi.create.content.trains.track.TrackPropagator;
import com.simibubi.create.content.trains.track.TrackShape;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import com.davzelenec.railwaysunlimited.snap.WorldSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Server-side checks of angled placement. Run with the gameTestServer run configuration. */
@GameTestHolder(RailwaysUnlimited.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AngledTrackGameTests {
	private static final String TEMPLATE = "empty_32";

	private AngledTrackGameTests() {}

	/** Trains classes carry mixins; loading them here makes a broken injection fail the test run. */
	@GameTest(template = TEMPLATE)
	public static void trainMixinsApply(GameTestHelper helper) {
		try {
			Class.forName("com.simibubi.create.content.trains.entity.TravellingPoint");
			Class.forName("com.simibubi.create.content.trains.entity.CarriageContraptionEntity");
			Class.forName("com.simibubi.create.content.trains.entity.Navigation");
			Class.forName("com.simibubi.create.content.trains.track.TrackPlacement");
			Class.forName("com.simibubi.create.content.trains.track.CurvedTrackDestroyPacket");
			Class.forName("com.simibubi.create.content.trains.track.CurvedTrackSelectionPacket");
			Class.forName("com.simibubi.create.content.trains.track.TrackTargetingBlockItem");
		} catch (Throwable t) {
			helper.fail("Loading train classes failed: " + t);
		}
		helper.succeed();
	}

	@GameTest(template = TEMPLATE)
	public static void createKeepsGridPlacement(GameTestHelper helper) {
		BlockPos start = groundAndTrack(helper);
		Player player = lookingAt(helper, 45);
		ItemStack stack = selectFrom(helper, player, start);
		SnapSettings.setServerStep(player, 45);
		helper.assertFalse(AngledTrackPlacement.shouldHandle(helper.getLevel(), player, stack,
			helper.absolutePos(new BlockPos(20, 1, 26)), null), "45 degree step must leave placement to Create");
		SnapSettings.setServerStep(player, 15);
		Player straight = lookingAt(helper, 0);
		helper.assertFalse(AngledTrackPlacement.shouldHandle(helper.getLevel(), straight, stack,
			helper.absolutePos(new BlockPos(20, 1, 16)), null), "a grid-aligned end must leave placement to Create");
		helper.succeed();
	}

	@GameTest(template = TEMPLATE)
	public static void angledTrackJoinsTheGraph(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos start = groundAndTrack(helper);
		Player player = lookingAt(helper, 30);
		SnapSettings.setServerStep(player, 15);
		ItemStack stack = selectFrom(helper, player, start);
		BlockPos target = helper.absolutePos(new BlockPos(24, 1, 24));

		helper.assertTrue(AngledTrackPlacement.shouldHandle(level, player, stack, target, null),
			"30 degree end must be handled by this mod");
		PlacementPlan plan = AngledTrackPlacement.plan(level, player, target, null, stack, false, 15);
		helper.assertTrue(plan.valid, "plan should be valid: " + plan.message.getString());
		// the straight next to the Create track is made of Create blocks, the rest are a turn and an angled straight
		helper.assertTrue(plan.curves.size() == 2, "expected turn and straight but got " + plan.curves.size());
		helper.assertTrue(plan.newBlocks.stream().anyMatch(b -> b.state().getBlock() instanceof TrackBlock
				&& !AngledTrackBlock.isAnchor(b.state())), "expected Create track blocks before the turn");
		AngledTrackPlacement.execute(level, plan);

		helper.runAfterDelay(5, () -> {
			helper.assertTrue(AngledTrackBlock.isAnchor(level.getBlockState(target)), "end anchor missing");
			BezierConnection last = plan.curves.get(plan.curves.size() - 1);
			helper.assertTrue(TrackGeometry.isStraight(last), "last piece should be a straight curve");
			helper.assertTrue(TrackGeometry.displayAngle(plan.endDirection) == 120,
				"end should run at 120 degrees on the compass, was " + TrackGeometry.displayAngle(plan.endDirection));

			TrackGraph startGraph = graphAt(level, plan.curves.get(0).starts.getFirst());
			TrackGraph endGraph = graphAt(level, last.starts.getSecond());
			helper.assertTrue(startGraph != null, "start node not in a track graph");
			helper.assertTrue(startGraph == endGraph, "start and end are not in the same track graph");

			TrackNode endNode = endGraph.locateNode(new TrackNodeLocation(last.starts.getSecond()).in(level));
			Map<TrackNode, TrackEdge> fromEnd = endGraph.getConnectionsFrom(endNode);
			helper.assertTrue(fromEnd != null && fromEnd.size() == 1, "end node should have exactly one edge");
			TrackEdge edge = fromEnd.values().iterator().next();
			helper.assertTrue(edge.isTurn() && TrackGeometry.isStraight(edge.getTurn()),
				"edge into the end should be a straight curve");
			helper.succeed();
		});
	}

	@GameTest(template = TEMPLATE)
	public static void consecutiveSlopesStayContinuous(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos start = groundAndTrack(helper);
		Player player = lookingAt(helper, 15);
		SnapSettings.setServerStep(player, 15);

		// first piece climbs 2 blocks
		ItemStack stack = selectFrom(helper, player, start);
		BlockPos firstEnd = helper.absolutePos(new BlockPos(24, 3, 18));
		PlacementPlan first = AngledTrackPlacement.plan(level, player, firstEnd, null, stack, false, 15);
		helper.assertTrue(first.valid, "first plan should be valid: " + first.message.getString());
		AngledTrackPlacement.execute(level, first);

		// second piece continues from the anchor at the same grade
		ItemStack next = selectFrom(helper, player, firstEnd);
		double grade = first.grade;
		Vec3 anchor = AngledTrackBlock.getAnchorPoint(level, firstEnd);
		Vec3 heading = TrackGeometry.fromYaw(15);
		Vec3 ahead = anchor.add(heading.scale(10));
		BlockPos secondEnd = BlockPos.containing(ahead.x, anchor.y + Math.round(grade * 10), ahead.z);
		PlacementPlan second = AngledTrackPlacement.plan(level, player, secondEnd, null, next, false, 15);
		helper.assertTrue(second.valid, "second plan should be valid: " + second.message.getString());

		// the first piece's free end lay flat; continuing tilts it to the grade the second piece leaves with
		BezierConnection last = first.curves.get(first.curves.size() - 1);
		helper.assertTrue(Math.abs(TrackGeometry.grade(last.axes.getSecond())) < 1e-9,
			"a free end must lie flat, was " + last.axes.getSecond());
		AngledTrackPlacement.execute(level, second);
		BezierConnection stored = AngledTrackBlock.connections(level, firstEnd).get(last.bePositions.getFirst());
		helper.assertTrue(stored != null, "the first piece must still end at the anchor");
		Vec3 arriving = stored.axes.getFirst().scale(-1);
		Vec3 leaving = second.curves.get(0).axes.getFirst();
		helper.assertTrue(arriving.distanceTo(leaving) < 1e-6,
			"grade/heading must continue across the joint: " + arriving + " vs " + leaving);
		helper.assertTrue(Math.abs(TrackGeometry.grade(leaving) - grade) < 1e-6,
			"the second piece must leave at the grade the first was built with: " + grade);
		helper.assertTrue(second.curves.get(0).starts.getFirst().distanceTo(anchor) < 1e-9,
			"second piece must start exactly at the anchor point");
		helper.succeed();
	}

	/** Breaking an angled piece frees its joints: the same piece can be placed there again. */
	@GameTest(template = TEMPLATE, timeoutTicks = 100)
	public static void brokenPieceCanBePlacedAgain(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 0; x < 32; x++)
			for (int z = 0; z < 32; z++)
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
		Player player = lookingAt(helper, 155);
		SnapSettings.setServerStep(player, 5);
		ItemStack stack = new ItemStack(AllBlocks.TRACK.get());
		BlockPos first = helper.absolutePos(new BlockPos(16, 1, 16));
		PlacementPlan starter = AngledTrackPlacement.planStarter(level, player, first, stack, 5);
		helper.assertTrue(starter.valid, "starter should be valid: " + starter.message);
		AngledTrackPlacement.execute(level, starter);
		Vec3 startPoint = starter.curves.get(0).starts.getFirst();

		helper.runAfterDelay(5, () -> {
			helper.assertTrue(graphAt(level, startPoint) != null, "the starter must be in the track graph");
			// what breaking the curve does (CurvedTrackDestroyPacket)
			BlockPos endPos = starter.curves.get(0).bePositions.getSecond();
			if (level.getBlockEntity(first) instanceof TrackBlockEntity be)
				be.removeConnection(endPos);
			if (level.getBlockEntity(endPos) instanceof TrackBlockEntity other)
				other.removeConnection(first);
			TrackPropagator.onRailRemoved(level, first, level.getBlockState(first));

			helper.runAfterDelay(5, () -> {
				helper.assertTrue(level.getBlockState(first).isAir(), "the empty anchor must be gone");
				helper.assertTrue(graphAt(level, startPoint) == null, "no graph node may stay behind");
				PlacementPlan again = AngledTrackPlacement.planStarter(level, player, first, stack, 5);
				helper.assertTrue(again.valid, "placing again should be valid: " + again.message);
				helper.succeed();
			});
		});
	}

	/** A new line started at 25 degrees, then extended straight ahead from its end anchor. */
	@GameTest(template = TEMPLATE)
	public static void starterPieceCanBeExtended(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 0; x < 32; x++)
			for (int z = 0; z < 32; z++)
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
		Player player = lookingAt(helper, 25);
		SnapSettings.setServerStep(player, 5);
		ItemStack stack = new ItemStack(AllBlocks.TRACK.get());
		BlockPos first = helper.absolutePos(new BlockPos(4, 1, 6));

		helper.assertTrue(AngledTrackPlacement.shouldPlaceStarter(player, stack, level.getBlockState(first)),
			"a 25 degree start must be placed by this mod");
		PlacementPlan starter = AngledTrackPlacement.planStarter(level, player, first, stack, 5);
		helper.assertTrue(starter.valid, "starter should be valid: " + starter.message);
		AngledTrackPlacement.execute(level, starter);

		ItemStack next = selectFrom(helper, player, starter.endPos);
		Vec3 anchor = AngledTrackBlock.getAnchorPoint(level, starter.endPos);
		Vec3 ahead = anchor.add(TrackGeometry.fromYaw(25).scale(12));
		BlockPos target = BlockPos.containing(ahead.x, anchor.y, ahead.z);
		PlacementPlan extension = AngledTrackPlacement.plan(level, player, target, null, next, false, 5);
		helper.assertTrue(extension.valid, "extension should be valid: " + extension.message.getString());
		Vec3 leaving = extension.curves.get(0).axes.getFirst();
		helper.assertTrue(leaving.distanceTo(TrackGeometry.fromYaw(25)) < 1e-6,
			"extension must leave the starter in its own direction, was " + leaving);
		AngledTrackPlacement.execute(level, extension);

		helper.runAfterDelay(5, () -> {
			TrackGraph a = graphAt(level, starter.curves.get(0).starts.getFirst());
			TrackGraph b = graphAt(level, extension.curves.get(extension.curves.size() - 1).starts.getSecond());
			helper.assertTrue(a != null && a == b, "starter and extension must form one track graph");
			helper.succeed();
		});
	}

	/** The reported case: an angled track joined into the middle of a long straight Create line, without Sprint. */
	@GameTest(template = TEMPLATE)
	public static void angledTrackJoinsExistingStraightLine(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 0; x < 32; x++)
			for (int z = 0; z < 32; z++)
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
		for (int x = 2; x <= 28; x++)
			helper.setBlock(new BlockPos(x, 1, 16), AllBlocks.TRACK.getDefaultState()
				.setValue(TrackBlock.SHAPE, TrackShape.XO));

		helper.runAfterDelay(2, () -> {
			Player player = lookingAt(helper, 60);
			SnapSettings.setServerStep(player, 5);
			ItemStack stack = new ItemStack(AllBlocks.TRACK.get());
			PlacementPlan starter = AngledTrackPlacement.planStarter(level, player,
				helper.absolutePos(new BlockPos(6, 1, 4)), stack, 5);
			helper.assertTrue(starter.valid, "starter should be valid");
			AngledTrackPlacement.execute(level, starter);

			ItemStack next = selectFrom(helper, player, starter.endPos);
			Player aiming = lookingAt(helper, 0);
			BlockPos target = helper.absolutePos(new BlockPos(20, 1, 16));
			PlacementPlan plan = AngledTrackPlacement.plan(level, aiming, target, level.getBlockState(target), next,
				false, 5);
			helper.assertTrue(plan.valid, "joining the straight line should be valid: " + plan.message.getString());
			helper.assertTrue(!plan.endPos.equals(target), "the turn should join further along the existing line");
			AngledTrackPlacement.execute(level, plan);

			helper.runAfterDelay(5, () -> {
				TrackGraph line = graphAt(level, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(28, 1, 16)))
					.add(0.5, 0, 0));
				TrackGraph angled = graphAt(level, starter.curves.get(0).starts.getFirst());
				helper.assertTrue(line != null && line == angled, "angled track must join the line's graph");
				helper.succeed();
			});
		});
	}

	/** Sneaking continues the selected angled track straight on, whatever the player looks at. */
	@GameTest(template = TEMPLATE)
	public static void sneakingPlacesStraightContinuation(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Player player = lookingAt(helper, 35);
		SnapSettings.setServerStep(player, 5);
		ItemStack stack = new ItemStack(AllBlocks.TRACK.get());
		PlacementPlan starter = AngledTrackPlacement.planStarter(level, player,
			helper.absolutePos(new BlockPos(4, 1, 4)), stack, 5);
		AngledTrackPlacement.execute(level, starter);

		ItemStack next = selectFrom(helper, player, starter.endPos);
		Vec3 anchor = AngledTrackBlock.getAnchorPoint(level, starter.endPos);
		Vec3 aim = anchor.add(TrackGeometry.fromYaw(35).scale(10)).add(3, 0, -2);
		PlacementPlan plan = AngledTrackPlacement.planStraight(level, player, aim, null, next);
		helper.assertTrue(plan.valid, "straight continuation should be valid: " + plan.message.getString());
		helper.assertTrue(plan.curves.size() == 1 && TrackGeometry.isStraight(plan.curves.get(0)),
			"continuation must be one straight piece");
		Vec3 along = plan.curves.get(0).starts.getSecond().subtract(anchor).normalize();
		helper.assertTrue(along.distanceTo(TrackGeometry.fromYaw(35)) < 1e-6,
			"continuation must keep the 35 degree heading");
		helper.succeed();
	}

	/** An angled line joined onto the end of another angled line at a different angle. */
	@GameTest(template = TEMPLATE)
	public static void angledTrackJoinsAngledTrack(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ItemStack track = new ItemStack(AllBlocks.TRACK.get());

		// line A: 10 degrees, 2 + 12 blocks
		Player alongA = lookingAt(helper, 10);
		PlacementPlan starterA = AngledTrackPlacement.planStarter(level, alongA,
			helper.absolutePos(new BlockPos(4, 1, 4)), track, 5);
		AngledTrackPlacement.execute(level, starterA);
		ItemStack fromA = selectFrom(helper, alongA, starterA.endPos);
		Vec3 aEnd = AngledTrackBlock.getAnchorPoint(level, starterA.endPos);
		PlacementPlan lineA = AngledTrackPlacement.planStraight(level, alongA,
			aEnd.add(TrackGeometry.fromYaw(10).scale(10)), null, fromA);
		helper.assertTrue(lineA.valid, "line A should be valid: " + lineA.message.getString());
		AngledTrackPlacement.execute(level, lineA);

		// line B: a short piece at 260 degrees (towards -z)
		Player alongB = lookingAt(helper, 260);
		PlacementPlan starterB = AngledTrackPlacement.planStarter(level, alongB,
			helper.absolutePos(new BlockPos(24, 1, 24)), track, 5);
		helper.assertTrue(starterB.valid, "line B should be valid");
		AngledTrackPlacement.execute(level, starterB);

		// connect A's far end to B's end anchor
		ItemStack connecting = selectFrom(helper, alongA, lineA.endPos);
		BlockPos target = starterB.endPos;
		PlacementPlan join = AngledTrackPlacement.plan(level, lookingAt(helper, 80), target,
			level.getBlockState(target), connecting, false, 5);
		helper.assertTrue(join.valid, "joining two angled lines should be valid: " + join.message.getString()
			+ " [" + join.debug + "]");
		AngledTrackPlacement.execute(level, join);

		helper.runAfterDelay(5, () -> {
			TrackGraph a = graphAt(level, starterA.curves.get(0).starts.getFirst());
			TrackGraph b = graphAt(level, starterB.curves.get(0).starts.getFirst());
			helper.assertTrue(a != null && a == b, "both angled lines must be one track graph");
			helper.succeed();
		});
	}

	/** Straight mode climbs to the aimed block's height, from Create track and from angled track. */
	@GameTest(template = TEMPLATE)
	public static void straightModeClimbsToAimedHeight(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos createEnd = groundAndTrack(helper);
		Player along = lookingAt(helper, 0);
		ItemStack fromCreate = selectFrom(helper, along, createEnd);
		Vec3 aim = Vec3.atBottomCenterOf(createEnd).add(14, 0, 0);
		double targetY = createEnd.getY() + 2;
		PlacementPlan climb = AngledTrackPlacement.planStraight(level, along, aim, targetY, fromCreate);
		helper.assertTrue(climb.valid, "climbing from Create track should be valid: " + climb.message.getString());
		helper.assertTrue(climb.curves.size() == 1, "a climbing straight is one curve");
		BezierConnection bc = climb.curves.get(0);
		helper.assertTrue(Math.abs(bc.starts.getSecond().y - targetY) < 1e-6, "must end at the aimed height");
		helper.assertTrue(TrackGeometry.grade(bc.axes.getFirst()) == 0, "must leave the flat Create track flat");
		helper.assertTrue(TrackGeometry.isStraight(bc), "must stay straight seen from above");

		PlacementPlan level0 = AngledTrackPlacement.planStraight(level, along, aim, null, fromCreate);
		helper.assertTrue(level0.valid && level0.curves.isEmpty(), "keeping the level from Create track uses blocks");

		PlacementPlan tooSteep = AngledTrackPlacement.planStraight(level, along,
			Vec3.atBottomCenterOf(createEnd).add(4, 0, 0), createEnd.getY() + 4d, fromCreate);
		helper.assertFalse(tooSteep.valid, "4 up over 4 blocks must be too steep");

		// from angled track: the new piece starts at the anchor's grade and ends climbing
		Player angled = lookingAt(helper, 20);
		SnapSettings.setServerStep(angled, 5);
		PlacementPlan starter = AngledTrackPlacement.planStarter(level, angled,
			helper.absolutePos(new BlockPos(4, 1, 24)), new ItemStack(AllBlocks.TRACK.get()), 5);
		AngledTrackPlacement.execute(level, starter);
		ItemStack fromAnchor = selectFrom(helper, angled, starter.endPos);
		Vec3 anchorPoint = AngledTrackBlock.getAnchorPoint(level, starter.endPos);
		PlacementPlan angledClimb = AngledTrackPlacement.planStraight(level, angled,
			anchorPoint.add(TrackGeometry.fromYaw(20).scale(12)), Math.floor(anchorPoint.y) + 2, fromAnchor);
		helper.assertTrue(angledClimb.valid && Math.abs(angledClimb.curves.get(0).starts.getSecond().y
			- (anchorPoint.y + 2)) < 1e-6, "angled straight should climb to the aimed height");
		AngledTrackPlacement.execute(level, angledClimb);

		// keeping the grade at the top of that climb continues at the slope it was built with
		double climbGrade = angledClimb.grade;
		ItemStack fromTop = selectFrom(helper, angled, angledClimb.endPos);
		Vec3 top = AngledTrackBlock.getAnchorPoint(level, angledClimb.endPos);
		PlacementPlan onward = AngledTrackPlacement.planStraight(level, angled,
			top.add(TrackGeometry.fromYaw(20).scale(8)), null, fromTop);
		helper.assertTrue(onward.valid, "continuing the climb should be valid: " + onward.message.getString());
		double onwardGrade = TrackGeometry.grade(onward.curves.get(0).axes.getFirst());
		helper.assertTrue(climbGrade > 0.05 && Math.abs(onwardGrade - climbGrade) < 1e-6,
			"keeping the grade must continue at " + climbGrade + ", was " + onwardGrade);
		helper.succeed();
	}

	/** Keeping the grade at the bottom of a slope Create built itself continues downhill, not flat. */
	@GameTest(template = TEMPLATE)
	public static void keepGradeContinuesCreateSlope(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		// a raised Create track, so the slope down and its continuation stay above the floor
		for (int x = 2; x <= 4; x++)
			helper.setBlock(new BlockPos(x, 6, 16), AllBlocks.TRACK.getDefaultState()
				.setValue(TrackBlock.SHAPE, TrackShape.XO));
		BlockPos createEnd = helper.absolutePos(new BlockPos(4, 6, 16));
		Player along = lookingAt(helper, 0);
		ItemStack fromCreate = selectFrom(helper, along, createEnd);
		BlockPos low = helper.absolutePos(new BlockPos(16, 5, 16));
		level.setBlockAndUpdate(low, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.XO));
		Object info = TrackPlacement.tryConnect(level, along, low, level.getBlockState(low), fromCreate, false, false);
		// Create starts a slope's curve next to the clicked blocks; its lower end holds the arriving curve
		BezierConnection placed = debugField(info, "curve") instanceof BezierConnection bc ? bc : null;
		helper.assertTrue(placed != null, "Create should build the slope down");
		BlockPos bottomPos = placed.bePositions.getSecond();
		BezierConnection slope = level.getBlockEntity(bottomPos) instanceof TrackBlockEntity tbe
			? tbe.getConnections().get(placed.bePositions.getFirst()) : null;
		helper.assertTrue(slope != null, "the slope's lower end should hold it");

		SnapSettings.setServerStep(along, 5);
		SnapSettings.setServerStraight(along, false, false, true);
		ItemStack fromBottom = selectFrom(helper, along, bottomPos);
		Vec3 bottom = Vec3.atBottomCenterOf(bottomPos);
		PlacementPlan onward = AngledTrackPlacement.plan(level, along, bottomPos.offset(12, 0, 0), null, fromBottom,
			false, 5);
		SnapSettings.setServerStraight(along, false, false, false);
		helper.assertTrue(onward.valid, "continuing the slope should be valid: " + onward.message.getString());
		// arriving at the bottom: from the slope's far end to this one
		Vec3 top = slope.starts.getSecond();
		Vec3 here = slope.starts.getFirst();
		double built = (here.y - top.y) / TrackGeometry.horizontal(here.subtract(top)).length();
		helper.assertTrue(built < -0.01 && Math.abs(onward.grade - built) < 1e-6,
			"keeping the grade must continue at " + built + ", was " + onward.grade + " (from " + bottom + ")");
		// the same with straight mode (Alt + X): a straight that keeps descending, not Create's flat blocks
		PlacementPlan straight = AngledTrackPlacement.planStraight(level, along,
			Vec3.atBottomCenterOf(bottomPos).add(12, 0, 0), null, fromBottom);
		helper.assertTrue(straight.valid && straight.curves.size() == 1,
			"a straight keeping the grade should be one sloped curve: " + straight.message);
		BezierConnection down = straight.curves.get(0);
		double straightGrade = (down.starts.getSecond().y - down.starts.getFirst().y)
			/ TrackGeometry.horizontal(down.starts.getSecond().subtract(down.starts.getFirst())).length();
		helper.assertTrue(Math.abs(straightGrade - built) < 1e-6,
			"the straight must descend at " + built + ", was " + straightGrade);
		helper.assertTrue(TrackGeometry.grade(down.axes.getFirst()) == 0,
			"it must leave the Create block flat like Create's slope arrives");
		// built (normal placement with X, as players do it): Create tilts the block between the two slopes, and the
		// grade right after the joint is the same on both sides (no bump)
		helper.assertTrue(TrackGeometry.grade(onward.curves.get(0).axes.getFirst()) == 0,
			"from a Create block the new curve must leave flat; Create's tilt adds the grade");
		AngledTrackPlacement.execute(level, onward);
		helper.assertTrue(level.getBlockEntity(bottomPos) instanceof TrackBlockEntity tilted
			&& tilted.tilt.smoothingAngle.isPresent(), "the joint between the slopes must be smoothed");
		double[] away = new double[2];
		int sides = 0;
		for (BezierConnection placed2 : ((TrackBlockEntity) level.getBlockEntity(bottomPos)).getConnections().values()) {
			// a fresh copy: Create does not refresh a curve's cached shape when tilting it (clients get fresh ones)
			BezierConnection c = placed2.clone();
			Vec3 a = c.getPosition(0);
			Vec3 b = c.getPosition(0.02);
			away[sides++] = (b.y - a.y) / TrackGeometry.horizontal(b.subtract(a)).length();
		}
		helper.assertTrue(sides == 2 && Math.abs(Math.abs(away[0]) - Math.abs(away[1])) < 0.05
			&& away[0] * away[1] < 0, "the grade must run through the joint, was " + away[0] + " / " + away[1]);
		helper.runAfterDelay(5, () -> {
			int removed = TrackGraphCleanup.clearGhostsAround(level, Vec3.atCenterOf(bottomPos), 32);
			helper.assertTrue(removed == 0, "real track nodes must not be taken for ghosts, removed " + removed);
			helper.succeed();
		});
	}

	/** The per-save maximum segment length decides what is "too far". */
	@GameTest(template = TEMPLATE)
	public static void maxLengthIsPerPlayerSetting(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Player player = lookingAt(helper, 25);
		SnapSettings.setServerStep(player, 5);
		PlacementPlan starter = AngledTrackPlacement.planStarter(level, player,
			helper.absolutePos(new BlockPos(2, 1, 2)), new ItemStack(AllBlocks.TRACK.get()), 5);
		AngledTrackPlacement.execute(level, starter);
		ItemStack next = selectFrom(helper, player, starter.endPos);
		Vec3 far = AngledTrackBlock.getAnchorPoint(level, starter.endPos).add(TrackGeometry.fromYaw(25).scale(40));

		helper.assertTrue(SnapSettings.getMaxLength(player) == 32, "default maximum must be Create's 32");
		helper.assertFalse(AngledTrackPlacement.planStraight(level, player, far, null, next).valid,
			"40 blocks must be too far with the default maximum");

		MinecraftServer server = level.getServer();
		WorldSettings settings = WorldSettings.get(server);
		settings.set(player.getUUID(), settings.get(player.getUUID()).withMaxLength(64));
		helper.assertTrue(AngledTrackPlacement.planStraight(level, player, far, null, next).valid,
			"40 blocks must be allowed with a maximum of 64");
		settings.set(player.getUUID(), settings.get(player.getUUID()).withMaxLength(9999));
		helper.assertTrue(SnapSettings.getMaxLength(player) == 512, "the maximum is capped at 512");
		helper.succeed();
	}

	/** The reported case: an angled track joined into the middle of a long angled straight, splitting it. */
	@GameTest(template = TEMPLATE)
	public static void angledTrackJoinsMiddleOfAngledLine(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ItemStack track = new ItemStack(AllBlocks.TRACK.get());

		// line A at 10 degrees: a starter and a 12 block straight
		Player alongA = lookingAt(helper, 10);
		PlacementPlan starterA = AngledTrackPlacement.planStarter(level, alongA,
			helper.absolutePos(new BlockPos(4, 1, 4)), track, 5);
		AngledTrackPlacement.execute(level, starterA);
		Vec3 aJoint = AngledTrackBlock.getAnchorPoint(level, starterA.endPos);
		PlacementPlan lineA = AngledTrackPlacement.planStraight(level, alongA,
			aJoint.add(TrackGeometry.fromYaw(10).scale(12)), null, selectFrom(helper, alongA, starterA.endPos));
		AngledTrackPlacement.execute(level, lineA);

		// line B at 250 degrees, heading towards line A
		Player alongB = lookingAt(helper, 250);
		PlacementPlan starterB = AngledTrackPlacement.planStarter(level, alongB,
			helper.absolutePos(new BlockPos(16, 1, 14)), track, 5);
		AngledTrackPlacement.execute(level, starterB);
		ItemStack connecting = selectFrom(helper, alongB, starterB.endPos);

		// aim 4.5 blocks from the start of line A, beyond where line B's smallest turn would join
		Vec3 aStart = starterA.curves.get(0).starts.getFirst();
		Vec3 aim = aStart.add(TrackGeometry.fromYaw(10).scale(4.5));

		// with Sprint the turn is as wide as the aim allows: it joins exactly at the aimed point
		PlacementPlan widest = AngledTrackPlacement.planIntoLine(level, alongB, lineA.startPos, lineA.endPos, aim,
			connecting, true, 5);
		helper.assertTrue(widest.valid && widest.split != null, "maximised join should split line A: "
			+ widest.message.getString() + " [" + widest.debug + "]");
		Vec3 widestJoint = widest.split.firstHalf().starts.getSecond();
		helper.assertTrue(TrackGeometry.horizontal(widestJoint.subtract(aim)).length() < 1e-6,
			"with Sprint the line must be split at the aimed point, was " + widestJoint);

		// without Sprint the smallest turn is used, wherever the aim is (like Create)
		PlacementPlan join = AngledTrackPlacement.planIntoLine(level, alongB, lineA.startPos, lineA.endPos, aim,
			connecting, false, 5);
		helper.assertTrue(join.valid, "joining the middle of line A should be valid: " + join.message.getString()
			+ " [" + join.debug + "]");
		helper.assertTrue(join.split != null, "line A should be split");
		Vec3 joint = join.split.firstHalf().starts.getSecond();
		PlacementPlan fartherAim = AngledTrackPlacement.planIntoLine(level, alongB, lineA.startPos, lineA.endPos,
			aStart.add(TrackGeometry.fromYaw(10).scale(3)), connecting, false, 5);
		helper.assertTrue(fartherAim.valid && fartherAim.split != null && fartherAim.split.firstHalf().starts
				.getSecond().distanceTo(joint) < 1e-6,
			"without Sprint the joint must not depend on where along the line the player aims");
		BezierConnection lastNew = join.curves.get(join.curves.size() - 1);
		helper.assertTrue(lastNew.starts.getSecond().distanceTo(joint) < 1e-6,
			"the new turn must end exactly at the joint");

		// with Sprint, aiming at the starter piece: the turn reaches the anchor between the pieces and reuses it
		PlacementPlan viaChain = AngledTrackPlacement.planIntoLine(level, alongB, lineA.startPos, lineA.endPos,
			aStart.add(TrackGeometry.fromYaw(10).scale(1)), connecting, true, 5);
		helper.assertTrue(viaChain.valid && viaChain.split == null && viaChain.endPos.equals(starterA.endPos),
			"the widest turn should join at the existing anchor between the pieces [" + viaChain.debug + "]");

		// aiming right at the joint between line A's pieces joins the line too, never a duplicate along it
		PlacementPlan atJointWide = AngledTrackPlacement.planIntoAnchorLine(level, alongB, starterA.endPos, connecting,
			true, 5);
		helper.assertTrue(atJointWide != null && atJointWide.valid && atJointWide.split == null
				&& atJointWide.endPos.equals(starterA.endPos),
			"with Sprint, aiming at the joint should end the turn at that joint");
		PlacementPlan atJoint = AngledTrackPlacement.planIntoAnchorLine(level, alongB, starterA.endPos, connecting,
			false, 5);
		helper.assertTrue(atJoint != null && atJoint.valid && atJoint.curves.size() <= 2,
			"without Sprint, aiming at the joint should use the smallest turn onto the line");
		for (BezierConnection bc : atJoint.curves)
			helper.assertFalse(TrackGeometry.isStraight(bc) && TrackGeometry.horizontalDir(bc.axes.getFirst())
					.dot(TrackGeometry.fromYaw(10)) < -0.999,
				"no new straight may run along line A");

		AngledTrackPlacement.execute(level, join);

		helper.runAfterDelay(5, () -> {
			helper.assertTrue(AngledTrackBlock.connections(level, lineA.startPos).get(lineA.endPos) == null,
				"the original long piece must be replaced by two halves");
			TrackGraph a = graphAt(level, starterA.curves.get(0).starts.getFirst());
			TrackGraph aFar = graphAt(level, lineA.curves.get(0).starts.getSecond());
			TrackGraph b = graphAt(level, starterB.curves.get(0).starts.getFirst());
			helper.assertTrue(a != null && a == aFar && a == b, "line A (both halves) and line B must be one graph");
			helper.succeed();
		});
	}

	/** Joining a straight line must work without Sprint wherever it works with Sprint. */
	@GameTest(template = TEMPLATE, timeoutTicks = 400)
	public static void joiningWorksWithoutSprintWheneverWithSprint(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 0; x < 32; x++)
			for (int z = 0; z < 32; z++)
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
		for (int x = 0; x <= 31; x++)
			helper.setBlock(new BlockPos(x, 1, 20), AllBlocks.TRACK.getDefaultState()
				.setValue(TrackBlock.SHAPE, TrackShape.XO));

		helper.runAfterDelay(2, () -> {
			StringBuilder problems = new StringBuilder();
			int cases = 0;
			for (int heading = 5; heading <= 175; heading += 10) {
				Player player = lookingAt(helper, heading);
				SnapSettings.setServerStep(player, 5);
				BlockPos startPos = helper.absolutePos(new BlockPos(15, 1, 3 + (heading / 10) % 12));
				PlacementPlan starter = AngledTrackPlacement.planStarter(level, player, startPos,
					new ItemStack(AllBlocks.TRACK.get()), 5);
				if (!starter.valid)
					continue;
				AngledTrackPlacement.execute(level, starter);
				ItemStack next = selectFrom(helper, player, starter.endPos);
				Player aiming = lookingAt(helper, 0);
				for (int x = 1; x <= 30; x++) {
					BlockPos target = helper.absolutePos(new BlockPos(x, 1, 20));
					BlockState state = level.getBlockState(target);
					PlacementPlan wide = AngledTrackPlacement.plan(level, aiming, target, state, next, true, 5);
					PlacementPlan normal = AngledTrackPlacement.plan(level, aiming, target, state, next, false, 5);
					cases++;
					if (wide.valid && !normal.valid)
						problems.append(String.format("heading %d target x=%d: %s; ", heading, x,
							normal.message.getString()));
				}
				for (PlacementPlan.NewBlock block : starter.newBlocks)
					level.removeBlock(block.pos(), false);
			}
			RailwaysUnlimited.LOGGER.info("Sprint comparison checked {} cases", cases);
			if (!problems.isEmpty())
				helper.fail("Valid only with Sprint: " + problems);
			helper.succeed();
		});
	}

	// ---------------------------------------------------------------- plain Create behaviour with this mod loaded

	/** Create lays its turns through terrain (the curve is not made of blocks); the mod must not change that. */
	@GameTest(template = TEMPLATE)
	public static void createTurnGoesThroughTerrain(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos start = groundAndTrack(helper);
		// a stone wall across the inside of the turn
		for (int x = 6; x <= 12; x++)
			for (int y = 1; y <= 3; y++)
				helper.setBlock(new BlockPos(x, y, 20), Blocks.STONE.defaultBlockState());
		Player player = lookingAt(helper, 90);
		ItemStack stack = selectFrom(helper, player, start);
		BlockPos end = helper.absolutePos(new BlockPos(14, 1, 26));
		level.setBlockAndUpdate(end, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.ZO));
		helper.assertFalse(AngledTrackPlacement.shouldHandle(level, player, stack, end, level.getBlockState(end)),
			"a 90 degree turn between grid track is Create's");
		Object info = TrackPlacement.tryConnect(level, player, end, level.getBlockState(end), stack, false, false);
		helper.assertTrue(Boolean.TRUE.equals(debugField(info, "valid")),
			"Create must lay the turn through the wall: " + debugField(info, "message"));
		helper.assertTrue(debugField(info, "curve") instanceof BezierConnection, "it must be a curve");
		helper.succeed();
	}

	/** The same for this mod's angled track: a piece may pass through terrain, only its joints need room. */
	@GameTest(template = TEMPLATE)
	public static void angledTrackGoesThroughTerrain(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 0; x < 32; x++)
			for (int z = 0; z < 32; z++)
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
		Player player = lookingAt(helper, 20);
		SnapSettings.setServerStep(player, 5);
		ItemStack stack = new ItemStack(AllBlocks.TRACK.get());
		PlacementPlan starter = AngledTrackPlacement.planStarter(level, player,
			helper.absolutePos(new BlockPos(3, 1, 4)), stack, 5);
		AngledTrackPlacement.execute(level, starter);
		Vec3 anchor = AngledTrackBlock.getAnchorPoint(level, starter.endPos);
		// a wall across the way, between the joints
		Vec3 wallAt = anchor.add(TrackGeometry.fromYaw(20).scale(7));
		BlockPos wall = BlockPos.containing(wallAt.x, anchor.y, wallAt.z);
		for (int d = -3; d <= 3; d++)
			for (int y = 0; y <= 2; y++)
				level.setBlockAndUpdate(wall.offset(-d, y, 2 * d), Blocks.STONE.defaultBlockState());
		Vec3 ahead = anchor.add(TrackGeometry.fromYaw(20).scale(14));
		PlacementPlan through = AngledTrackPlacement.plan(level, player,
			BlockPos.containing(ahead.x, anchor.y, ahead.z), null, selectFrom(helper, player, starter.endPos), false, 5);
		helper.assertTrue(through.valid, "angled track must pass through the wall: " + through.message);
		helper.succeed();
	}

	/** Create's own S-curve between parallel grid track stays Create's. */
	@GameTest(template = TEMPLATE)
	public static void createSCurveStaysCreate(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos start = groundAndTrack(helper);
		Player player = lookingAt(helper, 0);
		SnapSettings.setServerStep(player, 5);
		ItemStack stack = selectFrom(helper, player, start);
		BlockPos end = helper.absolutePos(new BlockPos(20, 1, 20));
		level.setBlockAndUpdate(end, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.XO));
		helper.assertFalse(AngledTrackPlacement.shouldHandle(level, player, stack, end, level.getBlockState(end)),
			"an S-curve between parallel grid track is Create's");
		Object info = TrackPlacement.tryConnect(level, player, end, level.getBlockState(end), stack, false, false);
		helper.assertTrue(Boolean.TRUE.equals(debugField(info, "valid")),
			"Create must lay its S-curve: " + debugField(info, "message"));
		helper.succeed();
	}

	/** Create still refuses slopes that are too steep. */
	@GameTest(template = TEMPLATE)
	public static void createStillRefusesTooSteep(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos start = groundAndTrack(helper);
		Player player = lookingAt(helper, 0);
		ItemStack stack = selectFrom(helper, player, start);
		BlockPos end = helper.absolutePos(new BlockPos(8, 5, 16));
		level.setBlockAndUpdate(end, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.XO));
		Object info = TrackPlacement.tryConnect(level, player, end, level.getBlockState(end), stack, false, false);
		helper.assertFalse(Boolean.TRUE.equals(debugField(info, "valid")), "4 up over 4 blocks must be too steep");
		helper.succeed();
	}

	/** Breaking Create's own curve leaves nothing behind in the track graph. */
	@GameTest(template = TEMPLATE, timeoutTicks = 100)
	public static void breakingCreateCurveLeavesNoGhosts(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos start = groundAndTrack(helper);
		Player player = lookingAt(helper, 90);
		ItemStack stack = selectFrom(helper, player, start);
		BlockPos end = helper.absolutePos(new BlockPos(14, 1, 26));
		level.setBlockAndUpdate(end, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.ZO));
		Object info = TrackPlacement.tryConnect(level, player, end, level.getBlockState(end), stack, false, false);
		helper.assertTrue(Boolean.TRUE.equals(debugField(info, "valid")), "Create must lay the turn");
		helper.runAfterDelay(5, () -> {
			level.destroyBlock(end, false);
			helper.runAfterDelay(5, () -> {
				int ghosts = TrackGraphCleanup.clearGhostsAround(level, Vec3.atCenterOf(end), 24);
				helper.assertTrue(ghosts == 0, "breaking Create track must not leave graph nodes, found " + ghosts);
				helper.succeed();
			});
		});
	}

	/** A free end of a slope lies flat like Create's; continuing from it tilts it back into the hill, and back. */
	@GameTest(template = TEMPLATE, timeoutTicks = 100)
	public static void freeSlopeEndLiesFlat(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 0; x < 32; x++)
			for (int z = 0; z < 32; z++)
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
		Player player = lookingAt(helper, 20);
		SnapSettings.setServerStep(player, 5);
		PlacementPlan starter = AngledTrackPlacement.planStarter(level, player,
			helper.absolutePos(new BlockPos(2, 4, 4)), new ItemStack(AllBlocks.TRACK.get()), 5);
		AngledTrackPlacement.execute(level, starter);
		Vec3 top = AngledTrackBlock.getAnchorPoint(level, starter.endPos);
		// down 1 block over 10 with the straight key
		PlacementPlan down = AngledTrackPlacement.planStraight(level, player,
			top.add(TrackGeometry.fromYaw(20).scale(10)), Math.floor(top.y) - 1,
			selectFrom(helper, player, starter.endPos));
		helper.assertTrue(down.valid, "the slope down should be valid: " + down.message);
		AngledTrackPlacement.execute(level, down);
		BezierConnection slope = down.curves.get(0);
		helper.assertTrue(Math.abs(TrackGeometry.grade(slope.axes.getSecond())) < 1e-9,
			"the free end must lie flat, was " + slope.axes.getSecond());

		// keep the grade from there: the slope's end tilts to it and the new piece leaves at the same grade
		SnapSettings.setServerStraight(player, true, false, true);
		Vec3 bottom = AngledTrackBlock.getAnchorPoint(level, down.endPos);
		PlacementPlan onward = AngledTrackPlacement.planStraight(level, player,
			bottom.add(TrackGeometry.fromYaw(20).scale(10)), null, selectFrom(helper, player, down.endPos));
		SnapSettings.setServerStraight(player, false, false, false);
		helper.assertTrue(onward.valid, "continuing should be valid: " + onward.message);
		helper.assertTrue(Math.abs(onward.grade - down.grade) < 1e-6,
			"keeping the grade continues at " + down.grade + ", was " + onward.grade);
		AngledTrackPlacement.execute(level, onward);
		BezierConnection tilted = AngledTrackBlock.connections(level, down.endPos).get(starter.endPos);
		helper.assertTrue(tilted != null, "the slope must still arrive at its end anchor");
		Vec3 arriving = tilted.axes.getFirst().scale(-1);
		helper.assertTrue(arriving.distanceTo(onward.curves.get(0).axes.getFirst()) < 1e-6,
			"the hill must run on through the joint: " + arriving + " vs " + onward.curves.get(0).axes.getFirst());

		// breaking the continuation makes it a free end again: flat, like before
		helper.runAfterDelay(3, () -> {
			BezierConnection continuation = onward.curves.get(0);
			BlockPos joint = continuation.bePositions.getFirst();
			BlockPos far = continuation.bePositions.getSecond();
			if (level.getBlockEntity(joint) instanceof TrackBlockEntity be)
				be.removeConnection(far);
			if (level.getBlockEntity(far) instanceof TrackBlockEntity other)
				other.removeConnection(joint);
			TrackPropagator.onRailRemoved(level, joint, level.getBlockState(joint));
			helper.runAfterDelay(3, () -> {
				BezierConnection again = AngledTrackBlock.connections(level, down.endPos).get(starter.endPos);
				helper.assertTrue(again != null, "the slope must still end at its anchor");
				helper.assertTrue(Math.abs(TrackGeometry.grade(again.axes.getFirst())) < 1e-9,
					"a free end again must lie flat, was " + again.axes.getFirst());
				BezierConnection otherSide = AngledTrackBlock.connections(level, starter.endPos).get(down.endPos);
				helper.assertTrue(otherSide != null && otherSide.axes.getSecond().distanceTo(again.axes.getFirst()) < 1e-9,
					"both block entities must hold the same flat curve");
				helper.succeed();
			});
		});
	}

	/** Level track after a hill (or over a crest) leaves the hill's eased, flat end as it is. */
	@GameTest(template = TEMPLATE)
	public static void levelTrackAfterHillKeepsItsEnd(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 0; x < 32; x++)
			for (int z = 0; z < 32; z++)
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
		Player player = lookingAt(helper, 20);
		SnapSettings.setServerStep(player, 5);
		PlacementPlan starter = AngledTrackPlacement.planStarter(level, player,
			helper.absolutePos(new BlockPos(2, 1, 4)), new ItemStack(AllBlocks.TRACK.get()), 5);
		AngledTrackPlacement.execute(level, starter);
		Vec3 bottom = AngledTrackBlock.getAnchorPoint(level, starter.endPos);
		PlacementPlan up = AngledTrackPlacement.planStraight(level, player,
			bottom.add(TrackGeometry.fromYaw(20).scale(10)), Math.floor(bottom.y) + 1,
			selectFrom(helper, player, starter.endPos));
		helper.assertTrue(up.valid, "the hill should be valid: " + up.message);
		AngledTrackPlacement.execute(level, up);
		Vec3 top = AngledTrackBlock.getAnchorPoint(level, up.endPos);

		// level straight on from the top (keeping the height)
		PlacementPlan flat = AngledTrackPlacement.planStraight(level, player,
			top.add(TrackGeometry.fromYaw(20).scale(10)), top.y, selectFrom(helper, player, up.endPos));
		helper.assertTrue(flat.valid, "level track should be valid: " + flat.message);
		helper.assertTrue(flat.retangent == null, "level track must not tilt the hill's end");
		helper.assertTrue(Math.abs(TrackGeometry.grade(flat.curves.get(0).axes.getFirst())) < 1e-9,
			"level track leaves the top flat");
		AngledTrackPlacement.execute(level, flat);
		BezierConnection hill = AngledTrackBlock.connections(level, up.endPos).get(starter.endPos);
		helper.assertTrue(hill != null && Math.abs(TrackGeometry.grade(hill.axes.getFirst())) < 1e-9,
			"the hill must keep its flat end, was " + (hill == null ? null : hill.axes.getFirst()));

		// over a crest (going down after going up) the top stays flat as well
		PlacementPlan crest = AngledTrackPlacement.planStraight(level, player,
			top.add(TrackGeometry.fromYaw(20).scale(10)), top.y - 1, selectFrom(helper, player, up.endPos));
		helper.assertTrue(crest.retangent == null
				&& Math.abs(TrackGeometry.grade(crest.curves.get(0).axes.getFirst())) < 1e-9,
			"a crest stays flat at the top");
		helper.succeed();
	}

	// ---------------------------------------------------------------- helpers

	private static Object debugField(Object owner, String name) {
		try {
			java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return field.get(owner);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	/** Stone floor and a short Create track along X ending at (4, 1, 16). Returns the absolute end position. */
	private static BlockPos groundAndTrack(GameTestHelper helper) {
		for (int x = 0; x < 32; x++)
			for (int z = 0; z < 32; z++)
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
		for (int x = 2; x <= 4; x++)
			helper.setBlock(new BlockPos(x, 1, 16), AllBlocks.TRACK.getDefaultState()
				.setValue(TrackBlock.SHAPE, TrackShape.XO));
		return helper.absolutePos(new BlockPos(4, 1, 16));
	}

	/** A creative mock player looking slightly down along the given yaw (degrees from +X towards +Z). */
	private static Player lookingAt(GameTestHelper helper, double yaw) {
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		player.setYRot((float) (yaw - 90));
		player.setXRot(30);
		return player;
	}

	private static ItemStack selectFrom(GameTestHelper helper, Player player, BlockPos pos) {
		ItemStack stack = new ItemStack(AllBlocks.TRACK.get());
		TrackBlockItem.select(helper.getLevel(), pos, player.getLookAngle(), stack);
		return stack;
	}

	private static TrackGraph graphAt(ServerLevel level, Vec3 point) {
		List<TrackGraph> graphs = Create.RAILWAYS.getGraphs(level, new TrackNodeLocation(point).in(level));
		return graphs.isEmpty() ? null : graphs.get(0);
	}
}

package com.davzelenec.railwaysunlimited.placement;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Everything one angled placement would do, computed identically on client (preview) and server (placement). */
public class PlacementPlan {
	public boolean valid = false;
	public Component message;
	public boolean neutralMessage = false;
	/** Geometry behind a refusal, logged so failed placements can be diagnosed. */
	public String debug = "";

	public TrackMaterial material;
	public BlockPos startPos;
	public BlockPos endPos;
	/** Blocks that must be placed before the curves are attached (anchors, a new Create track at the end). */
	public final List<NewBlock> newBlocks = new ArrayList<>();
	public final List<BezierConnection> curves = new ArrayList<>();
	/** Anchor points (for the preview). */
	public final List<Vec3> anchorPoints = new ArrayList<>();

	public int requiredTracks = 0;

	/** Heading the newly placed end runs at, for the goggle overlay. */
	public Vec3 endDirection;
	public double grade = 0;

	public record NewBlock(BlockPos pos, BlockState state) {}

	/**
	 * An existing straight curve between {@code from} and {@code to} that is cut in two at a new anchor, so that new
	 * track can join it in the middle. The two halves replace the original curve.
	 */
	public record Split(BezierConnection original, BezierConnection firstHalf, BezierConnection secondHalf) {}

	/** Set when this placement joins the middle of an angled straight. */
	public Split split;

	/**
	 * A curve ending at a free anchor (lying flat there) that gets {@code axisAtAnchor} as its tangent at the anchor,
	 * because new track continues from it at a grade.
	 */
	public record Retangent(BlockPos anchor, BezierConnection curve, net.minecraft.world.phys.Vec3 axisAtAnchor) {}

	public Retangent retangent;

	public PlacementPlan fail(Component message) {
		this.valid = false;
		this.message = message;
		return this;
	}

	public PlacementPlan failNeutral(Component message) {
		fail(message);
		this.neutralMessage = true;
		return this;
	}
}

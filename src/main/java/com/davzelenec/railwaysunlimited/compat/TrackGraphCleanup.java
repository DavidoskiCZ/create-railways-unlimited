package com.davzelenec.railwaysunlimited.compat;

import java.util.List;

import com.davzelenec.railwaysunlimited.block.AngledTrackBlock;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps Create's track graph free of nodes at joints that no longer have track.
 * <p>
 * When a curve is broken, Create first removes it from both blocks and only then asks the block for the graph nodes
 * it held. A Create track block still has its own straight piece ending there, but an anchor whose last curve is gone
 * has nothing left, so its node would stay in the graph and block the spot for new track.
 */
public final class TrackGraphCleanup {
	private TrackGraphCleanup() {}

	/** Called before {@code be} drops the curve leading to {@code target}. */
	public static void beforeConnectionRemoved(TrackBlockEntity be, BlockPos target) {
		Level level = be.getLevel();
		if (level == null || level.isClientSide || !AngledTrackBlock.isAnchor(be.getBlockState()))
			return;
		var connections = be.getConnections();
		BezierConnection removed = connections.get(target);
		if (removed == null || connections.size() != 1)
			return;
		removeNodesAt(level, removed.starts.getFirst());
	}

	/**
	 * Removes graph nodes at this point that no track holds any more (left behind by earlier versions). Returns
	 * whether a node of real track remains there.
	 */
	public static boolean purgeStale(Level level, Vec3 point) {
		TrackNodeLocation location = new TrackNodeLocation(point).in(level);
		if (Create.RAILWAYS.sided(level).getGraphs(level, location).isEmpty())
			return false;
		if (hasTrackNear(level, point))
			return true;
		if (!level.isClientSide)
			removeNodesAt(level, point);
		return false;
	}

	/** Removes every graph node without track within {@code radius} blocks of {@code center}. Returns how many. */
	public static int clearGhostsAround(Level level, Vec3 center, double radius) {
		List<Vec3> ghosts = new java.util.ArrayList<>();
		for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values())
			for (TrackNodeLocation location : graph.getNodes()) {
				if (location.dimension != level.dimension())
					continue;
				Vec3 point = location.getLocation();
				if (point.distanceToSqr(center) <= radius * radius && !hasTrackNear(level, point))
					ghosts.add(point);
			}
		for (Vec3 point : ghosts)
			removeNodesAt(level, point);
		return ghosts.size();
	}

	/**
	 * Whether any track block could hold a node at this point. Generous on purpose: Create moves the joint of two
	 * smoothed slopes up to a block along the track and up, and a real node must never be taken for a ghost.
	 */
	public static boolean hasTrackNear(LevelAccessor level, Vec3 point) {
		for (double dx : new double[] { -1.5, -0.5, 0.5, 1.5 })
			for (double dz : new double[] { -1.5, -0.5, 0.5, 1.5 })
				for (int dy = -2; dy <= 1; dy++) {
					BlockPos pos = BlockPos.containing(point.x + dx, Math.floor(point.y + 1e-6) + dy, point.z + dz);
					if (level.getBlockState(pos).getBlock() instanceof ITrackBlock)
						return true;
				}
		return false;
	}

	private static void removeNodesAt(Level level, Vec3 point) {
		TrackNodeLocation location = new TrackNodeLocation(point).in(level);
		GlobalRailwayManager manager = Create.RAILWAYS;
		List<TrackGraph> graphs = manager.getGraphs(level, location);
		for (TrackGraph graph : graphs) {
			TrackNode node = graph.locateNode(location);
			if (node == null)
				continue;
			graph.removeNode(level, location);
			manager.sync.nodeRemoved(graph, node);
			if (!graph.isEmpty())
				continue;
			manager.removeGraphAndGroup(graph);
			manager.sync.graphRemoved(graph);
		}
		manager.markTracksDirty();
	}
}

package com.davzelenec.railwaysunlimited.client;

import java.util.HashSet;
import java.util.Set;

import com.davzelenec.railwaysunlimited.block.AngledTrackBlock;
import com.davzelenec.railwaysunlimited.compat.TrackGraphCleanup;
import com.davzelenec.railwaysunlimited.snap.SnapSettings;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.TrackBlockEntity;

import net.createmod.catnip.data.Pair;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Debug view: highlights the anchors (joints) of angled track near the player in orange, so leftovers and joint
 * positions are easy to see. Anchors without any track left are shown in red. Track graph nodes that no track holds
 * any more ("ghosts", which block placing new track there) are shown as purple pillars.
 */
public final class JointDebugView {
	private static final int RADIUS = 32;
	private static final int ORANGE = 0xFF8A00;
	private static final int RED = 0xFF2020;
	private static final int PURPLE = 0xC040FF;
	private static final String KEY = "railways_unlimited_joint_debug";

	private static final Set<Object> shown = new HashSet<>();

	private JointDebugView() {}

	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || !SnapSettings.getClientSettings().showJoints()) {
			clear();
			return;
		}

		Set<Object> found = new HashSet<>();
		ChunkPos center = player.chunkPosition();
		int chunkRadius = (RADIUS >> 4) + 1;
		double radiusSqr = (double) RADIUS * RADIUS;
		for (int cx = center.x - chunkRadius; cx <= center.x + chunkRadius; cx++)
			for (int cz = center.z - chunkRadius; cz <= center.z + chunkRadius; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
				if (chunk == null)
					continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					BlockPos pos = be.getBlockPos();
					if (!(be instanceof TrackBlockEntity tbe) || !AngledTrackBlock.isAnchor(be.getBlockState())
						|| pos.distToCenterSqr(player.position()) > radiusSqr)
						continue;
					found.add(pos);
					boolean empty = tbe.getConnections().isEmpty();
					Vec3 point = AngledTrackBlock.getAnchorPoint(level, pos);
					Outliner.getInstance()
						.showAABB(Pair.of(KEY, pos), new AABB(point, point).inflate(0.3, 0.05, 0.3)
							.move(0, 0.3, 0))
						.colored(empty ? RED : ORANGE)
						.lineWidth(1 / 8f);
				}
			}

		// anchors without a block entity (left without any track) are found by their block
		BlockPos playerPos = player.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(playerPos.offset(-8, -4, -8), playerPos.offset(8, 4, 8))) {
			if (!AngledTrackBlock.isAnchor(level.getBlockState(pos)) || found.contains(pos))
				continue;
			BlockPos immutable = pos.immutable();
			found.add(immutable);
			Outliner.getInstance()
				.showAABB(Pair.of(KEY, immutable), new AABB(immutable).deflate(0.25))
				.colored(RED)
				.lineWidth(1 / 8f);
		}

		// graph nodes without any track: Create forgot to remove them, and they block new joints there
		for (TrackGraph graph : CreateClient.RAILWAYS.trackNetworks.values())
			for (TrackNodeLocation location : graph.getNodes()) {
				if (location.dimension != level.dimension())
					continue;
				Vec3 point = location.getLocation();
				if (point.distanceToSqr(player.position()) > radiusSqr || TrackGraphCleanup.hasTrackNear(level, point))
					continue;
				found.add(location);
				Outliner.getInstance()
					.showAABB(Pair.of(KEY, location), new AABB(point, point).inflate(0.15, 0, 0.15).expandTowards(0, 1.5, 0))
					.colored(PURPLE)
					.lineWidth(1 / 8f);
			}

		for (Object key : shown)
			if (!found.contains(key))
				Outliner.getInstance().remove(Pair.of(KEY, key));
		shown.clear();
		shown.addAll(found);
	}

	private static void clear() {
		for (Object key : shown)
			Outliner.getInstance().remove(Pair.of(KEY, key));
		shown.clear();
	}
}

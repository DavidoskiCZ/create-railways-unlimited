package com.davzelenec.railwaysunlimited.block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.davzelenec.railwaysunlimited.placement.AngledTrackPlacement;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.graph.TrackNodeLocation.DiscoveredLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackShape;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Invisible anchor that joins angled track pieces.
 * <p>
 * It reuses Create's {@link TrackBlockEntity} (the block is added to Create's track block entity type), so every curve
 * attached to it is a normal Create {@link BezierConnection}: rendering, breaking, stations, signals, the track graph and
 * map mods all see ordinary Create track. The anchor itself has no straight track of its own; it only exposes the ends
 * of its curves to the graph. All curves of one anchor start at the same exact point, the "anchor point".
 */
public class AngledTrackBlock extends TrackBlock {

	private static final VoxelShape SHAPE_OUTLINE = Block.box(5, 0, 5, 11, 2, 11);
	private static final Vec3 UP = new Vec3(0, 1, 0);

	public AngledTrackBlock(Properties properties) {
		super(properties, TrackMaterial.ANDESITE);
		registerDefaultState(defaultBlockState().setValue(SHAPE, TrackShape.NONE)
			.setValue(HAS_BE, true)
			.setValue(WATERLOGGED, false));
	}

	public static Map<BlockPos, BezierConnection> connections(BlockGetter world, BlockPos pos) {
		if (world.getBlockEntity(pos) instanceof TrackBlockEntity tbe)
			return tbe.getConnections();
		return Map.of();
	}

	/** The exact point all curves of this anchor start from. */
	public static Vec3 getAnchorPoint(BlockGetter world, BlockPos pos) {
		for (BezierConnection bc : connections(world, pos).values())
			return bc.starts.getFirst();
		return Vec3.atBottomCenterOf(pos);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return withWater(defaultBlockState(), ctx);
	}

	@Override
	public List<Vec3> getTrackAxes(BlockGetter world, BlockPos pos, BlockState state) {
		List<Vec3> axes = new ArrayList<>();
		for (BezierConnection bc : connections(world, pos).values()) {
			Vec3 axis = bc.axes.getFirst().normalize();
			boolean known = false;
			for (Vec3 existing : axes)
				if (Math.abs(existing.dot(axis)) > 0.999) {
					known = true;
					break;
				}
			if (!known)
				axes.add(axis);
		}
		if (axes.isEmpty())
			axes.add(new Vec3(0, 0, 1));
		return axes;
	}

	@Override
	public Vec3 getUpNormal(BlockGetter world, BlockPos pos, BlockState state) {
		return UP;
	}

	@Override
	public Vec3 getCurveStart(BlockGetter world, BlockPos pos, BlockState state, Vec3 axis) {
		return getAnchorPoint(world, pos);
	}

	@Override
	public boolean isSlope(BlockGetter world, BlockPos pos, BlockState state) {
		return false;
	}

	@Override
	public double getElevationAtCenter(BlockGetter world, BlockPos pos, BlockState state) {
		return 0;
	}

	@Override
	public Collection<DiscoveredLocation> getConnected(BlockGetter worldIn, BlockPos pos, BlockState state,
													   boolean linear, TrackNodeLocation connectedTo) {
		BlockGetter world = connectedTo != null && worldIn instanceof ServerLevel sl ? sl.getServer()
			.getLevel(connectedTo.dimension) : worldIn;
		List<DiscoveredLocation> list = new ArrayList<>();
		if (world == null || !state.getValue(HAS_BE))
			return list;
		Map<BlockPos, BezierConnection> connections = connections(world, pos);

		if (linear) {
			// An anchor has no straight track of its own, only its point. Create still asks for "linear" ends to find
			// a starting node for the graph and to locate stations/signals placed on curves leaving this block.
			if (connectedTo != null || connections.isEmpty())
				return list;
			BezierConnection bc = connections.values().iterator().next();
			ResourceKey<Level> dimension = world instanceof Level l ? l.dimension() : Level.OVERWORLD;
			list.add(new DiscoveredLocation(dimension, bc.starts.getFirst())
				.withNormal(UP)
				.withDirection(bc.axes.getFirst())
				.materials(bc.getMaterial(), bc.getMaterial())
				.withYOffset(bc.yOffsetAt(bc.starts.getFirst()))
				.forceNode());
			return list;
		}

		for (BezierConnection bc : connections.values())
			ITrackBlock.addToListIfConnected(connectedTo, list,
				(d, b) -> d == 1 ? Vec3.atLowerCornerOf(bc.bePositions.get(b)) : bc.starts.get(b), bc.normals::get,
				b -> world instanceof Level l ? l.dimension() : Level.OVERWORLD, bc::yOffsetAt, null, bc,
				(b, v) -> bc.getMaterial());
		return list;
	}

	@Override
	public BlockState overlay(BlockGetter world, BlockPos pos, BlockState existing, BlockState placed) {
		return existing;
	}

	@Override
	public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
		// Create removes HAS_BE once the last curve is gone; an anchor without curves has no purpose
		if (!level.isClientSide && !state.getValue(HAS_BE)) {
			level.scheduleTick(pos, this, 1);
			return;
		}
		super.onPlace(state, level, pos, oldState, isMoving);
	}

	@Override
	public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		// an anchor only exists to join curves; without any it is removed
		if (!state.getValue(HAS_BE) || connections(level, pos).isEmpty()) {
			level.removeBlock(pos, false);
			return;
		}
		// left with one curve (the track continuing from here was removed): a free end lies flat again
		AngledTrackPlacement.flattenIfFreeEnd(level, pos);
		super.tick(state, level, pos, random);
	}

	@Override
	protected boolean isRandomlyTicking(BlockState state) {
		return true;
	}

	/** Cleans up anchors that were left without curves (for example by older versions). */
	@Override
	protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (connections(level, pos).isEmpty())
			level.removeBlock(pos, false);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE_OUTLINE;
	}

	@Override
	public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
		return SHAPE_OUTLINE;
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	public BlockState rotate(BlockState state, Rotation rotation) {
		return state;
	}

	@Override
	public BlockState mirror(BlockState state, Mirror mirror) {
		return state;
	}

	@Override
	public BlockState getBogeyAnchor(BlockGetter world, BlockPos pos, BlockState state) {
		return state;
	}

	public static boolean isAnchor(BlockState state) {
		return state.getBlock() instanceof AngledTrackBlock;
	}

	public static boolean isAnchor(BlockEntity be) {
		return be != null && isAnchor(be.getBlockState());
	}
}

package com.davzelenec.railwaysunlimited;

import com.davzelenec.railwaysunlimited.block.AngledTrackBlock;
import com.simibubi.create.AllBlockEntityTypes;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RUBlocks {
	private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RailwaysUnlimited.MOD_ID);

	public static final DeferredBlock<AngledTrackBlock> ANGLED_TRACK = BLOCKS.register("angled_track",
		() -> new AngledTrackBlock(BlockBehaviour.Properties.of()
			.mapColor(MapColor.METAL)
			.strength(0.8f)
			.sound(SoundType.METAL)
			.noOcclusion()
			.noLootTable()
			.pushReaction(PushReaction.BLOCK)
			.forceSolidOn()));

	private RUBlocks() {}

	public static void register(IEventBus modBus) {
		BLOCKS.register(modBus);
		modBus.addListener(RUBlocks::addToCreateTrackBlockEntity);
	}

	/** Anchors store their curves in Create's own track block entity, so Create's renderers and logic apply. */
	private static void addToCreateTrackBlockEntity(BlockEntityTypeAddBlocksEvent event) {
		event.modify(AllBlockEntityTypes.TRACK.get(), ANGLED_TRACK.get());
	}
}

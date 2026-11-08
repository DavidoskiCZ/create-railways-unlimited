package com.davzelenec.railwaysunlimited.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllTags;
import com.simibubi.create.content.equipment.toolbox.ToolboxHandlerClient;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * Left Alt is also Create's "Access Nearby Toolboxes" key. While a track connection is being placed it holds this
 * mod's straight-track mode instead, so a nearby toolbox must not open its menu.
 */
@Mixin(ToolboxHandlerClient.class)
public abstract class ToolboxHandlerClientMixin {
	@Inject(method = "onKeyInput", at = @At("HEAD"), cancellable = true)
	private static void railwaysUnlimited$keepAltForTrack(int key, boolean pressed, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null)
			return;
		ItemStack held = mc.player.getMainHandItem();
		if (AllTags.AllBlockTags.TRACKS.matches(held) && held.has(AllDataComponents.TRACK_CONNECTING_FROM))
			ci.cancel();
	}
}

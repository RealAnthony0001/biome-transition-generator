package net.biometransitions.mixin;

import net.biometransitions.client.ColorBlending;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Biome Color Blending: wider grass/foliage/water colour blending than the vanilla option allows. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "calculateBlockTint", at = @At("HEAD"), cancellable = true)
    private void biometransitions$blend(BlockPos pos, ColorResolver resolver, CallbackInfoReturnable<Integer> cir) {
        Integer color = ColorBlending.tint((ClientLevel) (Object) this, pos, resolver);
        if (color != null) cir.setReturnValue(color);
    }
}

package net.biometransitions.mixin.compat;

import net.biometransitions.client.ColorBlending;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Biome Color Blending with Sodium (0.6 and later). Sodium meshes chunks from its own snapshot of the world
 * ({@code LevelSlice}) and blends grass, foliage and water colours itself with the vanilla Biome Blend radius,
 * so {@code ClientLevel.calculateBlockTint} (see {@code ClientLevelMixin}) is never asked for terrain colours.
 * Every block and fluid tint Sodium renders goes through {@code LevelSlice.getBlockTint}; answering there with
 * the mod's blended colour gives the same colours as without Sodium. When the mod's blending is off (distance 0,
 * mod disabled) Sodium's own blending runs unchanged.
 * <p>
 * Pseudo mixin with string target: no compile dependency on Sodium, and only applied when Sodium is present
 * ({@link SodiumMixinPlugin}).
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice", remap = false)
abstract class SodiumLevelSliceMixin {
    @Shadow @Final private ClientLevel level;

    @Inject(method = "getBlockTint(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/ColorResolver;)I",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void biometransitions$blend(BlockPos pos, ColorResolver resolver, CallbackInfoReturnable<Integer> cir) {
        Integer color = ColorBlending.tint(level, pos, resolver);
        if (color != null) cir.setReturnValue(color);
    }
}

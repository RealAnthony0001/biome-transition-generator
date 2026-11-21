package net.biometransitions.mixin;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.biometransitions.client.ColorBlending;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Biome Color Blending: wider grass/foliage/water colour blending than the vanilla option allows. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Shadow @Final private Object2ObjectArrayMap<ColorResolver, BlockTintCache> tintCaches;
    @Shadow @Final private LevelRenderer levelRenderer;

    @Inject(method = "calculateBlockTint", at = @At("HEAD"), cancellable = true)
    private void biometransitions$blend(BlockPos pos, ColorResolver resolver, CallbackInfoReturnable<Integer> cir) {
        Integer color = ColorBlending.tint((ClientLevel) (Object) this, pos, resolver);
        if (color != null) cir.setReturnValue(color);
    }

    /** Colours near a newly arrived chunk may have been computed without it. */
    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void biometransitions$refreshNearbyTints(ChunkPos pos, CallbackInfo ci) {
        ColorBlending.chunkLoaded((ClientLevel) (Object) this, pos, tintCaches.values(), levelRenderer);
    }
}

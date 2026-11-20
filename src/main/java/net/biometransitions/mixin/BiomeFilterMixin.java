package net.biometransitions.mixin;

import net.biometransitions.worldgen.Suppression;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BiomeFilter.class)
abstract class BiomeFilterMixin {
    @Inject(method = "shouldPlace", at = @At("RETURN"), cancellable = true)
    private void biometransitions$thinOut(PlacementContext ctx, RandomSource random, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && Suppression.suppress(ctx, pos)) cir.setReturnValue(false);
    }
}

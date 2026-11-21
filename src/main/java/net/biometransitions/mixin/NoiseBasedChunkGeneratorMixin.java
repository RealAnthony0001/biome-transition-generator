package net.biometransitions.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.biometransitions.core.BiomeReplacer;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Biome Replacing: chunks store the replacement biome for quarts of a too-small area. */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class NoiseBasedChunkGeneratorMixin {
    @WrapOperation(method = "doCreateBiomes", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;Lnet/minecraft/world/level/biome/Climate$Sampler;)V"))
    private void biometransitions$replaceSmallBiomes(ChunkAccess chunk, BiomeResolver resolver, Climate.Sampler sampler,
                                                     Operation<Void> original, @Local(argsOnly = true) RandomState random) {
        var source = ((ChunkGenerator) (Object) this).getBiomeSource();
        BiomeResolver replacing = (x, y, z, s) -> BiomeReplacer.apply(source, random, resolver.getNoiseBiome(x, y, z, s), x, y, z);
        original.call(chunk, replacing, sampler);
    }
}

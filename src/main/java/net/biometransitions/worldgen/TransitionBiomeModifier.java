package net.biometransitions.worldgen;

import com.mojang.serialization.MapCodec;
import net.biometransitions.BiomeTransitions;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

/**
 * Puts one transition feature per decoration step at the start of every biome's list, after all other
 * modifiers ran. First, so the neighbour's plants reach bare ground before the biome's own cover it.
 */
public record TransitionBiomeModifier() implements BiomeModifier {
    public static final MapCodec<TransitionBiomeModifier> CODEC = MapCodec.unit(TransitionBiomeModifier::new);

    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        // First in every step's list everywhere, so the feature sorter never sees a cycle.
        if (phase != Phase.AFTER_EVERYTHING) return;
        for (GenerationStep.Decoration step : GenerationStep.Decoration.values())
            builder.getGenerationSettings().getFeatures(step).add(0, BiomeTransitions.placedFor(step));
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec() { return CODEC; }
}

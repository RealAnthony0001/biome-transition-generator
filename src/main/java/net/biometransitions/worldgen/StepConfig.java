package net.biometransitions.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/** Which decoration step a transition feature instance handles. */
public record StepConfig(GenerationStep.Decoration step) implements FeatureConfiguration {
    public static final Codec<StepConfig> CODEC = StringRepresentable.fromEnum(GenerationStep.Decoration::values)
            .fieldOf("step").xmap(StepConfig::new, StepConfig::step).codec();
}

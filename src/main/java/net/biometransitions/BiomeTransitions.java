package net.biometransitions;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import net.biometransitions.config.BtgConfig;
import net.biometransitions.core.BiomeClassifier;
import net.biometransitions.core.BoundaryDetector;
import net.biometransitions.core.BiomeReplacer;
import net.biometransitions.core.Density;
import net.biometransitions.core.FeatureCatalog;
import net.biometransitions.core.Mixing;
import net.biometransitions.debug.BoundaryVisualizer;
import net.biometransitions.debug.BtgCommands;
import net.biometransitions.worldgen.StepConfig;
import net.biometransitions.worldgen.StructureExclusion;
import net.biometransitions.worldgen.TransitionBiomeModifier;
import net.biometransitions.worldgen.TransitionFeature;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.slf4j.Logger;

import java.util.List;

@Mod(BiomeTransitions.MODID)
public final class BiomeTransitions {
    public static final String MODID = "biometransitions";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, MODID);
    public static final DeferredHolder<Feature<?>, TransitionFeature> TRANSITION = FEATURES.register("transition", TransitionFeature::new);
    private static final DeferredRegister<MapCodec<? extends BiomeModifier>> MODIFIERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, MODID);

    static {
        MODIFIERS.register("transitions", () -> TransitionBiomeModifier.CODEC);
    }

    @SuppressWarnings("unchecked")
    private static final Holder<PlacedFeature>[] PLACED = new Holder[GenerationStep.Decoration.values().length];

    public BiomeTransitions(IEventBus modBus) {
        FEATURES.register(modBus);
        MODIFIERS.register(modBus);
        BtgConfig.load();
        NeoForge.EVENT_BUS.addListener(BtgCommands::register);
        NeoForge.EVENT_BUS.addListener(BoundaryVisualizer::tick);
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent e) -> BtgConfig.load());
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> invalidateCaches());
    }

    /** One shared placed feature per step; the same instance goes into every biome. */
    public static synchronized Holder<PlacedFeature> placedFor(GenerationStep.Decoration step) {
        int i = step.ordinal();
        if (PLACED[i] == null)
            PLACED[i] = Holder.direct(new PlacedFeature(Holder.direct(new ConfiguredFeature<>(TRANSITION.get(), new StepConfig(step))), List.of()));
        return PLACED[i];
    }

    public static void invalidateCaches() {
        BoundaryDetector.clear();
        BiomeClassifier.clear();
        FeatureCatalog.clear();
        Mixing.clear();
        Density.clear();
        BiomeReplacer.clear();
        StructureExclusion.clear();
    }
}

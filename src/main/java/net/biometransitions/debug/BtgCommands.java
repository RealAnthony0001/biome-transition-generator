package net.biometransitions.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.biometransitions.config.BtgConfig;
import net.biometransitions.core.BiomeClassifier;
import net.biometransitions.core.BoundaryDetector;
import net.biometransitions.core.BiomeReplacer;
import net.biometransitions.core.FeatureCatalog;
import net.biometransitions.core.Mixing;
import net.biometransitions.core.Mixing.Channel;
import net.biometransitions.core.Transitions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep.Decoration;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** /btg info | features | stats | reload | show */
public final class BtgCommands {
    private BtgCommands() {}

    public static void register(RegisterCommandsEvent event) {
        var biomeArg = ResourceArgument.resource(event.getBuildContext(), Registries.BIOME);
        event.getDispatcher().register(Commands.literal("btg").requires(s -> s.hasPermission(2))
                .then(Commands.literal("info").executes(BtgCommands::info))
                .then(Commands.literal("features").executes(c -> features(c, null))
                        .then(Commands.argument("biome", biomeArg)
                                .executes(c -> features(c, ResourceArgument.getResource(c, "biome", Registries.BIOME)))))
                .then(Commands.literal("stats").executes(BtgCommands::stats)
                        .then(Commands.literal("reset").executes(c -> {
                            Stats.reset();
                            return say(c, "Statistics reset.");
                        })))
                .then(Commands.literal("reload").executes(c -> {
                    BtgConfig.load();
                    return say(c, "Config reloaded. Only newly generated chunks use it.");
                }))
                .then(Commands.literal("show").executes(c -> show(c, 24))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 64))
                                .executes(c -> show(c, IntegerArgumentType.getInteger(c, "radius"))))));
    }

    private static int say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPos.containing(c.getSource().getPosition());
        var gen = level.getChunkSource().getGenerator();
        var rs = level.getChunkSource().randomState();
        long seed = level.getSeed();
        Holder<Biome> own = level.getBiome(pos);
        var ns = BoundaryDetector.zoneMap(gen, rs, seed, pos).neighbours(pos.getX(), pos.getZ());
        StringBuilder sb = new StringBuilder("Biome ").append(BiomeClassifier.id(own))
                .append(' ').append(BiomeClassifier.categories(own)).append('\n');
        if (BiomeReplacer.featuresSuppressed(gen.getBiomeSource(), rs, pos.getX(), pos.getZ(), own))
            sb.append("Small biome: own features suppressed, neighbours fill it.\n");
        if (ns.length == 0) sb.append("No biome within ").append(Mixing.maxWidth()).append(" blocks that may mix in here.");
        var veg = Transitions.at(gen, rs, seed, pos, own, Channel.FEATURES);
        var trees = Transitions.at(gen, rs, seed, pos, own, Channel.TREES);
        var fluid = Transitions.at(gen, rs, seed, pos, own, Channel.FLUID);
        var ground = Transitions.at(gen, rs, seed, pos, own, Channel.SURFACE);
        double foreignTotal = 0;
        java.util.Set<Holder<Biome>> listed = new java.util.HashSet<>();
        for (var n : ns) {
            if (!listed.add(n.biome())) continue; // a nearer cell of it was listed already
            double vs = Transitions.shareOf(veg, n.biome());
            foreignTotal += vs;
            var rule = Mixing.between(own, n.biome(), Channel.FEATURES);
            var treeRule = Mixing.between(own, n.biome(), Channel.TREES);
            var groundRule = Mixing.between(own, n.biome(), Channel.SURFACE);
            sb.append(String.format("→ %s %s  %.1f blocks%s%n   features: %s  share %.0f%% | trees: %s  share %.0f%% | ground: %s  share %.0f%% | fluid %.0f%%%n",
                    BiomeClassifier.id(n.biome()), BiomeClassifier.categories(n.biome()),
                    n.distance(BtgConfig.get().mixing.distribution.shape),
                    n.featureless() ? "  (small area, spreads no features)" : "",
                    rule == null ? "do not mix in" : String.format("zone %.0f blocks, %s", rule.width(), rule.curve()), vs * 100,
                    treeRule == null ? "do not mix in" : String.format("zone %.0f blocks", treeRule.width()), Transitions.shareOf(trees, n.biome()) * 100,
                    groundRule == null ? "does not blend in" : String.format("zone %.0f blocks", groundRule.width()), Transitions.shareOf(ground, n.biome()) * 100,
                    Transitions.shareOf(fluid, n.biome()) * 100));
        }
        if (ns.length > 0) sb.append(String.format("Feature ratio own/foreign: %.0f / %.0f", (1 - foreignTotal) * 100, foreignTotal * 100));
        return say(c, sb.toString().trim());
    }

    private static int features(CommandContext<CommandSourceStack> c, Holder<Biome> biome) {
        if (biome == null) biome = c.getSource().getLevel().getBiome(BlockPos.containing(c.getSource().getPosition()));
        StringBuilder sb = new StringBuilder("Features of ").append(BiomeClassifier.id(biome)).append(":\n");
        for (Decoration step : Decoration.values())
            for (var e : FeatureCatalog.all(biome, step)) {
                String why = FeatureCatalog.rejectFor(e, step, null);
                sb.append(why == null ? "✔ " : "✘ ").append(step.name().toLowerCase()).append(' ').append(e.id())
                        .append(" [").append(e.category()).append(", ").append(e.type()).append(']')
                        .append(e.hasBiomeFilter() ? " biome filter → zone share" : "")
                        .append(why == null ? "" : " — " + why).append('\n');
            }
        return say(c, sb.toString().trim());
    }

    private static int stats(CommandContext<CommandSourceStack> c) {
        var snap = Stats.snapshot();
        if (snap.isEmpty()) return say(c, "No statistics yet (generate new chunks, collectStats must be on).");
        StringBuilder sb = new StringBuilder("Transition statistics:\n");
        snap.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        return say(c, sb.toString().trim());
    }


    private static int show(CommandContext<CommandSourceStack> c, int radius) {
        var player = c.getSource().getPlayer();
        if (player == null) return say(c, "Players only.");
        return say(c, BoundaryVisualizer.toggle(player, radius)
                ? "Showing boundaries (red walls), vegetation zone (green → yellow near the border), fluid zone (blue). Run again to hide."
                : "Boundary display off.");
    }
}

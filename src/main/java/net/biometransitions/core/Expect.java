package net.biometransitions.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Pure helpers (Gson only, no Minecraft classes) so they are unit-testable. */
public final class Expect {
    private Expect() {}

    /** Expected value of an encoded IntProvider / FloatProvider: a number, constant, uniform, weighted list... */
    public static double value(JsonElement e) {
        if (e == null || e.isJsonNull()) return 1;
        if (e.isJsonPrimitive()) return e.getAsDouble();
        if (!e.isJsonObject()) return 1;
        JsonObject o = e.getAsJsonObject();
        if (o.has("value")) return value(o.get("value"));
        if (o.has("distribution")) {
            double sum = 0, weights = 0;
            for (JsonElement entry : o.getAsJsonArray("distribution")) {
                JsonObject w = entry.getAsJsonObject();
                double weight = w.has("weight") ? w.get("weight").getAsDouble() : 1;
                sum += weight * value(w.get("data"));
                weights += weight;
            }
            return weights <= 0 ? 0 : sum / weights;
        }
        if (o.has("source")) return value(o.get("source"));
        if (o.has("mean")) return o.get("mean").getAsDouble();
        if (o.has("min_inclusive") && o.has("max_inclusive"))
            return (o.get("min_inclusive").getAsDouble() + o.get("max_inclusive").getAsDouble()) / 2;
        return 1;
    }

    /** Expected placement count of one encoded placement modifier, as a multiplier. */
    public static double modifier(JsonObject m) {
        String type = m.has("type") ? m.get("type").getAsString() : "";
        return switch (type) {
            case "minecraft:count", "minecraft:count_on_every_layer" -> value(m.get("count"));
            case "minecraft:rarity_filter" -> 1.0 / Math.max(1, m.get("chance").getAsInt());
            case "minecraft:noise_threshold_count" -> (m.get("below_noise").getAsDouble() + m.get("above_noise").getAsDouble()) / 2;
            case "minecraft:noise_based_count" -> Math.max(0, m.get("noise_to_count_ratio").getAsDouble()
                    * (m.has("noise_offset") ? m.get("noise_offset").getAsDouble() : 0));
            default -> 1;
        };
    }

    /** "ocean-river, forest-plains" -> [[ocean, river], [forest, plains]]; malformed entries are skipped. */
    public static List<String[]> mapping(String text) {
        List<String[]> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split(",")) {
            int i = part.indexOf('-');
            if (i <= 0 || i == part.length() - 1) continue;
            String from = part.substring(0, i).trim(), to = part.substring(i + 1).trim();
            if (!from.isEmpty() && !to.isEmpty()) out.add(new String[]{from, to});
        }
        return out;
    }
}

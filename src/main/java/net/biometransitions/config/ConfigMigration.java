package net.biometransitions.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Pure JSON migration of older config files (Gson only, so it is unit-testable). */
public final class ConfigMigration {
    private ConfigMigration() {}

    /**
     * Brings an older file up to date in place, keeping every value: version 4 had a separate "detection"
     * section (now part of "replacing") and one surface on/off switch per pair (now a direction); up to
     * version 5 a pair's tree and block distance of -1 meant the global setting (now 129, while -1 is the
     * pair's own Mixing Distance).
     *
     * @param version {@code configVersion} of the file
     */
    public static void migrate(JsonObject root, int version) {
        if (root.get("detection") instanceof JsonObject detection) {
            JsonObject replacing = root.get("replacing") instanceof JsonObject r ? r : new JsonObject();
            for (var e : detection.entrySet()) if (!replacing.has(e.getKey())) replacing.add(e.getKey(), e.getValue());
            root.add("replacing", replacing);
            root.remove("detection");
        }
        if (root.get("pairs") != null && root.get("pairs").isJsonArray())
            for (JsonElement el : root.getAsJsonArray("pairs")) {
                if (!(el instanceof JsonObject pair) || !pair.has("surfaceMixing")) continue;
                boolean surface = !pair.get("surfaceMixing").isJsonPrimitive() || pair.get("surfaceMixing").getAsBoolean();
                pair.remove("surfaceMixing");
                if (pair.has("blockDirection")) continue;
                // the old switch followed the feature direction
                String mode = pair.has("mode") && pair.get("mode").isJsonPrimitive() ? pair.get("mode").getAsString() : "BOTH";
                String dir = !surface ? "NO_BLENDING" : switch (mode) {
                    case "A_INTO_B" -> "A_INTO_B";
                    case "B_INTO_A" -> "B_INTO_A";
                    case "DO_NOT_BLEND" -> "NO_BLENDING";
                    default -> "BLEND_BOTH";
                };
                pair.add("blockDirection", new JsonPrimitive(dir));
            }
        if (version < 6 && root.get("pairs") != null && root.get("pairs").isJsonArray())
            for (JsonElement el : root.getAsJsonArray("pairs")) {
                if (!(el instanceof JsonObject pair)) continue;
                for (String key : new String[]{"treeDistance", "blockDistance"})
                    if (pair.get(key) instanceof JsonPrimitive v && v.isNumber() && v.getAsInt() < 0)
                        pair.add(key, new JsonPrimitive(129)); // BtgConfig.PairRule.GLOBAL (kept Gson-only for tests)
            }
    }

}

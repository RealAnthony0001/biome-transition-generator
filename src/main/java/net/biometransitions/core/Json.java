package net.biometransitions.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Partial-override merge: category and feature settings only store what differs. */
public final class Json {
    private Json() {}

    public static JsonObject merge(JsonObject base, JsonObject over) {
        JsonObject out = base.deepCopy();
        if (over == null) return out;
        for (var e : over.entrySet()) {
            JsonElement b = out.get(e.getKey());
            out.add(e.getKey(), b != null && b.isJsonObject() && e.getValue().isJsonObject()
                    ? merge(b.getAsJsonObject(), e.getValue().getAsJsonObject()) : e.getValue().deepCopy());
        }
        return out;
    }

}

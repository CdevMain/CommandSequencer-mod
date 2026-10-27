package csequencer.client;

import com.google.gson.JsonObject;
import csequencer.CommandSequencerMod;
import csequencer.core.json.J;
import csequencer.core.project.FileProjectStore;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Per-user client preferences (config/csequencer-client.json). Separate from projects and server state. */
public final class ClientConfig {
    public enum Position { TOP_CENTER, TOP_LEFT, TOP_RIGHT }

    public static boolean overlayEnabled = true;
    public static boolean collapsed = false;
    public static Position position = Position.TOP_CENTER;
    public static int offsetY = 4;
    public static double scale = 1.0;
    public static double opacity = 0.85;
    /** Overrides per-sequence "keep visible": always show while a sequence runs. */
    public static boolean alwaysVisible = false;
    /** Seconds the overlay stays after a change when the sequence does not keep it visible. */
    public static int displaySeconds = 6;
    public static boolean animations = true;
    public static int maxEntries = 3;
    public static double audioVolume = 1.0;
    public static boolean downloadAudio = true;
    public static int editorSnap = 5;
    /** Editor Pieces panel: 0 = expanded, 1 = icon rail, 2 = hidden. */
    public static int paletteMode = 0;
    /** Words scattered on screen by the "insanity" Screen Effect+ when the piece does not set its own. */
    public static java.util.List<String> insanityWords = new java.util.ArrayList<>(java.util.List.of(
            "RUN", "THEY SEE YOU", "WAKE UP", "NOT REAL", "BEHIND YOU", "HELP ME", "IT'S COMING", "DON'T LOOK",
            "NO ESCAPE", "WHO ARE YOU?", "LIAR", "YOU FORGOT", "STOP", "WE ARE HERE"));

    private ClientConfig() {}

    static Path path() { return FMLPaths.CONFIGDIR.get().resolve("csequencer-client.json"); }

    public static void load() {
        Path p = path();
        if (!Files.exists(p)) { save(); return; }
        try {
            JsonObject o = J.parseObject(Files.readString(p, StandardCharsets.UTF_8));
            overlayEnabled = J.b(o, "overlayEnabled", overlayEnabled);
            collapsed = J.b(o, "collapsed", collapsed);
            position = J.en(o, "position", Position.class, position);
            offsetY = clamp(J.i(o, "offsetY", offsetY), 0, 200);
            scale = Math.max(0.5, Math.min(2.0, J.d(o, "scale", scale)));
            opacity = Math.max(0.2, Math.min(1.0, J.d(o, "opacity", opacity)));
            alwaysVisible = J.b(o, "alwaysVisible", alwaysVisible);
            displaySeconds = clamp(J.i(o, "displaySeconds", displaySeconds), 1, 120);
            animations = J.b(o, "animations", animations);
            maxEntries = clamp(J.i(o, "maxEntries", maxEntries), 1, 6);
            audioVolume = Math.max(0, Math.min(1, J.d(o, "audioVolume", audioVolume)));
            downloadAudio = J.b(o, "downloadAudio", downloadAudio);
            editorSnap = clamp(J.i(o, "editorSnap", editorSnap), 0, 200);
            paletteMode = clamp(J.i(o, "paletteMode", paletteMode), 0, 2);
            if (o.has("insanityWords")) {
                java.util.List<String> w = new java.util.ArrayList<>();
                for (String s : J.strings(o, "insanityWords")) if (!s.isBlank() && w.size() < 200) w.add(s.length() > 60 ? s.substring(0, 60) : s);
                if (!w.isEmpty()) insanityWords = w;
            }
        } catch (Exception e) {
            CommandSequencerMod.LOGGER.warn("[CSQ] Client config unreadable, using defaults: {}", e.getMessage());
        }
    }

    public static void save() {
        JsonObject o = new JsonObject();
        o.addProperty("overlayEnabled", overlayEnabled);
        o.addProperty("collapsed", collapsed);
        o.addProperty("position", position.name());
        o.addProperty("offsetY", offsetY);
        o.addProperty("scale", scale);
        o.addProperty("opacity", opacity);
        o.addProperty("alwaysVisible", alwaysVisible);
        o.addProperty("displaySeconds", displaySeconds);
        o.addProperty("animations", animations);
        o.addProperty("maxEntries", maxEntries);
        o.addProperty("audioVolume", audioVolume);
        o.addProperty("downloadAudio", downloadAudio);
        o.addProperty("editorSnap", editorSnap);
        o.addProperty("paletteMode", paletteMode);
        o.add("insanityWords", J.toArray(insanityWords));
        try {
            FileProjectStore.writeAtomic(path(), J.PRETTY.toJson(o));
        } catch (RuntimeException e) {
            CommandSequencerMod.LOGGER.warn("[CSQ] Could not save client config: {}", e.getMessage());
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}

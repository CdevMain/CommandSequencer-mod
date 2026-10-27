package csequencer.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import csequencer.core.json.J;
import csequencer.core.model.AudioRef;
import csequencer.core.model.ConditionGraph;
import csequencer.core.model.Project;
import csequencer.core.model.Timeline;
import csequencer.core.pieces.EventType;
import csequencer.core.pieces.ParamSpec;
import csequencer.core.pieces.ParamType;
import csequencer.core.pieces.PieceRegistry;
import csequencer.core.pieces.QueryType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Builds typed editors for piece / node parameters into a {@link Form}. */
public final class ParamForm {
    /** What the form needs from the screen hosting it. */
    public interface Host {
        CsqScreen screen();

        Project project();

        /** Called before a change (undo snapshot). {@code key} groups keystrokes of one field. */
        void beforeEdit(String key);

        /** Called after a value changed (no structural change). */
        void changed();

        /** Called after a structural change: the form must be rebuilt. */
        void rebuild();

        void editCondition(String title, JsonObject graph, Consumer<JsonObject> apply);

        default void openFunction(String id) {}

        default void createFunction(Consumer<String> onCreated) {}

        default void importAudio(Consumer<String> onAudioId) {}
    }

    private ParamForm() {}

    public static void build(Form f, List<ParamSpec> specs, JsonObject params, Host host, String keyPrefix) {
        for (ParamSpec spec : specs) editor(f, spec, params, host, keyPrefix + spec.key);
    }

    private static String str(JsonElement e) {
        if (e == null || e.isJsonNull()) return "";
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    private static JsonElement get(JsonObject params, ParamSpec spec) {
        JsonElement e = params.get(spec.key);
        return e == null ? spec.def : e;
    }

    /** Numbers stay numbers when they parse; anything else (templates, "2s") stays text. */
    private static JsonElement smart(String s, ParamType t) {
        String v = s.trim();
        if (t == ParamType.INT || t == ParamType.NUMBER || t == ParamType.DURATION) {
            try {
                double d = Double.parseDouble(v);
                return d == Math.rint(d) ? new JsonPrimitive((long) d) : new JsonPrimitive(d);
            } catch (NumberFormatException ignored) {}
        }
        return new JsonPrimitive(s);
    }

    static void editor(Form f, ParamSpec spec, JsonObject params, Host host, String key) {
        int W = f.width();
        JsonElement cur = get(params, spec);
        String help = spec.help.isEmpty() ? "" : " — " + spec.help;
        switch (spec.type) {
            case BOOL -> {
                boolean v = cur != null && cur.isJsonPrimitive() && (cur.getAsJsonPrimitive().isBoolean() ? cur.getAsBoolean() : "true".equals(str(cur)));
                f.row(f.toggle(spec.label, v, W, b -> {
                    host.beforeEdit(key + "!");
                    params.addProperty(spec.key, b);
                    host.changed();
                }));
            }
            case ENUM, VAR_SCOPE -> f.row(f.cycle(spec.label + ": ", spec.options, str(cur), W, s -> {
                host.beforeEdit(key + "!");
                params.addProperty(spec.key, s);
                host.changed();
            }));
            case EVENT_TYPE -> {
                f.label(spec.label);
                EventType et = PieceRegistry.event(str(cur));
                f.row(f.button((et == null ? str(cur) : et.name()) + " ▾", W, () -> {
                    List<PickerScreen.Option> opts = new ArrayList<>();
                    for (EventType e : PieceRegistry.events()) opts.add(new PickerScreen.Option(e.name(), e.description() + "  (fields: " + String.join(", ", e.fields()) + ")", e.id(),
                            e.internal() ? 0xFF7E57C2 : 0xFFFFA726, e.internal() ? "Internal" : e.id().startsWith("entity") ? "Entities" : "Players"));
                    opts.sort(java.util.Comparator.comparingInt(o -> o.group().equals("Players") ? 0 : o.group().equals("Entities") ? 1 : 2));
                    pick(host, "Choose event", opts, v -> { host.beforeEdit(key + "!"); params.addProperty(spec.key, v); host.changed(); host.rebuild(); });
                }));
            }
            case CONDITION -> {
                JsonObject g = cur != null && cur.isJsonObject() ? cur.getAsJsonObject() : new JsonObject();
                int nodes = J.arr(g, "nodes").size();
                f.label(spec.label);
                f.row(f.button(nodes == 0 ? "Add condition… (always true)" : "Edit logic (" + nodes + " node" + (nodes == 1 ? "" : "s") + ")", W,
                        () -> host.editCondition(spec.label, g, out -> { host.beforeEdit(key + "!"); params.add(spec.key, out); host.changed(); host.rebuild(); })));
            }
            case FUNCTION -> functionField(f, spec.label, str(cur), host, v -> { host.beforeEdit(key + "!"); params.addProperty(spec.key, v); host.changed(); host.rebuild(); });
            case FUNCTIONS -> {
                JsonArray arr = cur != null && cur.isJsonArray() ? cur.getAsJsonArray() : new JsonArray();
                f.label(spec.label + " (" + arr.size() + ")");
                for (int i = 0; i < arr.size(); i++) {
                    int idx = i;
                    functionRow(f, str(arr.get(i)), host, W - 20, v -> { host.beforeEdit(key + "!"); arr.set(idx, new JsonPrimitive(v)); params.add(spec.key, arr); host.changed(); host.rebuild(); },
                            () -> { host.beforeEdit(key + "!"); arr.remove(idx); params.add(spec.key, arr); host.changed(); host.rebuild(); });
                }
                f.row(f.button("+ Add branch function", W, () -> { host.beforeEdit(key + "!"); arr.add(""); params.add(spec.key, arr); host.changed(); host.rebuild(); }));
            }
            case BRANCHES -> {
                JsonArray arr = cur != null && cur.isJsonArray() ? cur.getAsJsonArray() : new JsonArray();
                for (int i = 0; i < arr.size(); i++) {
                    int idx = i;
                    JsonObject b = arr.get(i).isJsonObject() ? arr.get(i).getAsJsonObject() : new JsonObject();
                    JsonObject g = J.obj(b, "condition");
                    int nodes = g == null ? 0 : J.arr(g, "nodes").size();
                    f.label((i == 0 ? "IF" : "ELSE IF") + " #" + (i + 1), Theme.INFO);
                    f.row(f.button("Condition (" + nodes + ")", W - 20, () -> host.editCondition("Branch " + (idx + 1), g == null ? new JsonObject() : g, out -> {
                        host.beforeEdit(key + "!"); b.add("condition", out); arr.set(idx, b); params.add(spec.key, arr); host.changed(); host.rebuild();
                    })), f.button("✕", 18, () -> { host.beforeEdit(key + "!"); arr.remove(idx); params.add(spec.key, arr); host.changed(); host.rebuild(); }));
                    functionField(f, "then run", J.str(b, "target", ""), host, v -> { host.beforeEdit(key + "!"); b.addProperty("target", v); arr.set(idx, b); params.add(spec.key, arr); host.changed(); host.rebuild(); });
                }
                f.row(f.button(arr.size() == 0 ? "+ Add IF branch" : "+ Add ELSE IF", W, () -> {
                    host.beforeEdit(key + "!");
                    JsonObject b = new JsonObject();
                    b.add("condition", new JsonObject());
                    b.addProperty("target", "");
                    arr.add(b);
                    params.add(spec.key, arr);
                    host.changed();
                    host.rebuild();
                }));
            }
            case CASES, OUTCOMES -> {
                boolean cases = spec.type == ParamType.CASES;
                JsonArray arr = cur != null && cur.isJsonArray() ? cur.getAsJsonArray() : new JsonArray();
                f.label(spec.label + " (" + arr.size() + ")");
                for (int i = 0; i < arr.size(); i++) {
                    int idx = i;
                    JsonObject c = arr.get(i).isJsonObject() ? arr.get(i).getAsJsonObject() : new JsonObject();
                    String field = cases ? "match" : "weight";
                    EditBox box = f.text(cases ? "value" : "weight", str(c.get(field)), 52, s -> {
                        host.beforeEdit(key + idx);
                        if (cases) c.addProperty(field, s);
                        else c.add(field, smart(s, ParamType.NUMBER));
                        arr.set(idx, c);
                        params.add(spec.key, arr);
                        host.changed();
                    });
                    Timeline target = host.project().function(J.str(c, "target", ""));
                    f.row(box, f.button((target == null ? "(function)" : target.name) + " ▾", W - 76, () -> pickFunction(host, v -> {
                        host.beforeEdit(key + "!"); c.addProperty("target", v); arr.set(idx, c); params.add(spec.key, arr); host.changed(); host.rebuild();
                    })), f.button("✕", 18, () -> { host.beforeEdit(key + "!"); arr.remove(idx); params.add(spec.key, arr); host.changed(); host.rebuild(); }));
                }
                f.row(f.button(cases ? "+ Add case" : "+ Add outcome", W, () -> {
                    host.beforeEdit(key + "!");
                    JsonObject c = new JsonObject();
                    if (cases) c.addProperty("match", "");
                    else c.addProperty("weight", 1);
                    c.addProperty("target", "");
                    arr.add(c);
                    params.add(spec.key, arr);
                    host.changed();
                    host.rebuild();
                }));
            }
            case TARGET -> targetEditor(f, spec, params, host, key);
            case QUERY -> {
                f.label(spec.label + help);
                queryEditor(f, cur, host, key, v -> { params.add(spec.key, v); });
            }
            case AUDIO -> {
                f.label(spec.label);
                AudioRef a = host.project().audio(str(cur));
                f.row(f.button((a == null ? "(choose audio)" : a.name) + " ▾", W - 52, () -> {
                    List<PickerScreen.Option> opts = new ArrayList<>();
                    for (AudioRef r : host.project().audio) opts.add(new PickerScreen.Option(r.name, r.format + " · " + (r.durationMs / 1000) + "s · " + r.hash.substring(0, 8), r.id));
                    pick(host, "Choose audio", opts, v -> { host.beforeEdit(key + "!"); params.addProperty(spec.key, v); host.changed(); host.rebuild(); });
                }), f.button("Import", 50, () -> host.importAudio(id -> { host.beforeEdit(key + "!"); params.addProperty(spec.key, id); host.changed(); host.rebuild(); })));
            }
            case VEC3 -> {
                f.label(spec.label + help);
                EditBox box = f.text("x y z", str(cur), W - 24, s -> { host.beforeEdit(key); params.addProperty(spec.key, s); host.changed(); });
                f.row(box, f.button("@", 20, () -> {
                    var p = Minecraft.getInstance().player;
                    if (p == null) return;
                    host.beforeEdit(key + "!");
                    params.addProperty(spec.key, p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ());
                    host.changed();
                    host.rebuild();
                }));
            }
            case PLAYER -> {
                f.label(spec.label + help);
                EditBox box = f.text("player name or {placeholder}", str(cur), W - 22, s -> { host.beforeEdit(key); params.addProperty(spec.key, s); host.changed(); });
                f.row(box, f.button("▾", 19, () -> pickOnlinePlayer(host, name -> {
                    host.beforeEdit(key + "!");
                    params.addProperty(spec.key, name);
                    host.changed();
                    host.rebuild();
                })));
            }
            case JSON -> {
                f.label(spec.label + " (JSON)");
                f.row(f.text("{ }", cur == null || cur.isJsonNull() ? "{}" : cur.toString(), W, s -> {
                    try {
                        JsonElement e = com.google.gson.JsonParser.parseString(s.isBlank() ? "{}" : s);
                        host.beforeEdit(key);
                        params.add(spec.key, e);
                        host.changed();
                    } catch (Exception ignored) {
                        // keep typing; invalid JSON is not applied
                    }
                }));
            }
            default -> {
                f.label(spec.label + help);
                f.row(f.text(spec.label, str(cur), W, s -> { host.beforeEdit(key); params.add(spec.key, smart(s, spec.type)); host.changed(); }));
            }
        }
    }

    private static void functionField(Form f, String label, String current, Host host, Consumer<String> set) {
        f.label(label);
        functionRow(f, current, host, f.width(), set, null);
    }

    private static void functionRow(Form f, String current, Host host, int width, Consumer<String> set, Runnable remove) {
        Timeline t = host.project().function(current);
        String name = current.isEmpty() ? "(none)" : t == null ? current + " (missing!)" : t.name;
        List<Button> bs = new ArrayList<>();
        int extra = (remove != null ? 20 : 0) + (t != null ? 26 : 0);
        bs.add(f.button(name + " ▾", width - extra, () -> pickFunction(host, set)));
        if (t != null) bs.add(f.button("Go", 24, () -> host.openFunction(t.id)));
        if (remove != null) bs.add(f.button("✕", 18, remove));
        f.row(bs.toArray(new Button[0]));
    }

    static void pickFunction(Host host, Consumer<String> set) {
        List<PickerScreen.Option> opts = new ArrayList<>();
        opts.add(new PickerScreen.Option("+ New function…", "create an empty function and use it", "\u0000new", Theme.ACCENT, "Actions"));
        opts.add(new PickerScreen.Option("(none)", "clear the reference", "", Theme.MUTED, "Actions"));
        for (Timeline t : host.project().functions)
            opts.add(new PickerScreen.Option(t.name, t.inputs.size() + " input(s) · " + t.pieces.size() + " piece(s)", t.id, t.color, "Functions in this project"));
        pick(host, "Choose function", opts, v -> {
            if (v.equals("\u0000new")) host.createFunction(set);
            else set.accept(v);
        });
    }

    static void pick(Host host, String title, List<PickerScreen.Option> opts, Consumer<String> onPick) {
        Minecraft.getInstance().setScreen(new PickerScreen(host.screen(), title, opts, onPick));
    }

    // ── Targets ────────────────────────────────────────────────────────────

    private static final String[][] TARGET_MODES = {
            {"tracked", "Tracked player", "the player this sequence tracks (default)"},
            {"initiator", "Whoever started it", "the player who started the sequence"},
            {"context", "Current player", "the player of this branch (event, loop item)"},
            {"player", "Specific player…", "pick online players by name"},
            {"all", "Everyone online", ""},
            {"participants", "Participants", "players involved in this sequence"},
            {"team", "A team", "online players in a scoreboard team"},
            {"filter", "Players matching…", "online players passing a condition"},
            {"selector", "Selector", "vanilla selector like @a[distance=..10]"},
            {"variable", "From a variable", "players stored in a variable"},
            {"none", "Nobody", ""},
    };

    private static String targetLabel(String mode) {
        for (String[] m : TARGET_MODES) if (m[0].equals(mode)) return m[1];
        return Form.pretty(mode);
    }

    private static void targetEditor(Form f, ParamSpec spec, JsonObject params, Host host, String key) {
        JsonElement cur = get(params, spec);
        JsonObject t = cur != null && cur.isJsonObject() ? cur.getAsJsonObject().deepCopy() : PieceRegistry.targetMode("tracked");
        String mode = J.str(t, "mode", "tracked");
        f.label(spec.label);
        Button[] holder = new Button[1];
        holder[0] = f.button(targetLabel(mode) + " ▾", f.width(), () -> {
            PopupMenu m = new PopupMenu().title("Who is affected");
            for (String[] opt : TARGET_MODES) m.add((opt[0].equals(mode) ? "✔ " : "   ") + opt[1], "", true, () -> {
                host.beforeEdit(key + "!");
                t.addProperty("mode", opt[0]);
                params.add(spec.key, t);
                host.changed();
                host.rebuild();
            });
            host.screen().openMenu(m, holder[0].getX(), holder[0].getY() + 18);
        });
        f.row(holder[0]);
        if (mode.equals("player")) {
            EditBox names = f.text("player names (comma separated)", J.str(t, "name", ""), f.width() - 22, s -> {
                host.beforeEdit(key); t.addProperty("name", s); params.add(spec.key, t); host.changed();
            });
            f.row(names, f.button("▾", 19, () -> pickOnlinePlayer(host, name -> {
                host.beforeEdit(key + "!");
                String curNames = J.str(t, "name", "").trim();
                List<String> list = new ArrayList<>();
                for (String n : curNames.split("[,\\s]+")) if (!n.isEmpty() && !n.equalsIgnoreCase(name)) list.add(n);
                list.add(name);
                t.addProperty("name", String.join(", ", list));
                params.add(spec.key, t);
                host.changed();
                host.rebuild();
            })));
            return;
        }
        String field = switch (mode) {
            case "team" -> "team";
            case "selector" -> "selector";
            case "variable" -> "var";
            default -> null;
        };
        if (field != null) {
            String hint = switch (field) { case "selector" -> "@a[distance=..10]"; case "var" -> "variable holding players"; default -> "team name"; };
            f.row(f.text(hint, J.str(t, field, ""), f.width(), s -> { host.beforeEdit(key); t.addProperty(field, s); params.add(spec.key, t); host.changed(); }));
        }
        if (mode.equals("filter")) {
            JsonObject g = J.obj(t, "filter");
            f.row(f.button("Player filter (" + (g == null ? 0 : J.arr(g, "nodes").size()) + " nodes)", f.width(), () -> host.editCondition("Target filter",
                    g == null ? new JsonObject() : g, out -> { host.beforeEdit(key + "!"); t.add("filter", out); params.add(spec.key, t); host.changed(); host.rebuild(); })));
        }
    }

    // ── Online players ─────────────────────────────────────────────────────

    /** Names of the players currently online (as known by this client). */
    public static List<String> onlinePlayers() {
        List<String> out = new ArrayList<>();
        var conn = Minecraft.getInstance().getConnection();
        if (conn != null) for (var info : conn.getOnlinePlayers()) out.add(info.getProfile().getName());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Picker of online players. */
    public static void pickOnlinePlayer(Host host, Consumer<String> onPick) {
        List<PickerScreen.Option> opts = new ArrayList<>();
        String me = Minecraft.getInstance().player == null ? "" : Minecraft.getInstance().player.getGameProfile().getName();
        for (String n : onlinePlayers()) opts.add(new PickerScreen.Option(n, n.equals(me) ? "you" : "online", n, n.equals(me) ? Theme.OK : Theme.INFO, "Online players"));
        if (opts.isEmpty()) opts.add(new PickerScreen.Option("No players online", "", "", Theme.MUTED, "Online players"));
        pick(host, "Choose a player", opts, v -> { if (!v.isEmpty()) onPick.accept(v); });
    }

    // ── Value expressions ──────────────────────────────────────────────────

    /** Editor for a value: either a literal (text with {placeholders}) or a query with arguments. */
    public static void queryEditor(Form f, JsonElement cur, Host host, String key, Consumer<JsonElement> set) {
        boolean isQuery = cur != null && cur.isJsonObject() && cur.getAsJsonObject().has("q") && !"literal".equals(J.str(cur.getAsJsonObject(), "q", ""));
        int W = f.width();
        if (!isQuery) {
            f.row(f.text("value or {placeholder}", str(cur == null || cur.isJsonNull() ? JsonNull.INSTANCE : cur.isJsonObject() ? cur.getAsJsonObject().get("value") : cur), W - 52,
                    s -> { host.beforeEdit(key); set.accept(smart(s, ParamType.NUMBER)); host.changed(); }),
                    f.button("ƒ query", 50, () -> pickQuery(host, id -> { host.beforeEdit(key + "!"); set.accept(newQuery(id)); host.changed(); host.rebuild(); })));
            return;
        }
        JsonObject q = cur.getAsJsonObject();
        QueryType type = PieceRegistry.query(J.str(q, "q", ""));
        JsonObject args = J.obj(q, "args");
        if (args == null) { args = new JsonObject(); q.add("args", args); }
        JsonObject fargs = args;
        f.row(f.button((type == null ? J.str(q, "q", "?") : type.category() + ": " + type.name()) + " ▾", W - 52,
                () -> pickQuery(host, id -> { host.beforeEdit(key + "!"); set.accept(newQuery(id)); host.changed(); host.rebuild(); })),
                f.button("value", 50, () -> { host.beforeEdit(key + "!"); set.accept(new JsonPrimitive("")); host.changed(); host.rebuild(); }));
        if (type == null) return;
        for (ParamSpec a : type.params()) {
            JsonElement av = fargs.has(a.key) ? fargs.get(a.key) : a.def;
            if (a.type == ParamType.ENUM || a.type == ParamType.VAR_SCOPE) {
                f.row(f.cycle("  " + a.label + ": ", a.options, str(av), W, s -> { host.beforeEdit(key + "!"); fargs.addProperty(a.key, s); set.accept(q); host.changed(); }));
            } else if (a.type == ParamType.CONDITION) {
                JsonObject g = av != null && av.isJsonObject() ? av.getAsJsonObject() : new JsonObject();
                f.row(f.button("  " + a.label + " (logic)", W, () -> host.editCondition(a.label, g, out -> { host.beforeEdit(key + "!"); fargs.add(a.key, out); set.accept(q); host.changed(); host.rebuild(); })));
            } else {
                String shown = av != null && av.isJsonObject() && av.getAsJsonObject().has("q") ? "" : str(av);
                String hint = "  " + a.label + (av != null && av.isJsonObject() && av.getAsJsonObject().has("q") ? " (nested query: " + J.str(av.getAsJsonObject(), "q", "") + ")" : "");
                f.label(hint);
                f.row(f.text(a.label, shown, W, s -> { host.beforeEdit(key + a.key); fargs.add(a.key, smart(s, a.type)); set.accept(q); host.changed(); }));
            }
        }
    }

    static JsonObject newQuery(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("q", id);
        QueryType t = PieceRegistry.query(id);
        o.add("args", t == null ? new JsonObject() : PieceRegistry.defaults(t.params()));
        return o;
    }

    static void pickQuery(Host host, Consumer<String> onPick) {
        List<PickerScreen.Option> opts = new ArrayList<>();
        for (QueryType q : PieceRegistry.queries()) {
            if (q.id().equals("literal")) continue;
            opts.add(new PickerScreen.Option(q.name(), q.description() + "  → " + q.result().id(), q.id(), queryColor(q.category()), q.category()));
        }
        opts.sort(java.util.Comparator.comparingInt(o -> CATEGORY_ORDER.indexOf(o.group())));
        pick(host, "Choose a value", opts, onPick);
    }

    private static final List<String> CATEGORY_ORDER = List.of("Player", "Players", "World", "Entities", "Sequence", "Core");

    static int queryColor(String cat) {
        return switch (cat) {
            case "Player" -> 0xFFE94560;
            case "Players" -> 0xFFF06292;
            case "World" -> 0xFFA1887F;
            case "Entities" -> 0xFFEF5350;
            case "Sequence" -> 0xFF7E57C2;
            default -> 0xFF26C6DA;
        };
    }

    /** Builds a condition graph JSON for a quick preset (used by the logic graph palette). */
    public static ConditionGraph.Node cmpNode(String query, String op, JsonElement right) {
        ConditionGraph.Node n = new ConditionGraph.Node("cmp");
        n.params.add("left", newQuery(query));
        n.params.addProperty("op", op);
        if (right != null) n.params.add("right", right);
        return n;
    }
}

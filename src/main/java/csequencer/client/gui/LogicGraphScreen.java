package csequencer.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import csequencer.core.editor.EditorHistory;
import csequencer.core.json.J;
import csequencer.core.logic.CNode;
import csequencer.core.logic.ConditionCompiler;
import csequencer.core.logic.QueryExpr;
import csequencer.core.model.ConditionGraph;
import csequencer.core.model.Project;
import csequencer.core.pieces.CondNodeType;
import csequencer.core.pieces.PieceRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;

/**
 * Visual logic graph editor. Nodes are operators, comparisons, groups, references and transition
 * detectors; drag from a node's output port (right) onto another node to connect it as an input.
 * The graph is compiled live, so cycles, arity errors and missing references show immediately.
 * "Done" returns the graph to the piece; the timeline keeps its scroll/zoom position.
 */
public final class LogicGraphScreen extends CsqScreen implements ParamForm.Host {
    private static final int NW = 118, NH = 30, PALETTE_W = 112;
    private final Project project;
    private final Consumer<JsonObject> apply;
    private final String what;
    private ConditionGraph g;
    private final EditorHistory history = new EditorHistory(100);
    private String selNode;
    private ConditionGraph.Edge selEdge;
    private int offX = 40, offY = 30;
    private String dragNode;
    private int dragDX, dragDY;
    private String connectFrom;
    private boolean panning;
    private double lastMX, lastMY;
    private Form inspector;
    private ListPane<String[]> palette;
    private ConditionCompiler.Result compiled;
    private String lastEditKey;

    public LogicGraphScreen(Screen parent, Project project, String what, JsonObject graph, Consumer<JsonObject> apply) {
        super("Logic: " + what, parent);
        this.project = project;
        this.what = what;
        this.apply = apply;
        this.g = graph == null || graph.size() == 0 ? newGraph() : ConditionGraph.fromJson(graph.deepCopy());
        if (g.nodes.isEmpty()) g = newGraph();
        recompile();
    }

    private static ConditionGraph newGraph() {
        ConditionGraph x = new ConditionGraph();
        x.id = csequencer.core.model.Ids.next("c");
        return x;
    }

    @Override protected Tab tab() { return Tab.EDITOR; }

    // ── Host ───────────────────────────────────────────────────────────────

    @Override public CsqScreen screen() { return this; }
    @Override public Project project() { return project; }

    @Override public void beforeEdit(String key) {
        if (key.endsWith("!") || !key.equals(lastEditKey)) history.push(g.toJson().toString());
        lastEditKey = key.endsWith("!") ? null : key;
    }

    @Override public void changed() { recompile(); }

    @Override public void rebuild() { buildInspector(); }

    @Override public void editCondition(String title, JsonObject graph, Consumer<JsonObject> out) {
        minecraft.setScreen(new LogicGraphScreen(this, project, title, graph, out));
    }

    // ── Build ──────────────────────────────────────────────────────────────

    private static final String[][] PRESETS = {
            {"#Operators", ""},
            {"AND  (all true)", "logic.and"}, {"OR  (any true)", "logic.or"}, {"NOT  (invert)", "logic.not"}, {"XOR", "logic.xor"},
            {"NAND", "logic.nand"}, {"NOR", "logic.nor"}, {"XNOR", "logic.xnor"},
            {"#Structure", ""},
            {"( Group )", "logic.group"}, {"Reusable condition", "logic.ref"}, {"For players / list", "quant"}, {"Just entered / exited", "edge"},
            {"#Values", ""},
            {"Compare two values", "cmp"}, {"Random chance", "chance"}, {"Always true", "logic.true"}, {"Always false", "logic.false"},
            {"#Player", ""},
            {"Health below…", "p:player.health:lt:6"}, {"Health % below…", "p:player.health_pct:lt:50"},
            {"Inside region", "p:player.in_region:is_true:"}, {"Within radius", "p:player.in_radius:is_true:"},
            {"Is sneaking", "s:sneaking"}, {"Is sprinting", "s:sprinting"}, {"Is swimming", "s:swimming"}, {"Is burning", "s:burning"},
            {"On ground", "s:on_ground"}, {"Is dead", "s:dead"}, {"Has item", "p:player.has_item:is_true:"},
            {"Item count ≥", "p:player.item_count:gte:1"}, {"In dimension", "p:player.dimension:eq:minecraft:the_nether"},
            {"Player name is", "p:player.name:eq:Steve"}, {"In team", "p:player.team:eq:red"}, {"Score ≥", "p:player.score:gte:1"},
            {"#World", ""},
            {"Block is", "p:block.type:eq:minecraft:lever"}, {"Block powered", "b:powered"}, {"Entity count ≥", "p:entities.count:gte:1"},
            {"Day time between", "r:world.time"},
            {"#Sequence", ""},
            {"Variable equals", "p:var:eq:1"}, {"State machine is", "p:state.current:eq:idle"}, {"Online players ≥", "p:players.count:gte:2"},
            {"#Common patterns", ""},
            {"Any player in region", "quant-region"}, {"Player just entered region", "edge-region"},
    };

    private static int presetColor(String code) {
        if (code.startsWith("logic.") && !code.equals("logic.group") && !code.equals("logic.ref") && !code.equals("logic.true") && !code.equals("logic.false")) return 0xFF42A5F5;
        if (code.equals("logic.group") || code.equals("logic.ref") || code.equals("quant")) return 0xFF7E57C2;
        if (code.equals("edge") || code.contains("-")) return 0xFFFFA726;
        if (code.startsWith("s:") || code.startsWith("p:player")) return 0xFFE94560;
        if (code.startsWith("p:block") || code.startsWith("b:") || code.startsWith("r:") || code.startsWith("p:entities")) return 0xFFA1887F;
        return 0xFF26A69A;
    }

    @Override
    protected void build() {
        int y = HEADER + 4;
        btn("✔ Done", UiButton.Style.PRIMARY, "Apply this logic and go back", width - 120, y, 58, 18, this::done);
        btn("Cancel", UiButton.Style.GHOST, "Discard changes", width - 58, y, 52, 18, () -> minecraft.setScreen(parent));
        btn("↶", UiButton.Style.GHOST, "Undo (Ctrl+Z)", PALETTE_W + 10, y, 18, 18, this::undo);
        btn("↷", UiButton.Style.GHOST, "Redo (Ctrl+Y)", PALETTE_W + 30, y, 18, 18, this::redo);
        btn("Auto-layout", UiButton.Style.DEFAULT, "Arrange nodes as a tree from the result", PALETTE_W + 54, y, 70, 18, () -> { beforeEdit("!"); autoLayout(); });
        btn("Delete", UiButton.Style.DANGER, "Delete the selected node or connection (Del)", PALETTE_W + 128, y, 46, 18, this::deleteSelection);
        palette = new ListPane<>(13, () -> Arrays.asList(PRESETS), (gr, it, x, yy, w, h, sel, hov) -> {
            if (it[0].startsWith("#")) {
                Theme.section(gr, font, it[0].substring(1), x + 3, yy + 3, w - 6);
                return;
            }
            Theme.dot(gr, x + 4, yy + 4, presetColor(it[1]));
            gr.drawString(font, Theme.clip(font, it[0], w - 14), x + 12, yy + 3, hov ? Theme.TEXT : Theme.TEXT_DIM, false);
        });
        palette.bounds(4, HEADER + 26, PALETTE_W, height - HEADER - 30);
        inspector = new Form(this);
        inspector.bounds(width - 176, HEADER + 26, 172, height - HEADER - 60);
        buildInspector();
    }

    private void buildInspector() {
        if (inspector == null) return;
        inspector.clear();
        int W = inspector.width();
        ConditionGraph.Node n = selNode == null ? null : g.node(selNode);
        if (n == null) {
            inspector.title = "Logic: " + what;
            inspector.section("How it works");
            inspector.hint("1. Click an item on the left to add a node.");
            inspector.hint("2. Drag from the dot on the right side of a node onto another node to make it an input.");
            inspector.hint("3. The node marked RESULT decides the answer.");
            inspector.hint("Drag empty space or right-drag to move around. Del removes the selection.");
            inspector.section("Result");
            inspector.label(g.root.isEmpty() ? "None - the condition is always true" : label(g.node(g.root)), Theme.INFO);
            return;
        }
        CondNodeType t = PieceRegistry.node(n.type);
        inspector.title = t == null ? n.type : t.name();
        if (t != null) inspector.hint(t.description());
        if (g.root.equals(n.id)) inspector.label("\u2714 This node is the result", Theme.OK);
        else inspector.row(inspector.primary("Use as result", W, () -> { beforeEdit("!"); g.root = n.id; recompile(); buildInspector(); }));
        if (n.type.equals("logic.ref")) {
            inspector.section("Reusable condition");
            List<PickerScreen.Option> opts = new ArrayList<>();
            for (ConditionGraph c : project.conditions) opts.add(new PickerScreen.Option(c.name, c.nodes.size() + " nodes", c.name, 0xFF7E57C2, "In this project"));
            inspector.row(inspector.button(J.str(n.params, "condition", "(choose)") + " \u25be", W, () ->
                    ParamForm.pick(this, "Reusable condition", opts, v -> { beforeEdit("!"); n.params.addProperty("condition", v); recompile(); buildInspector(); })));
        } else if (t != null && !t.params().isEmpty()) {
            inspector.section("Settings");
            ParamForm.build(inspector, t.params(), n.params, this, n.id + ".");
        }
        inspector.section("Node");
        inspector.row(inspector.button("Duplicate", W / 2 - 2, this::duplicateNode), inspector.danger("Delete", W / 2 - 1, this::deleteSelection));
    }

    private static String flat(net.minecraft.util.FormattedCharSequence s) {
        StringBuilder sb = new StringBuilder();
        s.accept((i, style, cp) -> { sb.appendCodePoint(cp); return true; });
        return sb.toString();
    }

    // ── Graph operations ───────────────────────────────────────────────────

    private void recompile() { compiled = ConditionCompiler.compile(g, project::condition, 48, 2048); }

    private ConditionGraph.Node addNode(String type) {
        ConditionGraph.Node n = new ConditionGraph.Node(type);
        CondNodeType t = PieceRegistry.node(type);
        if (t != null) n.params = PieceRegistry.defaults(t.params());
        placeNew(n);
        return n;
    }

    private void placeNew(ConditionGraph.Node n) {
        int cx = (PALETTE_W + width - 176) / 2 - offX - NW / 2, cy = height / 2 - offY - NH / 2;
        int k = 0;
        while (overlaps(cx, cy) && k++ < 20) { cx += 14; cy += 14; }
        n.x = cx;
        n.y = cy;
        g.nodes.add(n);
        if (g.root.isEmpty()) g.root = n.id;
        selNode = n.id;
    }

    private boolean overlaps(int x, int y) {
        for (ConditionGraph.Node o : g.nodes) if (Math.abs(o.x - x) < 20 && Math.abs(o.y - y) < 12) return true;
        return false;
    }

    private void addPreset(String code) {
        if (code.isEmpty() || code.startsWith("#")) return;
        beforeEdit("!");
        if (!code.contains(":") && !code.contains("-")) { addNode(code); finishAdd(); return; }
        ConditionGraph.Node n;
        if (code.startsWith("p:")) {
            String[] p = code.split(":", 4);
            n = ParamForm.cmpNode(p[1], p[2], p.length > 3 && !p[3].isEmpty() ? smartVal(p[3]) : null);
            placeNew(n);
        } else if (code.startsWith("s:")) {
            n = ParamForm.cmpNode("player.state", "is_true", null);
            n.params.getAsJsonObject("left").getAsJsonObject("args").addProperty("state", code.substring(2));
            placeNew(n);
        } else if (code.startsWith("b:")) {
            n = ParamForm.cmpNode("block.state", "eq", new JsonPrimitive("true"));
            n.params.getAsJsonObject("left").getAsJsonObject("args").addProperty("property", code.substring(2));
            placeNew(n);
        } else if (code.startsWith("r:")) {
            n = ParamForm.cmpNode(code.substring(2), "in_range", new JsonPrimitive(0));
            n.params.addProperty("right2", 12000);
            placeNew(n);
        } else if (code.equals("quant-region")) {
            ConditionGraph.Node q = addNode("quant");
            ConditionGraph.Node c = ParamForm.cmpNode("player.in_region", "is_true", null);
            c.x = q.x - NW - 30;
            c.y = q.y;
            g.nodes.add(c);
            g.edges.add(new ConditionGraph.Edge(c.id, q.id, 0));
            selNode = q.id;
        } else if (code.equals("edge-region")) {
            ConditionGraph.Node e = addNode("edge");
            ConditionGraph.Node c = ParamForm.cmpNode("player.in_region", "is_true", null);
            c.x = e.x - NW - 30;
            c.y = e.y;
            g.nodes.add(c);
            g.edges.add(new ConditionGraph.Edge(c.id, e.id, 0));
            selNode = e.id;
        }
        finishAdd();
    }

    private void finishAdd() {
        recompile();
        buildInspector();
    }

    private static com.google.gson.JsonElement smartVal(String s) {
        try { return new JsonPrimitive(Double.parseDouble(s)); } catch (NumberFormatException e) { return new JsonPrimitive(s); }
    }

    private void connect(String from, String to) {
        if (from.equals(to)) return;
        CondNodeType t = PieceRegistry.node(g.node(to).type);
        if (t == null || t.maxChildren() == 0) { csequencer.client.Toasts.warn("That node has no inputs"); return; }
        int count = 0;
        for (ConditionGraph.Edge e : g.edges) if (e.to.equals(to) && !e.from.equals(from)) count++;
        if (count >= t.maxChildren()) { csequencer.client.Toasts.warn(t.name() + " accepts only " + t.maxChildren() + " input(s)"); return; }
        // cycle check: 'to' must not already be an input (transitively) of 'from'
        if (reaches(to, from)) { csequencer.client.Toasts.warn("That connection would create a loop"); return; }
        beforeEdit("!");
        g.edges.removeIf(e -> e.from.equals(from));
        int slot = 0;
        for (ConditionGraph.Edge e : g.edges) if (e.to.equals(to)) slot = Math.max(slot, e.slot + 1);
        g.edges.add(new ConditionGraph.Edge(from, to, slot));
        if (g.root.equals(from)) g.root = to;
        recompile();
        buildInspector();
    }

    /** True if {@code target} is reachable from {@code start} following child→parent edges. */
    private boolean reaches(String start, String target) {
        Deque<String> q = new ArrayDeque<>(List.of(start));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String c = q.poll();
            if (c.equals(target)) return true;
            if (!seen.add(c)) continue;
            for (ConditionGraph.Edge e : g.edges) if (e.from.equals(c)) q.add(e.to);
        }
        return false;
    }

    private void deleteSelection() {
        if (selEdge != null) {
            beforeEdit("!");
            g.edges.remove(selEdge);
            selEdge = null;
        } else if (selNode != null) {
            beforeEdit("!");
            String id = selNode;
            g.nodes.removeIf(n -> n.id.equals(id));
            g.edges.removeIf(e -> e.from.equals(id) || e.to.equals(id));
            if (g.root.equals(id)) g.root = g.nodes.isEmpty() ? "" : g.nodes.get(0).id;
            selNode = null;
        }
        recompile();
        buildInspector();
    }

    private void duplicateNode() {
        ConditionGraph.Node n = selNode == null ? null : g.node(selNode);
        if (n == null) return;
        beforeEdit("!");
        ConditionGraph.Node c = ConditionGraph.Node.fromJson(n.toJson());
        c.id = csequencer.core.model.Ids.next("n");
        c.x += 20;
        c.y += 20;
        g.nodes.add(c);
        selNode = c.id;
        recompile();
        buildInspector();
    }

    private void autoLayout() {
        ConditionGraph.Node root = g.node(g.root);
        if (root == null) return;
        int[] row = {0};
        layout(root, 0, row, new HashSet<>());
        int r = row[0];
        for (ConditionGraph.Node n : g.nodes) if (!placed.contains(n.id)) { n.x = -400; n.y = (r++) * (NH + 12); }
        placed.clear();
    }

    private final Set<String> placed = new HashSet<>();

    private void layout(ConditionGraph.Node n, int depth, int[] row, Set<String> path) {
        if (!path.add(n.id)) return;
        List<ConditionGraph.Node> kids = g.children(n.id);
        int startRow = row[0];
        for (ConditionGraph.Node k : kids) layout(k, depth + 1, row, path);
        if (kids.isEmpty()) row[0]++;
        int endRow = Math.max(startRow + 1, row[0]);
        n.x = 300 - depth * (NW + 34);
        n.y = (int) ((startRow + endRow - 1) / 2.0 * (NH + 12));
        placed.add(n.id);
    }

    private void undo() {
        String s = history.undo(g.toJson().toString());
        if (s != null) { g = ConditionGraph.fromJson(J.parseObject(s)); selNode = null; selEdge = null; recompile(); buildInspector(); }
    }

    private void redo() {
        String s = history.redo(g.toJson().toString());
        if (s != null) { g = ConditionGraph.fromJson(J.parseObject(s)); selNode = null; selEdge = null; recompile(); buildInspector(); }
    }

    private void done() {
        minecraft.setScreen(parent);
        apply.accept(g.nodes.isEmpty() ? new JsonObject() : g.toJson());
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    private int sx(ConditionGraph.Node n) { return n.x + offX + PALETTE_W + 8; }

    private int sy(ConditionGraph.Node n) { return n.y + offY + HEADER + 26; }

    private boolean inCanvas(double mx, double my) { return mx > PALETTE_W + 6 && mx < width - 178 && my > HEADER + 24 && my < height - 34; }

    @Override
    protected void drawContent(GuiGraphics gr, int mx, int my, float pt) {
        palette.render(gr, font, mx, my);
        int cx0 = PALETTE_W + 6, cy0 = HEADER + 24, cx1 = width - 178, cy1 = height - 34;
        gr.fill(cx0, cy0, cx1, cy1, 0xFF0A0D14);
        gr.enableScissor(cx0, cy0, cx1, cy1);
        for (int x = cx0 + Math.floorMod(offX, 20); x < cx1; x += 20) gr.fill(x, cy0, x + 1, cy1, 0xFF121722);
        for (int y = cy0 + Math.floorMod(offY, 20); y < cy1; y += 20) gr.fill(cx0, y, cx1, y + 1, 0xFF121722);
        // edges
        for (ConditionGraph.Edge e : g.edges) {
            ConditionGraph.Node a = g.node(e.from), b = g.node(e.to);
            if (a == null || b == null) continue;
            int x1 = sx(a) + NW, y1 = sy(a) + NH / 2, x2 = sx(b), y2 = sy(b) + 8 + e.slot * 5;
            wire(gr, x1, y1, x2, Math.min(sy(b) + NH - 3, y2), e == selEdge ? 0xFFFFD54F : 0xFF6B7FA8);
        }
        if (connectFrom != null && g.node(connectFrom) != null) {
            ConditionGraph.Node a = g.node(connectFrom);
            wire(gr, sx(a) + NW, sy(a) + NH / 2, mx, my, 0xFFFFFFFF);
        }
        // result sink
        ConditionGraph.Node root = g.node(g.root);
        if (root != null) {
            int rx = sx(root) + NW + 26, ry = sy(root) + NH / 2;
            wire(gr, sx(root) + NW, ry, rx, ry, Theme.ACCENT);
            gr.drawString(font, "RESULT", rx + 3, ry - 4, Theme.ACCENT, false);
        }
        Map<String, Boolean> errors = new HashMap<>();
        for (ConditionGraph.Node n : g.nodes) drawNode(gr, n, mx, my, errors);
        gr.disableScissor();
        // status
        int y = height - 30;
        if (compiled != null) {
            if (!compiled.errors().isEmpty()) gr.drawString(font, Theme.clip(font, "✘ " + compiled.errors().get(0) + (compiled.errors().size() > 1 ? " (+" + (compiled.errors().size() - 1) + ")" : ""), width - PALETTE_W - 190), cx0, y, Theme.ERR, false);
            else if (!compiled.warnings().isEmpty()) gr.drawString(font, Theme.clip(font, "⚠ " + compiled.warnings().get(0), width - PALETTE_W - 190), cx0, y, Theme.WARN, false);
            else gr.drawString(font, "✔ Valid", cx0, y, Theme.OK, false);
            gr.drawString(font, Theme.clip(font, g.nodes.isEmpty() ? "Empty: always true" : compiled.root().describe(), width - PALETTE_W - 190), cx0, y + 11, Theme.SUB, false);
        }
        inspector.render(gr);
    }

    private void drawNode(GuiGraphics gr, ConditionGraph.Node n, int mx, int my, Map<String, Boolean> errs) {
        int x = sx(n), y = sy(n);
        CondNodeType t = PieceRegistry.node(n.type);
        int color = switch (t == null ? "" : t.category()) {
            case "Operators" -> 0xFF42A5F5;
            case "Structure" -> 0xFF7E57C2;
            case "Transitions" -> 0xFFFFA726;
            default -> 0xFF26A69A;
        };
        boolean sel = n.id.equals(selNode);
        boolean isRoot = n.id.equals(g.root);
        gr.fill(x - 1, y - 1, x + NW + 1, y + NH + 1, sel ? 0xFFFFFFFF : isRoot ? Theme.ACCENT : 0xFF2A3245);
        gr.fill(x, y, x + NW, y + NH, 0xFF161B28);
        gr.fill(x, y, x + NW, y + 10, (color & 0x00FFFFFF) | 0xAA000000);
        gr.drawString(font, Theme.clip(font, t == null ? n.type : t.name(), NW - 6), x + 3, y + 1, Theme.TEXT, false);
        gr.drawString(font, Theme.clip(font, summary(n), NW - 6), x + 3, y + 13, 0xFFC0C8D8, false);
        int kids = 0;
        for (ConditionGraph.Edge e : g.edges) if (e.to.equals(n.id)) kids++;
        if (t != null && t.maxChildren() > 0) {
            gr.fill(x - 3, y + NH / 2 - 3, x + 3, y + NH / 2 + 3, kids < t.minChildren() ? Theme.ERR : 0xFF8FA3C8);
            gr.drawString(font, kids + (t.maxChildren() == CondNodeType.UNBOUNDED ? "" : "/" + t.maxChildren()), x + NW - 18, y + 21, Theme.SUB, false);
        }
        boolean hovPort = Math.abs(mx - (x + NW)) <= 4 && Math.abs(my - (y + NH / 2)) <= 4;
        gr.fill(x + NW - 3, y + NH / 2 - 3, x + NW + 3, y + NH / 2 + 3, hovPort ? 0xFFFFFFFF : color);
        if (t != null && t.stateful()) gr.drawString(font, "⇄", x + NW - 10, y + 1, 0xFFFFE082, false);
    }

    private String summary(ConditionGraph.Node n) {
        JsonObject p = n.params;
        return switch (n.type) {
            case "cmp" -> {
                var op = csequencer.core.value.CompareOp.byId(J.str(p, "op", "eq"));
                String l = QueryExpr.parse(p.get("left")).describe();
                yield l + " " + (op == null ? "?" : op.symbol) + (op != null && op.operands > 0 ? " " + QueryExpr.parse(p.get("right")).describe() : "");
            }
            case "logic.group" -> J.str(p, "name", "Group") + " · " + J.str(p, "op", "and").toUpperCase(Locale.ROOT);
            case "logic.ref" -> "[" + J.str(p, "condition", "?") + "]";
            case "edge" -> "when input " + J.str(p, "mode", "entered");
            case "quant" -> J.str(p, "mode", "any") + " of " + QueryExpr.parse(p.get("source")).describe();
            case "chance" -> J.d(p, "probability", 0.5) * 100 + "%";
            default -> "";
        };
    }

    private static void wire(GuiGraphics gr, int x1, int y1, int x2, int y2, int color) {
        int mid = (x1 + x2) / 2;
        hline(gr, x1, mid, y1, color);
        vline(gr, mid, y1, y2, color);
        hline(gr, mid, x2, y2, color);
    }

    private static void hline(GuiGraphics g, int a, int b, int y, int c) { g.fill(Math.min(a, b), y, Math.max(a, b) + 1, y + 1, c); }

    private static void vline(GuiGraphics g, int x, int a, int b, int c) { g.fill(x, Math.min(a, b), x + 1, Math.max(a, b) + 1, c); }

    private String label(ConditionGraph.Node n) {
        if (n == null) return "?";
        CondNodeType t = PieceRegistry.node(n.type);
        return (t == null ? n.type : t.name()) + " " + summary(n);
    }

    // ── Input ──────────────────────────────────────────────────────────────

    private ConditionGraph.Node nodeAt(double mx, double my) {
        for (int i = g.nodes.size() - 1; i >= 0; i--) {
            ConditionGraph.Node n = g.nodes.get(i);
            if (mx >= sx(n) - 3 && mx <= sx(n) + NW + 4 && my >= sy(n) && my <= sy(n) + NH) return n;
        }
        return null;
    }

    private ConditionGraph.Edge edgeAt(double mx, double my) {
        for (ConditionGraph.Edge e : g.edges) {
            ConditionGraph.Node a = g.node(e.from), b = g.node(e.to);
            if (a == null || b == null) continue;
            int mid = (sx(a) + NW + sx(b)) / 2;
            int y1 = sy(a) + NH / 2, y2 = sy(b) + 8 + e.slot * 5;
            if (Math.abs(mx - mid) <= 3 && my >= Math.min(y1, y2) - 2 && my <= Math.max(y1, y2) + 2) return e;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int pi = palette.click(mx, my);
        if (pi >= 0) { if (!PRESETS[pi][0].startsWith("#")) addPreset(PRESETS[pi][1]); return true; }
        if (!inCanvas(mx, my)) return false;
        lastMX = mx;
        lastMY = my;
        if (button == 1 || button == 2) { panning = true; return true; }
        ConditionGraph.Node n = nodeAt(mx, my);
        if (n != null) {
            if (Math.abs(mx - (sx(n) + NW)) <= 5 && Math.abs(my - (sy(n) + NH / 2)) <= 6) {
                connectFrom = n.id;
                return true;
            }
            beforeEdit("move" + n.id);
            selNode = n.id;
            selEdge = null;
            dragNode = n.id;
            dragDX = (int) mx - sx(n);
            dragDY = (int) my - sy(n);
            buildInspector();
            return true;
        }
        ConditionGraph.Edge e = edgeAt(mx, my);
        selEdge = e;
        selNode = null;
        if (e == null) panning = true;
        buildInspector();
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragNode != null) {
            ConditionGraph.Node n = g.node(dragNode);
            if (n != null) {
                n.x = (int) mx - dragDX - offX - PALETTE_W - 8;
                n.y = (int) my - dragDY - offY - HEADER - 26;
            }
            return true;
        }
        if (panning) {
            offX += (int) (mx - lastMX);
            offY += (int) (my - lastMY);
            lastMX = mx;
            lastMY = my;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (connectFrom != null) {
            ConditionGraph.Node target = nodeAt(mx, my);
            if (target != null) connect(connectFrom, target.id);
            connectFrom = null;
        }
        dragNode = null;
        panning = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        if (inspector.scroll(mx, my, d)) return true;
        if (palette.scroll(mx, my, d)) return true;
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean typing = getFocused() instanceof net.minecraft.client.gui.components.EditBox;
        if (!typing) {
            if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) { deleteSelection(); return true; }
            if (ctrl && key == GLFW.GLFW_KEY_Z) { undo(); return true; }
            if (ctrl && key == GLFW.GLFW_KEY_Y) { redo(); return true; }
            if (ctrl && key == GLFW.GLFW_KEY_D) { duplicateNode(); return true; }
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) { minecraft.setScreen(parent); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }

    @SuppressWarnings("unused")
    private CNode root() { return compiled == null ? null : compiled.root(); }
}

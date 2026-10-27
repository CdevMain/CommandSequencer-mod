package csequencer.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import csequencer.client.ClientConfig;
import csequencer.client.ClientNet;
import csequencer.client.ClientState;
import csequencer.client.ProjectSession;
import csequencer.client.Toasts;
import csequencer.client.audio.AudioDecoder;
import csequencer.client.audio.ClientAudio;
import csequencer.core.editor.EditorHistory;
import csequencer.core.editor.TimelineOps;
import csequencer.core.engine.Durations;
import csequencer.core.json.J;
import csequencer.core.json.ProjectCodec;
import csequencer.core.model.*;
import csequencer.core.pieces.PieceCategory;
import csequencer.core.pieces.PieceKind;
import csequencer.core.pieces.PieceRegistry;
import csequencer.core.pieces.PieceType;
import csequencer.core.platform.Capability;
import csequencer.core.platform.PlatformInfo;
import csequencer.core.security.Limits;
import csequencer.core.validate.Validator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;

/**
 * The in-game Timeline Editor.
 *
 * <p>Layout: toolbar · [Pieces panel] · track headers · timeline · inspector. Pieces are dragged
 * from the categorised Pieces panel onto a track, moved/resized with the mouse, and configured in
 * the inspector. Right-click opens context menus everywhere. Editing is local until saved; running
 * and debugging happen on the server, and the editor shows the live server playheads of its run.
 * The instance survives navigation so the timeline position is never lost.
 */
public final class TimelineEditorScreen extends CsqScreen implements ParamForm.Host {
    private static TimelineEditorScreen CURRENT;
    private static String clipboard;
    private static final int RULER_H = 15, TRACK_H = 26, COLLAPSED_H = 12, WAVE_H = 30, PAL_ROW = 15, PAL_HEAD = 16;
    private static final int[] PALETTE = {0xFF4F7DC8, 0xFFE94560, 0xFF66BB6A, 0xFFFFA726, 0xFFAB47BC, 0xFF26C6DA, 0xFFA1887F, 0xFF90A4AE};

    private Project project;
    private Timeline tl;
    private long baseRevision;
    private boolean dirty, readOnly, saving;
    private String liveTaskId;
    private final EditorHistory history = new EditorHistory(100);
    private String lastEditKey;
    private final LinkedHashSet<String> sel = new LinkedHashSet<>();
    private String selMarker;
    private String selTrack;
    private double scrollTick;
    private int scrollTrack;
    private double ppt = 2.0;
    private int playhead;
    private boolean previewing;
    private long previewStartMs;
    private int previewStartTick;

    // Pieces panel
    /** Expanded list (true) — false means rail or hidden, see {@link ClientConfig#paletteMode}. */
    private boolean showPalette = ClientConfig.paletteMode == 0;
    /** Category whose flyout is open in rail mode, or null. */
    private PieceCategory flyoutCat;
    private int flyoutScroll;
    private static final int RAIL_W = 22, FLY_W = 132;
    private UiTextField paletteSearch;
    private final Set<String> openCategories = new HashSet<>(Set.of("Timing", "Logic", "Messages"));
    private int paletteScroll;
    private PieceType dragNew;

    private enum Drag { NONE, MOVE, RESIZE, RESIZE_LEFT, BOX, PLAYHEAD, MARKER, PAN, NEW }
    private Drag drag = Drag.NONE;
    private double dragX0, dragY0;
    private boolean rightMoved;
    private final Map<String, int[]> dragOrig = new HashMap<>();
    private String dragSnapshot;
    private String dragAnchor;
    private long lastClickMs;
    private double lastClickX, lastClickY;

    private Form inspector;
    private Validator.Report report;
    private boolean reportDirty = true;
    private final Map<String, float[]> waveforms = new HashMap<>();
    private final Set<String> waveformRequested = new HashSet<>();
    private long lastDetailReq;
    private UiButton bSave, bPause, bStep, bStop, bTimeline;

    private TimelineEditorScreen(Project p, String liveTask) {
        super("Timeline Editor", null);
        load(p, liveTask);
    }

    public static TimelineEditorScreen current() { return CURRENT; }

    /** Opens (or replaces) the editor with a project. {@code liveTask} shows that run's playheads. */
    public static TimelineEditorScreen open(Project p, String liveTask) {
        if (CURRENT != null && CURRENT.project.id.equals(p.id) && CURRENT.dirty) {
            CURRENT.liveTaskId = liveTask != null ? liveTask : CURRENT.liveTaskId;
            Toasts.info("Kept your unsaved edits of '" + p.name + "'");
            return CURRENT;
        }
        CURRENT = new TimelineEditorScreen(p, liveTask);
        return CURRENT;
    }

    private void load(Project p, String liveTask) {
        project = p;
        tl = p.main;
        baseRevision = p.revision > 0 ? p.revision : -1;
        readOnly = ProjectSession.lastMeta != null && J.str(ProjectSession.lastMeta, "id", "").equals(p.id) && !ProjectSession.lastCanEdit;
        liveTaskId = liveTask;
        dirty = false;
        history.clear();
        sel.clear();
        reportDirty = true;
        selTrack = tl.tracks.isEmpty() ? null : tl.tracks.get(0).id;
    }

    @Override protected Tab tab() { return Tab.EDITOR; }

    // ── Host (for ParamForm / LogicGraph) ──────────────────────────────────

    @Override public CsqScreen screen() { return this; }
    @Override public Project project() { return project; }

    @Override public void beforeEdit(String key) {
        if (key.endsWith("!") || !key.equals(lastEditKey)) history.push(ProjectCodec.writeCompact(project));
        lastEditKey = key.endsWith("!") ? null : key;
    }

    @Override public void changed() {
        dirty = true;
        reportDirty = true;
    }

    @Override public void rebuild() { buildInspector(); }

    @Override public void editCondition(String title, JsonObject graph, Consumer<JsonObject> apply) {
        minecraft.setScreen(new LogicGraphScreen(this, project, title, graph, apply));
    }

    @Override public void openFunction(String id) {
        Timeline t = project.timeline(id);
        if (t != null) switchTimeline(t);
    }

    @Override public void createFunction(Consumer<String> onCreated) {
        minecraft.setScreen(new PromptScreen(this, "New function", "Function name", "Function " + (project.functions.size() + 1), name -> {
            if (name.isBlank()) return;
            beforeEdit("!");
            Timeline f = Timeline.create(name.trim(), "fn");
            f.color = PALETTE[project.functions.size() % PALETTE.length];
            project.functions.add(f);
            changed();
            onCreated.accept(f.id);
            buildInspector();
        }));
    }

    @Override public void importAudio(Consumer<String> onAudioId) {
        minecraft.setScreen(new AudioImportScreen(this, ref -> {
            beforeEdit("!");
            AudioRef existing = null;
            for (AudioRef a : project.audio) if (a.hash.equals(ref.hash)) existing = a;
            if (existing == null) { project.audio.add(ref); existing = ref; }
            changed();
            onAudioId.accept(existing.id);
            buildInspector();
        }));
    }

    // ── Layout ─────────────────────────────────────────────────────────────

    private int palW() { return ClientConfig.paletteMode == 0 ? 126 : ClientConfig.paletteMode == 1 ? RAIL_W : 0; }

    private void setPaletteMode(int m) {
        ClientConfig.paletteMode = Math.floorMod(m, 3);
        ClientConfig.save();
        showPalette = ClientConfig.paletteMode == 0;
        flyoutCat = null;
        rebuildWidgets();
    }

    /** Cycles expanded → rail → hidden → expanded. */
    private void cyclePalette() { setPaletteMode(ClientConfig.paletteMode + 1); }
    private int hdrW() { return 86; }
    private int insW() { return Math.max(160, Math.min(220, width / 3)); }
    private int x0() { return palW() + hdrW(); }
    private int x1() { return width - insW() - 4; }
    private int toolbarY() { return HEADER + 3; }
    private int infoY() { return HEADER + 24; }
    private int areaY() { return HEADER + 38; }
    private int rulerY() { return areaY(); }
    private int tracksY() { return rulerY() + RULER_H; }
    private boolean hasWave() { return audioPieceForWave() != null; }
    private int tracksBottom() { return height - 14 - (hasWave() ? WAVE_H + 2 : 0); }
    private int trackH(Track t) { return t.collapsed ? COLLAPSED_H : TRACK_H; }

    private int trackTop(int index) {
        int y = tracksY();
        for (int i = scrollTrack; i < index && i < tl.tracks.size(); i++) y += trackH(tl.tracks.get(i));
        return index < scrollTrack ? -1000 : y;
    }

    private int trackAt(double my) {
        int y = tracksY();
        for (int i = scrollTrack; i < tl.tracks.size(); i++) {
            int h = trackH(tl.tracks.get(i));
            if (my >= y && my < y + h) return i;
            y += h;
        }
        return -1;
    }

    private double tx(double tick) { return x0() + (tick - scrollTick) * ppt; }
    private double tickAt(double mx) { return scrollTick + (mx - x0()) / ppt; }

    private int snap(int tick) { return TimelineOps.snap(tick, hasShiftDown() ? 0 : ClientConfig.editorSnap, tl.markers, (int) Math.max(1, 4 / ppt)); }

    private int displayTicks(Piece p) {
        if (p.duration > 0) return p.duration;
        switch (p.type) {
            case "flow.delay": return J.i(p.params, "ticks", 0);
            case "flow.timeout": return J.i(p.params, "window", 0);
            case "camera.path": return J.i(p.params, "duration", 100) + J.i(p.params, "blend", 0);
            case "screen.effect": { int d = J.i(p.params, "duration", 100); return d <= 0 ? 0 : d + J.i(p.params, "fadeIn", 20) + J.i(p.params, "fadeOut", 20); }
            case "camera.fixed", "camera.orbit", "player.controls": return J.i(p.params, "duration", 0);
            case "camera.look_at": return J.i(p.params, "duration", 60);
            case "audio.play": {
                AudioRef a = project.audio(J.str(p.params, "audio", ""));
                return a == null ? 0 : Math.max(1, a.durationTicks() - (int) (J.d(p.params, "offsetMs", 0) / 50));
            }
            default: return 0;
        }
    }

    private int pieceW(Piece p) { return (int) Math.max(16, displayTicks(p) * ppt); }

    // ── Build ──────────────────────────────────────────────────────────────

    @Override
    protected void build() {
        if (width < 520 && ClientConfig.paletteMode == 0 && CURRENT == this && !buildOnce) { ClientConfig.paletteMode = 1; showPalette = false; }
        buildOnce = true;
        int y = toolbarY(), h = 18, x = 6;
        bSave = btn(dirty ? "Save •" : "Save", UiButton.Style.PRIMARY, "Save to the server (Ctrl+S)", x, y, 44, h, () -> save(null));
        x += 48;
        x = sep(x);
        btn("▶ Run", UiButton.Style.DEFAULT, "Save and run on the server from the start\nShift-click: run from the playhead", x, y, 44, h, () -> run(hasShiftDown() ? playhead : 0, false));
        x += 46;
        btn("Debug", UiButton.Style.DEFAULT, "Run with breakpoints (pieces with a red dot pause the run)", x, y, 40, h, () -> run(0, true));
        x += 42;
        bPause = btn("⏸", UiButton.Style.GHOST, "Pause / resume the live run", x, y, 18, h, this::togglePauseLive);
        x += 20;
        bStep = btn("Step", UiButton.Style.GHOST, "Advance the paused run by one tick", x, y, 30, h, () -> controlLive("step"));
        x += 32;
        bStop = btn("■", UiButton.Style.GHOST, "Stop the live run", x, y, 18, h, () -> controlLive("cancel"));
        x += 20;
        x = sep(x);
        btn("↶", UiButton.Style.GHOST, "Undo (Ctrl+Z)", x, y, 18, h, this::undo);
        x += 20;
        btn("↷", UiButton.Style.GHOST, "Redo (Ctrl+Y)", x, y, 18, h, this::redo);
        x += 20;
        x = sep(x);
        String tlName = tl == project.main ? "Main" : tl.name;
        bTimeline = btn("☰ " + tlName + " ▾", UiButton.Style.DEFAULT, "Switch between the main sequence and functions", x, y, Math.min(110, UiButton.fit("☰ " + tlName + " ▾")), h, this::timelineMenu);
        x += bTimeline.getWidth() + 3;
        int insX = x;
        btn("+ Insert ▾", UiButton.Style.DEFAULT, "Add a piece, track, marker, audio or function", x, y, 62, h, () -> insertMenu(insX, y + h + 2));
        x += 65;
        btn("⚙ Project", UiButton.Style.DEFAULT, "Execution scope, policies, overlay and automatic triggers", x, y, 58, h, () -> minecraft.setScreen(new ProjectSettingsScreen(this, this)));
        x += 61;
        btn("▣ Area", UiButton.Style.DEFAULT, "New area trigger: draw a box in the world that starts this sequence when a player enters it", x, y, 46, h, () -> ProjectSettingsScreen.newAreaTrigger(this));

        int rx = x1() - 2;
        rx -= 18;
        btn("♪", UiButton.Style.GHOST, "Preview locally from the playhead (Space)", rx, y, 18, h, this::togglePreview);
        rx -= 20;
        btn("+", UiButton.Style.GHOST, "Zoom in (scroll)", rx, y, 18, h, () -> zoom(1.5, (x0() + x1()) / 2.0));
        rx -= 20;
        btn("−", UiButton.Style.GHOST, "Zoom out (scroll)", rx, y, 18, h, () -> zoom(1 / 1.5, (x0() + x1()) / 2.0));
        rx -= 22;
        btn("◧", UiButton.Style.GHOST, "Pieces panel: full list → icon rail → hidden (P)", rx, y, 20, h, this::cyclePalette).selected(ClientConfig.paletteMode != 2);

        paletteSearch = null;
        if (showPalette) {
            btn("«", UiButton.Style.GHOST, "Minimize the Pieces panel to an icon rail (P)", palW() - 17, areaY() + 2, 14, 15, () -> setPaletteMode(1));
            paletteSearch = new UiTextField(font, 4, areaY() + 2, palW() - 24, 15, "Search pieces…");
            paletteSearch.setValue(paletteSearchValue);
            paletteSearch.setResponder(s -> { paletteSearchValue = s; paletteScroll = 0; });
            addRenderableWidget(paletteSearch);
        }

        inspector = new Form(this);
        inspector.bounds(width - insW(), areaY(), insW() - 4, height - areaY() - 16);
        buildInspector();
        if (readOnly) Toasts.warn("Read-only: you may not save '" + project.name + "' (use Projects → Duplicate)");
    }

    private boolean buildOnce;
    private String paletteSearchValue = "";

    private int sep(int x) { return x + 6; }

    private void timelineMenu() {
        PopupMenu m = new PopupMenu().title("Timelines");
        m.add((tl == project.main ? "✔ " : "   ") + "Main sequence", "" + project.main.pieces.size(), true, () -> switchTimeline(project.main));
        for (Timeline f : project.functions) m.add((tl == f ? "✔ " : "   ") + "ƒ " + f.name, "" + f.pieces.size(), true, () -> switchTimeline(f));
        m.separator();
        m.add("+ New function…", () -> createFunction(this::openFunction));
        if (tl != project.main) {
            m.add("Rename this function…", () -> minecraft.setScreen(new PromptScreen(this, "Rename function", "Name", tl.name, s -> { beforeEdit("!"); tl.name = s; changed(); rebuildWidgets(); })));
            m.danger("Delete this function", "", this::confirmDeleteFunction);
        }
        openMenu(m, bTimeline.getX(), bTimeline.getY() + 20);
    }

    private void insertMenu(int x, int y) {
        PopupMenu m = new PopupMenu().title("Insert");
        m.add("Piece…", "I", true, () -> insertPiece(playhead, selTrack));
        m.add("Track", "", true, this::addTrack);
        m.add("Phase marker", "M", true, this::addMarker);
        m.add("Audio (WAV / MP3)…", "", true, () -> importAudio(this::addAudioPiece));
        m.add("Function…", "", true, () -> createFunction(this::openFunction));
        m.add("Area trigger (draw in world)…", "", true, () -> ProjectSettingsScreen.newAreaTrigger(this));
        m.separator();
        m.add("Group selection", "G", !sel.isEmpty(), this::groupSelection);
        m.add("Reusable condition…", "", true, this::editReusable);
        openMenu(m, x, y);
    }

    void buildInspector() {
        if (inspector == null) return;
        inspector.clear();
        int W = inspector.width();
        Piece p = sel.size() == 1 ? tl.piece(sel.iterator().next()) : null;
        if (p != null) { pieceInspector(p, W); return; }
        if (sel.size() > 1) {
            inspector.title = sel.size() + " pieces selected";
            inspector.section("Selection");
            inspector.row(inspector.button("Group (G)", W / 2 - 2, this::groupSelection), inspector.button("Duplicate", W / 2 - 1, this::duplicateSelection));
            inspector.row(inspector.button("Enable / disable", W, () -> { beforeEdit("!"); boolean on = !allEnabled(); for (Piece x : selected()) x.enabled = on; changed(); }));
            inspector.row(inspector.danger("Delete " + sel.size() + " pieces", W, this::deleteSelection));
            inspector.hint("Drag any selected piece to move them together. Arrow keys nudge.");
            return;
        }
        Marker m = selMarker == null ? null : marker(selMarker);
        if (m != null) { markerInspector(m, W); return; }
        timelineInspector(W);
    }

    private boolean allEnabled() { for (Piece x : selected()) if (!x.enabled) return false; return true; }

    private void pieceInspector(Piece p, int W) {
        PieceType t = PieceRegistry.piece(p.type);
        PieceCategory c = PieceRegistry.categoryOf(t);
        inspector.title = (c == null ? "" : c.icon() + "  ") + (t == null ? p.type : t.name());
        if (t == null) inspector.label("Unknown piece type — it is kept but will not run", Theme.ERR);
        else inspector.hint(t.description());
        if (report != null) for (Validator.Issue i : report.forPiece(p.id))
            inspector.label((i.severity() == Validator.Severity.ERROR ? "✘ " : "⚠ ") + i.message(), i.severity() == Validator.Severity.ERROR ? Theme.ERR : Theme.WARN);
        if (t != null && t.hasConditionParam())
            inspector.row(inspector.primary("◆ Edit logic", W, () -> openFirstCondition(p)));

        inspector.section("General");
        inspector.label("Name on the timeline");
        inspector.row(inspector.text(t == null ? "label" : t.name(), p.label, W, s -> { beforeEdit(p.id + "label"); p.label = s; changed(); }));
        inspector.label("Start  ·  Length   (ticks or 0:05.5)");
        EditBox tick = inspector.text("tick", String.valueOf(p.tick), W / 2 - 2, s -> {
            int v = Durations.parseTicks(s);
            if (v < 0 || (v == 0 && !s.trim().matches("0+(\\.0*)?|00?:00(\\.0+)?"))) return;
            beforeEdit(p.id + "tick"); p.tick = v; changed();
        });
        EditBox dur = inspector.text("length", String.valueOf(p.duration), W / 2 - 1, s -> {
            beforeEdit(p.id + "dur"); p.duration = Math.max(0, Durations.parseTicks(s)); changed();
        });
        inspector.row(tick, dur);
        inspector.row(inspector.toggle("Enabled", p.enabled, W / 2 - 2, v -> { beforeEdit("!"); p.enabled = v; changed(); }),
                inspector.toggle("Breakpoint", p.breakpoint, W / 2 - 1, v -> { beforeEdit("!"); p.breakpoint = v; changed(); }));
        List<String> errs = List.of("project default", "CONTINUE", "STOP_BRANCH", "FAIL_TASK");
        inspector.row(inspector.cycle("If it fails: ", errs, p.onError == null ? "project default" : p.onError.name(), W, s -> {
            beforeEdit("!");
            p.onError = s.equals("project default") ? null : Policies.ErrorPolicy.valueOf(s);
            changed();
        }));
        if (t != null && !t.params().isEmpty()) {
            inspector.section("Settings");
            ParamForm.build(inspector, t.params(), p.params, this, p.id + ".");
        }
        inspector.section("Actions");
        inspector.row(inspector.button("Duplicate", W / 2 - 2, this::duplicateSelection), inspector.danger("Delete", W / 2 - 1, this::deleteSelection));
    }

    private void markerInspector(Marker m, int W) {
        inspector.title = "⚑  Marker";
        inspector.hint("Phase markers name the stages of the sequence shown in the overlay and monitor.");
        inspector.section("Marker");
        inspector.label("Name");
        inspector.row(inspector.text("name", m.name, W, s -> { beforeEdit(m.id + "name"); m.name = s; changed(); }));
        inspector.row(inspector.cycle("Kind: ", List.of("PHASE", "CUE", "AUDIO"), m.kind.name(), W, s -> { beforeEdit("!"); m.kind = Policies.MarkerKind.valueOf(s); changed(); }));
        inspector.row(inspector.toggle("Announce in overlay", m.announce, W, v -> { beforeEdit("!"); m.announce = v; changed(); }));
        inspector.label("Tick");
        inspector.row(inspector.text("tick", String.valueOf(m.tick), W, s -> { beforeEdit(m.id + "tick"); m.tick = Math.max(0, Durations.parseTicks(s)); changed(); }));
        inspector.row(inspector.danger("Delete marker", W, () -> { beforeEdit("!"); tl.markers.remove(m); selMarker = null; changed(); buildInspector(); }));
    }

    private void timelineInspector(int W) {
        boolean main = tl == project.main;
        inspector.title = main ? "▤  " + project.name : "ƒ  " + tl.name;
        inspector.section(main ? "Sequence" : "Function");
        inspector.label("Name");
        inspector.row(inspector.text("name", main ? project.name : tl.name, W, s -> {
            beforeEdit("tlname");
            if (main) project.name = s; else tl.name = s;
            changed();
        }));
        if (!main) {
            inspector.label("Inputs  (name:type, …)");
            inspector.row(inspector.text("x:number, who:text", decls(tl.inputs), W, s -> { beforeEdit("inputs"); tl.inputs = parseDecls(s); changed(); }));
            inspector.label("Outputs  (name:type, …)");
            inspector.row(inspector.text("result:number", decls(tl.outputs), W, s -> { beforeEdit("outputs"); tl.outputs = parseDecls(s); changed(); }));
            inspector.row(inspector.danger("Delete function", W, this::confirmDeleteFunction));
        } else {
            inspector.label("Description");
            inspector.row(inspector.text("what this sequence does", project.description, W, s -> { beforeEdit("desc"); project.description = s; changed(); }));
        }
        inspector.section("Overview");
        inspector.label(tl.pieces.size() + " pieces  ·  " + tl.tracks.size() + " tracks  ·  " + Durations.format(tl.contentLength()));
        inspector.label(project.functions.size() + " functions  ·  " + project.audio.size() + " audio  ·  " + project.triggers.size() + " triggers");
        inspector.section("Problems");
        if (report == null) return;
        if (report.issues.isEmpty()) { inspector.label("✔ No problems found", Theme.OK); }
        else {
            int n = 0;
            for (Validator.Issue i : report.issues) {
                if (n++ >= 30) break;
                int col = i.severity() == Validator.Severity.ERROR ? Theme.ERR : i.severity() == Validator.Severity.WARNING ? Theme.WARN : Theme.INFO;
                if (i.pieceId() != null) inspector.row(inspector.button((i.severity() == Validator.Severity.ERROR ? "✘ " : "⚠ ") + i.message(), W, () -> focusPiece(i.timelineId(), i.pieceId())));
                else inspector.label(i.message(), col);
            }
        }
        inspector.section("Tips");
        inspector.hint("Drag pieces from the left panel onto a track. Right-click anything for options. Double-click a logic piece to edit its conditions.");
    }

    private void confirmDeleteFunction() {
        Timeline f = tl;
        if (f == project.main) return;
        minecraft.setScreen(new ConfirmScreen(ok -> {
            minecraft.setScreen(this);
            if (!ok) return;
            beforeEdit("!");
            project.functions.remove(f);
            switchTimeline(project.main);
            changed();
        }, Component.literal("Delete function '" + f.name + "'?"), Component.literal("Pieces calling it will report a missing function.")));
    }

    private static String decls(List<VariableDecl> l) {
        StringBuilder sb = new StringBuilder();
        for (VariableDecl d : l) { if (sb.length() > 0) sb.append(", "); sb.append(d.name).append(':').append(d.type.id()); }
        return sb.toString();
    }

    private static List<VariableDecl> parseDecls(String s) {
        List<VariableDecl> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String[] kv = p.split(":", 2);
            out.add(new VariableDecl(kv[0].trim(), csequencer.core.value.ValueType.byId(kv.length > 1 ? kv[1].trim() : "text")));
        }
        return out;
    }

    private void focusPiece(String timelineId, String pieceId) {
        Timeline t = project.timeline(timelineId);
        if (t != null && t != tl) switchTimeline(t);
        Piece p = tl.piece(pieceId);
        if (p == null) return;
        sel.clear();
        sel.add(p.id);
        scrollTick = Math.max(0, p.tick - (x1() - x0()) / ppt / 3);
        buildInspector();
    }

    // ── Editing actions ────────────────────────────────────────────────────

    private List<Piece> selected() {
        List<Piece> out = new ArrayList<>();
        for (String id : sel) { Piece p = tl.piece(id); if (p != null) out.add(p); }
        return out;
    }

    private Marker marker(String id) {
        for (Marker m : tl.markers) if (m.id.equals(id)) return m;
        return null;
    }

    private Piece place(PieceType type, int tick, String trackId) {
        String track = trackId != null && tl.track(trackId) != null ? trackId : tl.tracks.get(0).id;
        beforeEdit("!");
        Piece p = TimelineOps.create(tl, type.id(), track, snap(Math.max(0, tick)));
        sel.clear();
        sel.add(p.id);
        selMarker = null;
        changed();
        buildInspector();
        return p;
    }

    private void insertPiece(int tick, String trackId) {
        minecraft.setScreen(new PieceLibraryScreen(this, type -> place(type, tick, trackId)));
    }

    private void addAudioPiece(String audioId) {
        beforeEdit("!");
        String track = null;
        for (Track t : tl.tracks) if (t.name.toLowerCase(Locale.ROOT).contains("audio")) track = t.id;
        if (track == null) {
            Track t = new Track("Audio");
            t.color = 0xFFAB47BC;
            tl.tracks.add(t);
            track = t.id;
        }
        Piece p = TimelineOps.create(tl, "audio.play", track, playhead);
        p.params.addProperty("audio", audioId);
        AudioRef a = project.audio(audioId);
        if (a != null) { p.duration = a.durationTicks(); p.label = "♪ " + a.name; }
        if (project.settings.timing == Policies.TimingMode.TICK && tl == project.main) {
            project.settings.timing = Policies.TimingMode.REALTIME;
            Toasts.info("Timing set to REALTIME so commands stay in sync with the audio");
        }
        sel.clear();
        sel.add(p.id);
        changed();
        buildInspector();
    }

    private void addTrack() { addTrackAt(tl.tracks.size()); }

    private void addTrackAt(int index) {
        beforeEdit("!");
        Track t = new Track("Track " + (tl.tracks.size() + 1));
        t.color = PALETTE[tl.tracks.size() % PALETTE.length];
        tl.tracks.add(Math.max(0, Math.min(tl.tracks.size(), index)), t);
        selTrack = t.id;
        changed();
    }

    private void addMarker() { addMarkerAt(playhead); }

    private void addMarkerAt(int tick) {
        minecraft.setScreen(new PromptScreen(this, "New phase marker", "Phase name (shown in the overlay)", "Phase " + (tl.phases().size() + 1), name -> {
            beforeEdit("!");
            Marker m = new Marker(name, tick, Policies.MarkerKind.PHASE);
            tl.markers.add(m);
            selMarker = m.id;
            sel.clear();
            changed();
            buildInspector();
        }));
    }

    private void groupSelection() {
        List<Piece> s = selected();
        if (s.isEmpty()) return;
        minecraft.setScreen(new PromptScreen(this, "Group", "Group name", "Group " + (tl.groups.size() + 1), name -> {
            beforeEdit("!");
            PieceGroup g = TimelineOps.group(tl, s, name);
            g.color = PALETTE[tl.groups.size() % PALETTE.length];
            changed();
        }));
    }

    private void ungroupSelection() {
        Set<String> groups = new HashSet<>();
        for (Piece p : selected()) if (!p.groupId.isEmpty()) groups.add(p.groupId);
        if (groups.isEmpty()) return;
        beforeEdit("!");
        for (String g : groups) TimelineOps.ungroup(tl, g);
        changed();
    }

    private void deleteSelection() {
        if (sel.isEmpty()) {
            if (selMarker != null) { beforeEdit("!"); tl.markers.remove(marker(selMarker)); selMarker = null; changed(); buildInspector(); }
            return;
        }
        beforeEdit("!");
        TimelineOps.delete(tl, selected());
        sel.clear();
        changed();
        buildInspector();
    }

    private void duplicateSelection() {
        List<Piece> s = selected();
        if (s.isEmpty()) return;
        beforeEdit("!");
        int min = Integer.MAX_VALUE, max = 0;
        for (Piece p : s) { min = Math.min(min, p.tick); max = Math.max(max, p.tick + Math.max(1, displayTicks(p))); }
        List<Piece> copies = TimelineOps.duplicate(tl, s, max - min);
        sel.clear();
        for (Piece c : copies) sel.add(c.id);
        changed();
        buildInspector();
    }

    private void copySelection() {
        if (sel.isEmpty()) return;
        clipboard = TimelineOps.copy(tl, selected());
        minecraft.keyboardHandler.setClipboard(clipboard);
        Toasts.info("Copied " + sel.size() + " piece(s)");
    }

    private void paste() { pasteAt(playhead, selTrack); }

    private void pasteAt(int tick, String trackId) {
        String clip = minecraft.keyboardHandler.getClipboard();
        if (clip == null || !clip.contains("commandsequencer.clipboard")) clip = clipboard;
        if (clip == null) { Toasts.info("Nothing to paste"); return; }
        beforeEdit("!");
        int base = Math.max(0, tl.trackIndex(trackId == null ? tl.tracks.get(0).id : trackId));
        List<Piece> pasted = TimelineOps.paste(tl, clip, tick, base);
        sel.clear();
        for (Piece p : pasted) sel.add(p.id);
        changed();
        buildInspector();
    }

    private void undo() {
        String s = history.undo(ProjectCodec.writeCompact(project));
        if (s != null) restore(s);
    }

    private void redo() {
        String s = history.redo(ProjectCodec.writeCompact(project));
        if (s != null) restore(s);
    }

    private void restore(String json) {
        String tlId = tl.id;
        project = ProjectCodec.read(json).project();
        tl = project.timeline(tlId) != null ? project.timeline(tlId) : project.main;
        sel.removeIf(id -> tl.piece(id) == null);
        changed();
        buildInspector();
    }

    private void switchTimeline(Timeline t) {
        tl = t;
        sel.clear();
        selMarker = null;
        selTrack = tl.tracks.isEmpty() ? null : tl.tracks.get(0).id;
        scrollTick = 0;
        scrollTrack = 0;
        rebuildWidgets();
    }

    private void editReusable() {
        List<PickerScreen.Option> opts = new ArrayList<>();
        opts.add(new PickerScreen.Option("+ New reusable condition", "use it from any logic graph via 'Reusable condition'", "\u0000new", Theme.ACCENT, "Actions"));
        for (ConditionGraph c : project.conditions) opts.add(new PickerScreen.Option(c.name, c.nodes.size() + " nodes", c.id, 0xFF42A5F5, "In this project"));
        minecraft.setScreen(new PickerScreen(this, "Reusable conditions", opts, id -> {
            if (id.equals("\u0000new")) {
                minecraft.setScreen(new PromptScreen(this, "Reusable condition", "Name", "Condition " + (project.conditions.size() + 1), name -> {
                    beforeEdit("!");
                    ConditionGraph g = new ConditionGraph();
                    g.id = Ids.next("c");
                    g.name = name.trim();
                    project.conditions.add(g);
                    changed();
                    editReusableGraph(g);
                }));
            } else {
                for (ConditionGraph c : project.conditions) if (c.id.equals(id)) editReusableGraph(c);
            }
        }));
    }

    private void editReusableGraph(ConditionGraph g) {
        minecraft.setScreen(new LogicGraphScreen(this, project, g.name, g.toJson(), out -> {
            beforeEdit("!");
            ConditionGraph n = out.size() == 0 ? new ConditionGraph() : ConditionGraph.fromJson(out);
            g.nodes = n.nodes;
            g.edges = n.edges;
            g.root = n.root == null ? "" : n.root;
            changed();
        }));
    }

    private void zoom(double factor, double aroundX) {
        double t = tickAt(aroundX);
        ppt = Math.max(0.05, Math.min(32, ppt * factor));
        scrollTick = Math.max(0, t - (aroundX - x0()) / ppt);
    }

    // ── Context menus ──────────────────────────────────────────────────────

    private void pieceMenu(Piece p, double mx, double my) {
        if (!sel.contains(p.id)) { sel.clear(); sel.add(p.id); buildInspector(); }
        PieceType t = PieceRegistry.piece(p.type);
        PopupMenu m = new PopupMenu().title(sel.size() > 1 ? sel.size() + " pieces" : (t == null ? p.type : t.name()));
        if (sel.size() == 1 && t != null && t.hasConditionParam()) m.add("◆ Edit logic…", "L", true, () -> openFirstCondition(p));
        if (sel.size() == 1 && t != null && J.str(p.params, "function", "").length() > 0 && project.function(J.str(p.params, "function", "")) != null)
            m.add("ƒ Open function", "", true, () -> openFunction(project.function(J.str(p.params, "function", "")).id));
        m.add("Duplicate", "Ctrl+D", true, this::duplicateSelection);
        m.add("Copy", "Ctrl+C", true, this::copySelection);
        m.add("Cut", "Ctrl+X", true, () -> { copySelection(); deleteSelection(); });
        m.separator();
        boolean grouped = false;
        for (Piece x : selected()) if (!x.groupId.isEmpty()) grouped = true;
        m.add(grouped ? "Ungroup" : "Group…", grouped ? "Shift+G" : "G", true, grouped ? this::ungroupSelection : this::groupSelection);
        if (!p.groupId.isEmpty() && tl.group(p.groupId) != null) m.add("Collapse group", "", true, () -> { beforeEdit("!"); tl.group(p.groupId).collapsed = true; changed(); });
        m.add(p.enabled ? "Disable" : "Enable", "", true, () -> { beforeEdit("!"); boolean on = !p.enabled; for (Piece x : selected()) x.enabled = on; changed(); buildInspector(); });
        m.add(p.breakpoint ? "Remove breakpoint" : "Add breakpoint", "B", true, () -> { beforeEdit("!"); boolean on = !p.breakpoint; for (Piece x : selected()) x.breakpoint = on; changed(); buildInspector(); });
        m.add("Run from here", "", true, () -> run(p.tick, false));
        if (sel.size() == 1 && p.type.equals("audio.play")) {
            int at = Math.max(0, snap((int) Math.round(tickAt(mx))));
            boolean inside = at > p.tick && at < p.tick + displayTicks(p);
            m.separator();
            m.add("✂ Split here", "S", inside, () -> splitAudio(p, at));
            m.add("Trim start to here", "", inside, () -> trimStart(p, at));
            m.add("Trim end to here", "", inside, () -> { beforeEdit("!"); p.duration = at - p.tick; changed(); buildInspector(); });
            m.add("Use full length", "", p.duration > 0 || J.d(p.params, "offsetMs", 0) > 0, () -> {
                beforeEdit("!");
                p.tick = Math.max(0, p.tick - (int) (J.d(p.params, "offsetMs", 0) / 50));
                p.params.addProperty("offsetMs", 0);
                p.duration = 0;
                changed();
                buildInspector();
            });
            m.add("Fade in 1s / out 1s", "", true, () -> {
                beforeEdit("!");
                p.params.addProperty("fadeInMs", 1000);
                p.params.addProperty("fadeOutMs", 1000);
                if (p.duration == 0) p.duration = displayTicks(p);
                changed();
                buildInspector();
            });
        }
        m.separator();
        m.danger("Delete", "Del", this::deleteSelection);
        openMenu(m, mx, my);
    }

    // ── Audio editing ──────────────────────────────────────────────────────

    /** Ticks of audio left in the file after this piece's start offset. */
    private int audioRemaining(Piece p) {
        AudioRef a = project.audio(J.str(p.params, "audio", ""));
        if (a == null || a.durationMs <= 0) return Integer.MAX_VALUE / 4;
        return Math.max(1, a.durationTicks() - (int) (J.d(p.params, "offsetMs", 0) / 50));
    }

    /** Cuts an audio piece in two at {@code at}: the second part continues where the first stops. */
    private void splitAudio(Piece p, int at) {
        int len = displayTicks(p);
        if (at <= p.tick || at >= p.tick + len) { Toasts.info("Put the cursor inside the audio piece to split it"); return; }
        beforeEdit("!");
        int first = at - p.tick;
        Piece second = p.duplicate();
        second.tick = at;
        second.params.addProperty("offsetMs", (long) J.d(p.params, "offsetMs", 0) + first * 50L);
        second.duration = len - first;
        second.params.addProperty("fadeInMs", 0);
        p.duration = first;
        p.params.addProperty("fadeOutMs", 0);
        tl.pieces.add(tl.pieces.indexOf(p) + 1, second);
        sel.clear();
        sel.add(second.id);
        changed();
        buildInspector();
        Toasts.info("Split at " + Durations.format(at));
    }

    private void trimStart(Piece p, int at) {
        int len = displayTicks(p);
        if (at <= p.tick || at >= p.tick + len) return;
        beforeEdit("!");
        int cut = at - p.tick;
        p.params.addProperty("offsetMs", (long) J.d(p.params, "offsetMs", 0) + cut * 50L);
        p.tick = at;
        p.duration = len - cut;
        changed();
        buildInspector();
    }

    private void laneMenu(int tick, String trackId, double mx, double my) {
        PopupMenu m = new PopupMenu().title("At " + Durations.format(tick));
        m.add("+ Insert piece here…", "", true, () -> insertPiece(tick, trackId));
        m.add("Paste here", "Ctrl+V", clipboard != null || (minecraft.keyboardHandler.getClipboard() != null && minecraft.keyboardHandler.getClipboard().contains("commandsequencer.clipboard")), () -> pasteAt(tick, trackId));
        m.add("Add phase marker here", "", true, () -> addMarkerAt(tick));
        m.add("Move playhead here", "", true, () -> playhead = tick);
        m.add("Run from here", "", true, () -> run(tick, false));
        m.separator();
        m.add("Select all", "Ctrl+A", !tl.pieces.isEmpty(), () -> { sel.clear(); for (Piece p : tl.pieces) sel.add(p.id); buildInspector(); });
        openMenu(m, mx, my);
    }

    private void trackMenu(Track t, double mx, double my) {
        int idx = tl.trackIndex(t.id);
        PopupMenu m = new PopupMenu().title("Track: " + t.name);
        m.add("Rename…", () -> minecraft.setScreen(new PromptScreen(this, "Rename track", "Track name", t.name, s -> { beforeEdit("!"); t.name = s; changed(); })));
        m.add("Change colour", () -> { beforeEdit("!"); t.color = PALETTE[(indexOf(PALETTE, t.color) + 1) % PALETTE.length]; changed(); });
        m.add(t.muted ? "Unmute (runs again)" : "Mute (skip when running)", () -> { beforeEdit("!"); t.muted = !t.muted; changed(); });
        m.add(t.locked ? "Unlock" : "Lock (prevent moving)", () -> { beforeEdit("!"); t.locked = !t.locked; changed(); });
        m.add(t.collapsed ? "Expand" : "Collapse", () -> { beforeEdit("!"); t.collapsed = !t.collapsed; changed(); });
        m.separator();
        m.add("Add track above", () -> addTrackAt(idx));
        m.add("Add track below", () -> addTrackAt(idx + 1));
        if (idx > 0) m.add("Move up", () -> { beforeEdit("!"); Collections.swap(tl.tracks, idx, idx - 1); changed(); });
        if (idx < tl.tracks.size() - 1) m.add("Move down", () -> { beforeEdit("!"); Collections.swap(tl.tracks, idx, idx + 1); changed(); });
        m.separator();
        m.danger("Delete track", "", () -> minecraft.setScreen(new ConfirmScreen(ok -> {
            minecraft.setScreen(this);
            if (ok) { beforeEdit("!"); if (!TimelineOps.removeTrack(tl, t.id)) Toasts.warn("A timeline needs at least one track"); changed(); }
        }, Component.literal("Delete track '" + t.name + "'?"), Component.literal("Its pieces move to the neighbouring track."))));
        openMenu(m, mx, my);
    }

    private void markerMenu(Marker mk, double mx, double my) {
        selMarker = mk.id;
        sel.clear();
        buildInspector();
        PopupMenu m = new PopupMenu().title("Marker: " + mk.name);
        m.add("Rename…", () -> minecraft.setScreen(new PromptScreen(this, "Rename marker", "Name", mk.name, s -> { beforeEdit("!"); mk.name = s; changed(); buildInspector(); })));
        m.add("Run from here", () -> run(mk.tick, false));
        m.danger("Delete marker", "", () -> { beforeEdit("!"); tl.markers.remove(mk); selMarker = null; changed(); buildInspector(); });
        openMenu(m, mx, my);
    }

    // ── Server: save / run / live control ──────────────────────────────────

    void save(Runnable after) { save(after, false); }

    private void save(Runnable after, boolean force) {
        if (readOnly) { Toasts.error("You may not save this project (read-only)"); return; }
        if (saving) return;
        saving = true;
        ClientNet.uploadProject(project, baseRevision, force, r -> {
            saving = false;
            if (J.b(r, "ok", false)) {
                baseRevision = J.l(r, "revision", baseRevision);
                project.revision = baseRevision;
                dirty = false;
                Toasts.success(J.str(r, "msg", "Saved"));
                if (after != null) after.run();
            } else if (J.b(r, "conflict", false)) {
                minecraft.setScreen(new ConfirmScreen(ok -> {
                    minecraft.setScreen(this);
                    if (ok) save(after, true);
                }, Component.literal("Save conflict"), Component.literal(J.str(r, "msg", "") + "\nOverwrite the server version with yours?")));
            } else {
                Toasts.error(J.str(r, "msg", "Save failed"));
            }
        });
    }

    private void run(int fromTick, boolean debug) {
        Runnable go = () -> ClientNet.request("project.run", ClientNet.obj("id", project.id, "fromTick", fromTick, "debug", debug), r -> {
            if (J.b(r, "ok", false)) {
                liveTaskId = J.str(r, "task", null);
                Toasts.success(J.str(r, "msg", "Started"));
            } else Toasts.error(J.str(r, "msg", "Could not start"));
        });
        if (dirty || baseRevision < 0) {
            if (readOnly) { go.run(); return; }
            save(go);
        } else go.run();
    }

    private void controlLive(String action) {
        if (liveTaskId == null) { Toasts.info("No live run — press ▶ Run first"); return; }
        ClientNet.request("task.control", ClientNet.obj("task", liveTaskId, "action", action), r -> {
            if (!J.b(r, "ok", false)) Toasts.error(J.str(r, "msg", ""));
        });
    }

    private void togglePauseLive() {
        String state = J.str(liveDetail(), "state", "");
        controlLive(state.equals("PAUSED") ? "resume" : "pause");
    }

    /** True once the server reported the live run as finished or gone (polling stops). */
    private boolean liveFinished() {
        JsonObject d = liveDetail();
        if (J.b(d, "gone", false)) return true;
        String st = J.str(d, "state", "");
        return st.equals("COMPLETED") || st.equals("FAILED") || st.equals("CANCELLED");
    }

    private JsonObject liveDetail() {
        if (liveTaskId == null) return new JsonObject();
        return liveTaskId.equals(J.str(ClientState.detail, "id", "")) ? ClientState.detail : new JsonObject();
    }

    // ── Preview (local) ────────────────────────────────────────────────────

    private void togglePreview() {
        if (previewing) {
            previewing = false;
            ClientAudio.stopPreview();
            return;
        }
        previewing = true;
        previewStartMs = System.currentTimeMillis();
        previewStartTick = playhead;
        Piece a = audioPieceAt(playhead);
        if (a != null) {
            AudioRef ref = project.audio(J.str(a.params, "audio", ""));
            if (ref != null) ClientAudio.preview(ref.hash, (playhead - a.tick) * 50L + (long) J.d(a.params, "offsetMs", 0));
        }
    }

    private Piece audioPieceAt(int tick) {
        for (Piece p : tl.pieces) if (p.type.equals("audio.play") && p.tick <= tick && tick < p.tick + Math.max(1, displayTicks(p))) return p;
        return null;
    }

    private Piece audioPieceForWave() {
        for (Piece p : selected()) if (p.type.equals("audio.play")) return p;
        for (Piece p : tl.pieces) if (p.type.equals("audio.play")) return p;
        return null;
    }

    // ── Tick ───────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        if (bSave != null) {
            bSave.active = !readOnly && !saving;
            bSave.setLabel(saving ? "Saving…" : dirty ? "Save •" : "Saved");
            bSave.style(dirty ? UiButton.Style.PRIMARY : UiButton.Style.DEFAULT);
        }
        boolean live = liveTaskId != null && !J.str(liveDetail(), "state", "").isEmpty() && !Set.of("COMPLETED", "FAILED", "CANCELLED").contains(J.str(liveDetail(), "state", ""));
        if (bPause != null) {
            bPause.active = live;
            bPause.setLabel("PAUSED".equals(J.str(liveDetail(), "state", "")) ? "▶" : "⏸");
            bStep.active = live && "PAUSED".equals(J.str(liveDetail(), "state", ""));
            bStop.active = live;
        }
        if (reportDirty) {
            reportDirty = false;
            report = Validator.validate(project, platformInfo(), new Limits(), null, null);
            if (sel.isEmpty() && selMarker == null && !(getFocused() instanceof EditBox)) buildInspector();
        }
        if (previewing) {
            playhead = previewStartTick + (int) ((System.currentTimeMillis() - previewStartMs) / 50);
            double right = tickAt(x1() - 40);
            if (playhead > right) scrollTick += playhead - right;
        }
        if (liveTaskId != null && !liveFinished() && System.currentTimeMillis() - lastDetailReq > 400) {
            lastDetailReq = System.currentTimeMillis();
            ClientNet.send("task.detail", ClientNet.obj("task", liveTaskId));
        }
        Piece wp = audioPieceForWave();
        if (wp != null) {
            AudioRef ref = project.audio(J.str(wp.params, "audio", ""));
            if (ref != null && !waveforms.containsKey(ref.hash) && waveformRequested.add(ref.hash)) {
                ClientAudio.decodeAsync(ref.hash, pcm -> {
                    waveforms.put(ref.hash, AudioDecoder.waveform(pcm, 4096));
                    if (ref.durationMs == 0) ref.durationMs = pcm.durationMs();
                });
            }
        }
    }

    private PlatformInfo platformInfo() {
        var caps = J.strings(ClientState.hello, "capabilities");
        if (caps.isEmpty()) return null;
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        for (String c : caps) try { set.add(Capability.valueOf(c)); } catch (IllegalArgumentException ignored) {}
        return new PlatformInfo(J.str(ClientState.hello, "platform", "server"), "", J.str(ClientState.hello, "minecraft", ""), set);
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    @Override
    protected void drawContent(GuiGraphics g, int mx, int my, float pt) {
        int X0 = x0(), X1 = x1(), RY = rulerY(), TY = tracksY(), TB = tracksBottom();
        // toolbar strip + info line
        g.fill(0, HEADER, width, HEADER + 23, Theme.SURFACE);
        g.fill(0, HEADER + 23, width, HEADER + 24, Theme.BORDER);
        JsonObject live = liveDetail();
        String title = (tl == project.main ? project.name : project.name + "  ›  ƒ " + tl.name) + (readOnly ? "  (read-only)" : "");
        g.drawString(font, Theme.clip(font, title, X1 - 10), 8, infoY() + 3, Theme.TEXT, false);
        String right;
        int rightCol;
        if (liveTaskId != null && !J.str(live, "state", "").isEmpty()) {
            String st = J.str(live, "state", "");
            right = "● " + Theme.stateLabel(st) + "  " + Durations.format(J.i(live, "position", 0)) + (J.str(live, "phase", "").isEmpty() ? "" : "  ·  " + J.str(live, "phase", ""));
            rightCol = Theme.stateColor(st);
        } else {
            right = "⏵ " + Durations.format(playhead) + "  ·  snap " + (ClientConfig.editorSnap == 0 ? "off" : ClientConfig.editorSnap + "t");
            rightCol = Theme.MUTED;
        }
        g.drawString(font, right, X1 - font.width(right) - 4, infoY() + 3, rightCol, false);

        if (showPalette) drawPalette(g, mx, my);
        else if (ClientConfig.paletteMode == 1) drawRail(g, mx, my);

        // ruler
        g.fill(palW(), RY, X1, RY + RULER_H, Theme.SURFACE_2);
        g.fill(palW(), RY + RULER_H - 1, X1, RY + RULER_H, Theme.BORDER);
        g.drawString(font, "TRACKS", palW() + 8, RY + 4, Theme.MUTED, false);
        int step = rulerStep();
        int first = (int) (Math.floor(scrollTick / step) * step);
        g.enableScissor(X0, RY, X1, TB);
        for (int t = first; tx(t) < X1; t += step) {
            int x = (int) tx(t);
            g.fill(x, RY + 9, x + 1, RY + RULER_H - 1, Theme.BORDER_HI);
            g.drawString(font, step >= 20 ? Durations.clock(t) : String.valueOf(t), x + 3, RY + 3, Theme.MUTED, false);
        }
        for (Marker m : tl.markers) {
            int x = (int) tx(m.tick);
            if (x < X0 - 80 || x > X1) continue;
            int c = m.kind == Policies.MarkerKind.PHASE ? m.color : m.kind == Policies.MarkerKind.AUDIO ? 0xFFCE93D8 : 0xFF90A4AE;
            g.fill(x, RY, x + 1, TB, (c & 0x00FFFFFF) | 0x55000000);
            String label = "⚑ " + m.name;
            int fw = font.width(label) + 6;
            g.fill(x, RY + 1, x + fw, RY + 11, m.id.equals(selMarker) ? 0xFFFFFFFF : c);
            g.drawString(font, label, x + 3, RY + 2, 0xFF101010, false);
        }
        g.disableScissor();

        // track headers + lanes
        int HX = palW();
        for (int i = scrollTrack; i < tl.tracks.size(); i++) {
            Track t = tl.tracks.get(i);
            int y = trackTop(i), h = trackH(t);
            if (y >= TB) break;
            boolean selT = t.id.equals(selTrack);
            g.fill(HX, y, X0 - 1, y + h - 1, selT ? Theme.SURFACE_3 : Theme.SURFACE);
            g.fill(HX, y, HX + 3, y + h - 1, t.color);
            g.drawString(font, Theme.clip(font, (t.collapsed ? "▸ " : "▾ ") + t.name, hdrW() - 14), HX + 7, y + (t.collapsed ? 2 : 4), t.muted ? Theme.MUTED : Theme.TEXT, false);
            if (!t.collapsed) {
                String flags = (t.muted ? "muted " : "") + (t.locked ? "locked" : "");
                long count = tl.pieces.stream().filter(p -> p.trackId.equals(t.id)).count();
                g.drawString(font, flags.isEmpty() ? count + " pieces" : flags.trim(), HX + 7, y + 14, flags.isEmpty() ? Theme.MUTED : Theme.WARN, false);
            }
            g.fill(X0, y, X1, y + h - 1, i % 2 == 0 ? 0xFF0F131D : 0xFF0C1018);
            g.fill(HX, y + h - 1, X1, y + h, 0xFF1A2030);
        }
        int lastBottom = tl.tracks.isEmpty() ? TY : trackTop(tl.tracks.size() - 1) + trackH(tl.tracks.get(tl.tracks.size() - 1));
        if (lastBottom < TB - 12) {
            boolean hov = in(mx, my, HX, lastBottom + 2, hdrW() - 2, 12);
            g.drawString(font, "+ add track", HX + 7, lastBottom + 4, hov ? Theme.TEXT : Theme.MUTED, false);
        }

        g.enableScissor(X0, TY, X1, TB);
        for (int t = first; tx(t) < X1; t += step) g.fill((int) tx(t), TY, (int) tx(t) + 1, Math.min(TB, lastBottom), 0x10FFFFFF);
        drawGroups(g);
        Set<String> errorPieces = new HashSet<>();
        if (report != null) for (Validator.Issue is : report.issues) if (is.severity() == Validator.Severity.ERROR && is.pieceId() != null) errorPieces.add(is.pieceId());
        Set<String> collapsedGroups = new HashSet<>();
        for (PieceGroup gr : tl.groups) if (gr.collapsed) collapsedGroups.add(gr.id);
        String nextId = null;
        Map<String, Integer> clocks = new HashMap<>();
        for (JsonElement el : J.arr(live, "branchList")) {
            JsonObject b = el.getAsJsonObject();
            if (!J.str(b, "timeline", "").equals(tl.id) || J.str(b, "state", "").equals("DONE")) continue;
            clocks.merge(J.str(b, "track", ""), J.i(b, "clock", 0), Math::max);
            if (nextId == null) nextId = J.str(b, "nextId", null);
        }
        for (Piece p : tl.pieces) {
            int ti = tl.trackIndex(p.trackId);
            if (ti < scrollTrack) continue;
            Track t = tl.tracks.get(ti);
            int y = trackTop(ti);
            if (y >= TB) continue;
            if (!p.groupId.isEmpty() && collapsedGroups.contains(p.groupId)) continue;
            int x = (int) tx(p.tick), w = pieceW(p);
            if (x + w < X0 || x > X1) continue;
            Integer clock = clocks.get(p.trackId);
            boolean done = clock != null && p.tick < clock;
            drawPiece(g, p, x, y + 2, w, trackH(t) - 5, sel.contains(p.id), errorPieces.contains(p.id), p.id.equals(nextId), done, t.collapsed);
        }
        for (PieceGroup gr : tl.groups) {
            if (!gr.collapsed) continue;
            int[] span = groupSpan(gr);
            if (span == null) continue;
            int y = trackTop(span[2]);
            int x = (int) tx(span[0]), w = (int) Math.max(18, (span[1] - span[0]) * ppt);
            Theme.card(g, x, y + 2, w, TRACK_H - 5, gr.color, Theme.dim(gr.color, 0.7f));
            g.drawString(font, Theme.clip(font, "▸ " + gr.name + " (" + span[3] + ")", w - 6), x + 4, y + 8, 0xFF101010, false);
        }
        for (var e : clocks.entrySet()) {
            int ti = tl.trackIndex(e.getKey());
            if (ti < scrollTrack) continue;
            int y = trackTop(ti);
            int x = (int) tx(e.getValue());
            g.fill(x, y, x + 2, y + trackH(tl.tracks.get(ti)), Theme.stateColor(J.str(live, "state", "RUNNING")));
        }
        if (drag == Drag.BOX) {
            int bx0 = (int) Math.min(dragX0, mx), bx1 = (int) Math.max(dragX0, mx), by0 = (int) Math.min(dragY0, my), by1 = (int) Math.max(dragY0, my);
            g.fill(bx0, by0, bx1, by1, 0x224FC3F7);
            g.fill(bx0, by0, bx1, by0 + 1, Theme.INFO);
            g.fill(bx0, by1 - 1, bx1, by1, Theme.INFO);
            g.fill(bx0, by0, bx0 + 1, by1, Theme.INFO);
            g.fill(bx1 - 1, by0, bx1, by1, Theme.INFO);
        }
        g.disableScissor();

        // empty state
        if (tl.pieces.isEmpty() && drag != Drag.NEW) {
            int cx = (X0 + X1) / 2, cy = (TY + TB) / 2;
            Theme.empty(g, font, cx, cy, "✚", "This timeline is empty",
                    ClientConfig.paletteMode != 2 ? "Drag a piece from the left panel onto a track" : "Right-click a track or use + Insert");
        }

        // drop ghost
        if (flyoutCat != null && drag != Drag.NEW) drawFlyout(g, mx, my);
        if (drag == Drag.NEW && dragNew != null) {
            int ti = trackAt(my);
            if (mx >= X0 && mx < X1 && ti >= 0) {
                int tick = snap((int) Math.round(tickAt(mx)));
                int gx = (int) tx(tick), gy = trackTop(ti) + 2;
                g.fill(gx, gy, gx + 60, gy + TRACK_H - 5, (dragNew.color() & 0x00FFFFFF) | 0x88000000);
                g.fill(gx, gy, gx + 1, gy + TRACK_H - 5, 0xFFFFFFFF);
                g.drawString(font, Durations.format(tick), gx + 3, gy - 9 < TY ? gy + TRACK_H - 3 : gy - 9, Theme.TEXT, false);
            }
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            String label = PieceRegistry.categoryOf(dragNew).icon() + " " + dragNew.name();
            Theme.card(g, mx + 8, my + 4, font.width(label) + 10, 13, Theme.SURFACE_3, dragNew.color());
            g.drawString(font, label, mx + 13, my + 7, Theme.TEXT, false);
            g.pose().popPose();
        }

        // playhead
        int px = (int) tx(playhead);
        if (px >= X0 && px <= X1) {
            g.fill(px, RY, px + 1, TB, 0xFFFFD54F);
            g.fill(px - 3, RY, px + 4, RY + 3, 0xFFFFD54F);
            g.fill(px - 2, RY + 3, px + 3, RY + 4, 0xFFFFD54F);
        }
        if (hasWave()) drawWave(g, X0, X1, TB + 2);

        // status line
        g.fill(0, height - 13, width, height, Theme.SURFACE);
        String status = tl.pieces.size() + " pieces  ·  zoom " + String.format(Locale.ROOT, "%.1f", ppt) + "px/t  ·  timing " + project.settings.timing.name().toLowerCase(Locale.ROOT)
                + "  ·  scope " + project.settings.scope.name().toLowerCase(Locale.ROOT) + (previewing ? "  ·  ♪ preview" : "");
        g.drawString(font, Theme.clip(font, status, X1 - 10), 6, height - 10, Theme.MUTED, false);
        if (report != null) {
            long errs = report.count(Validator.Severity.ERROR), warns = report.count(Validator.Severity.WARNING);
            String s = errs == 0 && warns == 0 ? "✔ no problems" : (errs > 0 ? "✘ " + errs + " error" + (errs == 1 ? "" : "s") : "") + (warns > 0 ? "  ⚠ " + warns : "");
            g.drawString(font, s, X1 - font.width(s) - 4, height - 10, errs > 0 ? Theme.ERR : warns > 0 ? Theme.WARN : Theme.OK, false);
        }
        inspector.render(g);
        // hover tooltip
        if (drag == Drag.NONE && mx >= X0 && mx < X1 && my >= TY && my < TB) {
            Piece h = pieceAt(mx, my);
            if (h != null) {
                PieceType t = PieceRegistry.piece(h.type);
                tipArea(mx, my, 1, 1, () -> (t == null ? h.type : t.name()) + (h.label.isEmpty() || (t != null && h.label.equals(t.name())) ? "" : " — " + h.label)
                        + "\nat " + Durations.format(h.tick) + (displayTicks(h) > 0 ? "  ·  " + displayTicks(h) + " ticks" : "")
                        + (h.enabled ? "" : "\n(disabled)") + (h.breakpoint ? "\n● breakpoint" : "") + "\nRight-click for options");
            }
        }
    }

    // ── Pieces panel ───────────────────────────────────────────────────────

    /** Rows of the panel: a PieceCategory (header) or a PieceType. */
    private List<Object> paletteRows() {
        List<Object> out = new ArrayList<>();
        String q = paletteSearchValue == null ? "" : paletteSearchValue.trim();
        if (!q.isEmpty()) {
            out.addAll(PieceRegistry.search(q));
            return out;
        }
        for (PieceCategory c : PieceRegistry.pieceCategories()) {
            out.add(c);
            if (openCategories.contains(c.name())) out.addAll(PieceRegistry.inCategory(c));
        }
        return out;
    }

    private int paletteTop() { return areaY() + 20; }

    private void drawPalette(GuiGraphics g, int mx, int my) {
        int x = 0, w = palW(), top = paletteTop(), bottom = height - 14;
        g.fill(x, areaY(), w, bottom, Theme.SURFACE);
        g.fill(w - 1, areaY(), w, bottom, Theme.BORDER);
        List<Object> rows = paletteRows();
        int total = 0;
        for (Object o : rows) total += o instanceof PieceCategory ? PAL_HEAD : PAL_ROW;
        paletteScroll = Math.max(0, Math.min(paletteScroll, Math.max(0, total - (bottom - top))));
        g.enableScissor(x, top, w - 1, bottom);
        int y = top - paletteScroll;
        for (Object o : rows) {
            if (o instanceof PieceCategory c) {
                boolean open = openCategories.contains(c.name());
                boolean hov = in(mx, my, x, y, w, PAL_HEAD);
                g.fill(x + 2, y + 1, w - 3, y + PAL_HEAD - 1, hov ? Theme.SURFACE_3 : Theme.SURFACE_2);
                g.fill(x + 2, y + 1, x + 4, y + PAL_HEAD - 1, c.color());
                g.drawString(font, c.icon(), x + 8, y + 4, c.color(), false);
                g.drawString(font, c.name(), x + 20, y + 4, Theme.TEXT, false);
                g.drawString(font, open ? "▾" : "▸", w - 12, y + 4, Theme.MUTED, false);
                if (hov) tipArea(x, Math.max(y, top), w, PAL_HEAD, c::description);
                y += PAL_HEAD;
            } else {
                PieceType t = (PieceType) o;
                boolean hov = in(mx, my, x, y, w, PAL_ROW) && my >= top;
                if (hov) g.fill(x + 2, y, w - 3, y + PAL_ROW, Theme.HOVER);
                Theme.dot(g, x + 10, y + 5, t.color());
                g.drawString(font, Theme.clip(font, t.name(), w - 26), x + 19, y + 4, hov ? Theme.TEXT : Theme.TEXT_DIM, false);
                if (hov) tipArea(x, y, w, PAL_ROW, () -> t.name() + "\n" + t.description() + "\nDrag onto a track, or click to add at the playhead");
                y += PAL_ROW;
            }
        }
        g.disableScissor();
        if (rows.isEmpty()) g.drawCenteredString(font, "No match", w / 2, top + 20, Theme.MUTED);
    }

    // ── Rail (minimized Pieces panel) ──────────────────────────────────────

    private int railTop() { return areaY() + 2; }

    /** Row 0 is the "expand" button; rows 1.. are categories. */
    private int railRow(double mx, double my) {
        if (ClientConfig.paletteMode != 1 || mx < 0 || mx >= RAIL_W || my < railTop() || my >= height - 14) return -1;
        int r = (int) ((my - railTop()) / 18);
        return r <= PieceRegistry.pieceCategories().size() ? r : -1;
    }

    private void drawRail(GuiGraphics g, int mx, int my) {
        int top = railTop(), bottom = height - 14;
        g.fill(0, areaY(), RAIL_W, bottom, Theme.SURFACE);
        g.fill(RAIL_W - 1, areaY(), RAIL_W, bottom, Theme.BORDER);
        int hov = railRow(mx, my);
        g.fill(2, top, RAIL_W - 3, top + 16, hov == 0 ? Theme.SURFACE_3 : Theme.SURFACE_2);
        g.drawCenteredString(font, "»", RAIL_W / 2, top + 4, Theme.TEXT);
        if (hov == 0) tipArea(0, top, RAIL_W, 16, () -> "Expand the Pieces panel (P)");
        List<PieceCategory> cats = PieceRegistry.pieceCategories();
        for (int i = 0; i < cats.size(); i++) {
            PieceCategory c = cats.get(i);
            int y = top + 18 * (i + 1);
            if (y + 16 > bottom) break;
            boolean open = c == flyoutCat;
            g.fill(2, y, RAIL_W - 3, y + 16, open ? Theme.SURFACE_3 : hov == i + 1 ? Theme.HOVER : Theme.SURFACE_2);
            g.fill(2, y, 4, y + 16, c.color());
            g.drawCenteredString(font, c.icon(), RAIL_W / 2 + 1, y + 4, c.color());
            if (hov == i + 1 && flyoutCat == null) tipArea(0, y, RAIL_W, 16, () -> c.name() + "\n" + c.description() + "\nClick to show its pieces");
        }
    }

    private int flyTop() {
        int i = PieceRegistry.pieceCategories().indexOf(flyoutCat);
        int y = railTop() + 18 * (i + 1);
        int h = flyHeight();
        return Math.max(areaY(), Math.min(y, height - 16 - h));
    }

    private int flyHeight() {
        int n = flyoutCat == null ? 0 : PieceRegistry.inCategory(flyoutCat).size();
        return Math.min(PAL_HEAD + n * PAL_ROW + 4, height - 16 - areaY());
    }

    private boolean inFlyout(double mx, double my) {
        if (flyoutCat == null) return false;
        int t = flyTop();
        return mx >= RAIL_W && mx < RAIL_W + FLY_W && my >= t && my < t + flyHeight();
    }

    private void drawFlyout(GuiGraphics g, int mx, int my) {
        int x = RAIL_W, t = flyTop(), h = flyHeight(), w = FLY_W;
        List<PieceType> list = PieceRegistry.inCategory(flyoutCat);
        flyoutScroll = Math.max(0, Math.min(flyoutScroll, Math.max(0, list.size() * PAL_ROW - (h - PAL_HEAD - 4))));
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        Theme.card(g, x, t, w, h, Theme.SURFACE, flyoutCat.color());
        g.drawString(font, flyoutCat.icon() + " " + flyoutCat.name(), x + 6, t + 4, flyoutCat.color(), false);
        g.drawString(font, "×", x + w - 10, t + 4, Theme.MUTED, false);
        g.enableScissor(x + 1, t + PAL_HEAD, x + w - 1, t + h - 2);
        int y = t + PAL_HEAD - flyoutScroll;
        for (PieceType pt : list) {
            boolean hov = in(mx, my, x, y, w, PAL_ROW) && my >= t + PAL_HEAD && my < t + h - 2;
            if (hov) g.fill(x + 2, y, x + w - 2, y + PAL_ROW, Theme.HOVER);
            Theme.dot(g, x + 9, y + 5, pt.color());
            g.drawString(font, Theme.clip(font, pt.name(), w - 24), x + 18, y + 4, hov ? Theme.TEXT : Theme.TEXT_DIM, false);
            if (hov) tipArea(x, y, w, PAL_ROW, () -> pt.name() + "\n" + pt.description() + "\nDrag onto a track, or click to add at the playhead");
            y += PAL_ROW;
        }
        g.disableScissor();
        g.pose().popPose();
    }

    /** Piece under the mouse in the flyout, or null. */
    private PieceType flyoutPieceAt(double mx, double my) {
        if (!inFlyout(mx, my)) return null;
        int t = flyTop();
        if (my < t + PAL_HEAD || my >= t + flyHeight() - 2) return null;
        int i = (int) ((my - (t + PAL_HEAD) + flyoutScroll) / PAL_ROW);
        List<PieceType> list = PieceRegistry.inCategory(flyoutCat);
        return i >= 0 && i < list.size() ? list.get(i) : null;
    }

    private Object paletteAt(double mx, double my) {
        if (!showPalette || mx >= palW() || my < paletteTop() || my >= height - 14) return null;
        int y = paletteTop() - paletteScroll;
        for (Object o : paletteRows()) {
            int h = o instanceof PieceCategory ? PAL_HEAD : PAL_ROW;
            if (my >= y && my < y + h) return o;
            y += h;
        }
        return null;
    }

    // ── Timeline drawing helpers ───────────────────────────────────────────

    private int rulerStep() {
        double minPx = 44;
        int[] steps = {1, 2, 5, 10, 20, 40, 100, 200, 400, 1200, 2400, 6000, 12000, 36000};
        for (int s : steps) if (s * ppt >= minPx) return s;
        return 72000;
    }

    private int[] groupSpan(PieceGroup gr) {
        int min = Integer.MAX_VALUE, max = 0, track = Integer.MAX_VALUE, n = 0;
        for (Piece p : tl.pieces) {
            if (!gr.id.equals(p.groupId)) continue;
            min = Math.min(min, p.tick);
            max = Math.max(max, p.tick + Math.max(1, displayTicks(p)));
            track = Math.min(track, Math.max(scrollTrack, tl.trackIndex(p.trackId)));
            n++;
        }
        return n == 0 ? null : new int[]{min, max, track, n};
    }

    private void drawGroups(GuiGraphics g) {
        for (PieceGroup gr : tl.groups) {
            if (gr.collapsed) continue;
            int minT = Integer.MAX_VALUE, maxT = 0, minTr = Integer.MAX_VALUE, maxTr = -1;
            for (Piece p : tl.pieces) {
                if (!gr.id.equals(p.groupId)) continue;
                minT = Math.min(minT, p.tick);
                maxT = Math.max(maxT, p.tick + (int) Math.max(1, Math.max(displayTicks(p), 16 / ppt)));
                int ti = tl.trackIndex(p.trackId);
                minTr = Math.min(minTr, ti);
                maxTr = Math.max(maxTr, ti);
            }
            if (maxTr < 0 || maxTr < scrollTrack) continue;
            int y0 = trackTop(Math.max(scrollTrack, minTr)), y1 = trackTop(maxTr) + trackH(tl.tracks.get(maxTr));
            int gx0 = (int) tx(minT) - 3, gx1 = (int) tx(maxT) + 3;
            g.fill(gx0, y0, gx1, y1, (gr.color & 0x00FFFFFF) | 0x1C000000);
            g.fill(gx0, y0, gx1, y0 + 1, gr.color);
            g.fill(gx0, y1 - 1, gx1, y1, (gr.color & 0x00FFFFFF) | 0x66000000);
            g.drawString(font, "▾ " + gr.name, gx0 + 2, y0 + 1, gr.color, false);
        }
    }

    private void drawPiece(GuiGraphics g, Piece p, int x, int y, int w, int h, boolean selected, boolean error, boolean next, boolean done, boolean collapsedTrack) {
        PieceType t = PieceRegistry.piece(p.type);
        PieceCategory cat = PieceRegistry.categoryOf(t);
        int color = p.color != 0 ? p.color : t == null ? 0xFF555555 : t.color();
        if (!p.enabled) color = 0xFF3A3F4A;
        if (done) color = Theme.dim(color, 0.5f);
        PieceKind kind = t == null ? PieceKind.ACTION : t.kind();
        int body = Theme.dim(color, 0.42f) | 0xFF000000;
        // body + coloured left edge
        Theme.card(g, x, y, w, h, body, selected ? 0xFFFFFFFF : Theme.dim(color, 0.75f));
        g.fill(x + 1, y + 1, x + 4, y + h - 1, color);
        switch (kind) {
            case LOGIC -> { for (int i = 0; i < 4; i++) g.fill(x - 4 + i, y + h / 2 - i, x - 3 + i, y + h / 2 + i + 1, color); }
            case EVENT -> { for (int i = x + 5; i < x + w - 1; i += 4) g.fill(i, y + 1, Math.min(i + 2, x + w - 1), y + 2, color); }
            case AUDIO -> drawMiniWave(g, p, x + 4, y + 1, w - 5, h - 2);
            default -> {}
        }
        if (error) g.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, Theme.ERR);
        if (next && (System.currentTimeMillis() / 300) % 2 == 0) g.fill(x - 2, y - 2, x + w + 2, y - 1, 0xFFFFD54F);
        if (p.breakpoint) { g.fill(x + w - 7, y + 2, x + w - 3, y + 6, 0xFFFF1744); }
        if (collapsedTrack || w < 18) return;
        String icon = cat == null ? "" : cat.icon() + " ";
        g.drawString(font, Theme.clip(font, icon + p.displayName(), w - 9), x + 7, y + 2, p.enabled ? Theme.TEXT : Theme.MUTED, false);
        if (h > 15) {
            String sub = subtitle(p);
            if (!sub.isEmpty()) g.drawString(font, Theme.clip(font, sub, w - 9), x + 7, y + 11, Theme.dim(color, 1.0f) | 0xFF000000, false);
        }
    }

    private String subtitle(Piece p) {
        return switch (p.type) {
            case "action.command" -> "/" + J.str(p.params, "command", "");
            case "action.chat", "action.actionbar" -> J.str(p.params, "message", "");
            case "action.title" -> J.str(p.params, "title", "");
            case "flow.delay" -> J.str(p.params, "ticks", "") + " ticks";
            case "flow.call", "flow.repeat", "flow.while", "flow.foreach" -> {
                Timeline f = project.function(J.str(p.params, "function", ""));
                yield "→ " + (f == null ? "(no function)" : f.name);
            }
            case "event.listen", "event.wait" -> {
                var e = PieceRegistry.event(J.str(p.params, "eventType", ""));
                yield e == null ? J.str(p.params, "eventType", "") : e.name();
            }
            case "var.set" -> J.str(p.params, "name", "") + " " + J.str(p.params, "op", "set");
            case "action.teleport" -> "→ " + J.str(p.params, "pos", "");
            default -> "";
        };
    }

    private void drawMiniWave(GuiGraphics g, Piece p, int x, int y, int w, int h) {
        AudioRef ref = project.audio(J.str(p.params, "audio", ""));
        float[] wf = ref == null ? null : waveforms.get(ref.hash);
        double offTicks = J.d(p.params, "offsetMs", 0) / 50.0;
        if (wf != null && ref.durationMs > 0) {
            double ticksTotal = ref.durationMs / 50.0;
            int mid = y + h / 2;
            for (int i = 0; i < w; i++) {
                int bin = (int) ((i / ppt + offTicks) / ticksTotal * wf.length);
                if (bin >= wf.length) break;
                int a = (int) (wf[bin] * (h / 2 - 1));
                g.fill(x + i, mid - a, x + i + 1, mid + a + 1, 0x55FFFFFF);
            }
        }
        // fade ramps
        int fin = (int) (J.d(p.params, "fadeInMs", 0) / 50 * ppt), fout = (int) (J.d(p.params, "fadeOutMs", 0) / 50 * ppt);
        for (int i = 0; i < Math.min(fin, w); i++) g.fill(x + i, y, x + i + 1, y + (int) ((h - 1) * (1 - i / (double) Math.max(1, fin))), 0x66000000);
        for (int i = 0; i < Math.min(fout, w); i++) g.fill(x + w - 1 - i, y, x + w - i, y + (int) ((h - 1) * (1 - i / (double) Math.max(1, fout))), 0x66000000);
        if (offTicks > 0) g.fill(x, y, x + 1, y + h, 0xFFFFD54F);
    }

    private void drawWave(GuiGraphics g, int X0, int X1, int y) {
        Piece p = audioPieceForWave();
        AudioRef ref = p == null ? null : project.audio(J.str(p.params, "audio", ""));
        g.fill(palW(), y, X1, y + WAVE_H, Theme.SURFACE);
        g.drawString(font, "♪", palW() + 7, y + 11, 0xFFCE93D8, false);
        if (ref == null) return;
        g.drawString(font, Theme.clip(font, ref.name, hdrW() - 20), palW() + 18, y + 11, Theme.TEXT_DIM, false);
        float[] wf = waveforms.get(ref.hash);
        if (wf == null) { g.drawString(font, "loading waveform…", X0 + 6, y + 11, Theme.MUTED, false); return; }
        double ticksTotal = Math.max(1, ref.durationMs / 50.0);
        int mid = y + WAVE_H / 2;
        g.enableScissor(X0, y, X1, y + WAVE_H);
        for (int x = Math.max(X0, (int) tx(p.tick)); x < Math.min(X1, (int) tx(p.tick + displayTicks(p))); x++) {
            double tick = tickAt(x) - p.tick + J.d(p.params, "offsetMs", 0) / 50.0;
            int bin = (int) (tick / ticksTotal * wf.length);
            if (bin < 0 || bin >= wf.length) continue;
            int a = (int) (wf[bin] * (WAVE_H / 2 - 3));
            g.fill(x, mid - a, x + 1, mid + a + 1, 0xFFA978E8);
        }
        g.fill(X0, mid, X1, mid + 1, 0x22FFFFFF);
        g.disableScissor();
    }

    // ── Input ──────────────────────────────────────────────────────────────

    private Piece pieceAt(double mx, double my) {
        int ti = trackAt(my);
        if (ti < 0) return null;
        Track t = tl.tracks.get(ti);
        Set<String> collapsedGroups = new HashSet<>();
        for (PieceGroup gr : tl.groups) if (gr.collapsed) collapsedGroups.add(gr.id);
        for (int i = tl.pieces.size() - 1; i >= 0; i--) {
            Piece p = tl.pieces.get(i);
            if (!p.trackId.equals(t.id) || (!p.groupId.isEmpty() && collapsedGroups.contains(p.groupId))) continue;
            double x = tx(p.tick);
            PieceType type = PieceRegistry.piece(p.type);
            if (mx >= x - (type != null && type.kind() == PieceKind.LOGIC ? 4 : 0) && mx <= x + pieceW(p)) return p;
        }
        return null;
    }

    private PieceGroup collapsedGroupAt(double mx, double my) {
        for (PieceGroup gr : tl.groups) {
            if (!gr.collapsed) continue;
            int[] s = groupSpan(gr);
            if (s == null) continue;
            int y = trackTop(s[2]);
            if (my >= y && my < y + TRACK_H && mx >= tx(s[0]) && mx <= tx(s[0]) + Math.max(18, (s[1] - s[0]) * ppt)) return gr;
        }
        return null;
    }

    private Marker markerAt(double mx) {
        for (Marker m : tl.markers) {
            double x = tx(m.tick);
            if (mx >= x - 2 && mx <= x + font.width("⚑ " + m.name) + 6) return m;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (menuOpen()) return super.mouseClicked(mx, my, button);
        if (super.mouseClicked(mx, my, button)) return true;
        if (inspector.contains(mx, my)) return false;
        if (!(getFocused() instanceof EditBox) || !in(mx, my, 0, 0, palW(), paletteTop())) setFocused(null);
        int X0 = x0(), X1 = x1();
        boolean dbl = System.currentTimeMillis() - lastClickMs < 300 && Math.abs(mx - lastClickX) < 4 && Math.abs(my - lastClickY) < 4;
        lastClickMs = System.currentTimeMillis();
        lastClickX = mx;
        lastClickY = my;
        dragX0 = mx;
        dragY0 = my;
        rightMoved = false;

        // Rail + flyout (minimized Pieces panel)
        if (flyoutCat != null) {
            if (inFlyout(mx, my)) {
                if (my < flyTop() + PAL_HEAD) { if (mx >= RAIL_W + FLY_W - 14) flyoutCat = null; return true; }
                PieceType pt = flyoutPieceAt(mx, my);
                if (pt != null && button == 0) { dragNew = pt; drag = Drag.NEW; }
                return true;
            }
            if (railRow(mx, my) < 0) { flyoutCat = null; return true; }
        }
        int rr = railRow(mx, my);
        if (rr >= 0) {
            if (rr == 0) setPaletteMode(0);
            else {
                PieceCategory c = PieceRegistry.pieceCategories().get(rr - 1);
                flyoutCat = flyoutCat == c ? null : c;
                flyoutScroll = 0;
            }
            return true;
        }

        // Pieces panel
        Object pal = paletteAt(mx, my);
        if (pal != null) {
            if (pal instanceof PieceCategory c) {
                if (!openCategories.remove(c.name())) openCategories.add(c.name());
            } else if (button == 0) {
                dragNew = (PieceType) pal;
                drag = Drag.NEW;
            }
            return true;
        }
        if (mx < palW()) return false;

        if (button == 1 || button == 2) {
            if (mx >= palW() && my >= tracksY() && my < tracksBottom()) { drag = Drag.PAN; return true; }
            if (button == 1 && my >= rulerY() && my < tracksY() && mx >= X0) {
                Marker m = markerAt(mx);
                if (m != null) markerMenu(m, mx, my);
                return true;
            }
            return false;
        }
        // Track headers
        if (mx < X0 && my >= tracksY() && my < tracksBottom()) {
            int ti = trackAt(my);
            if (ti < 0) {
                int last = tl.tracks.isEmpty() ? tracksY() : trackTop(tl.tracks.size() - 1) + trackH(tl.tracks.get(tl.tracks.size() - 1));
                if (my >= last && my < last + 16) addTrack();
                return true;
            }
            Track t = tl.tracks.get(ti);
            int y = trackTop(ti);
            selTrack = t.id;
            if (mx < palW() + 18 && my < y + 12) { beforeEdit("!"); t.collapsed = !t.collapsed; changed(); return true; }
            if (dbl) minecraft.setScreen(new PromptScreen(this, "Rename track", "Track name", t.name, s -> { beforeEdit("!"); t.name = s; changed(); }));
            return true;
        }
        if (mx < X0 || mx >= X1) return false;
        // Ruler: markers and playhead
        if (my >= rulerY() && my < tracksY()) {
            Marker m = markerAt(mx);
            if (m != null) {
                selMarker = m.id;
                sel.clear();
                dragSnapshot = ProjectCodec.writeCompact(project);
                drag = Drag.MARKER;
                buildInspector();
                if (dbl) minecraft.setScreen(new PromptScreen(this, "Rename marker", "Name", m.name, s -> { beforeEdit("!"); m.name = s; changed(); buildInspector(); }));
                return true;
            }
            playhead = Math.max(0, snap((int) Math.round(tickAt(mx))));
            drag = Drag.PLAYHEAD;
            return true;
        }
        if (my < tracksY() || my >= tracksBottom()) return false;
        PieceGroup cg = collapsedGroupAt(mx, my);
        if (cg != null) {
            if (dbl) { beforeEdit("!"); cg.collapsed = false; changed(); return true; }
            if (!hasControlDown()) sel.clear();
            for (Piece p : tl.pieces) if (cg.id.equals(p.groupId)) sel.add(p.id);
            startMove(null);
            buildInspector();
            return true;
        }
        Piece hit = pieceAt(mx, my);
        int ti = trackAt(my);
        if (ti >= 0) selTrack = tl.tracks.get(ti).id;
        selMarker = null;
        if (hit != null) {
            if (dbl) {
                PieceType t = PieceRegistry.piece(hit.type);
                if (t != null && t.hasConditionParam()) openFirstCondition(hit);
                else if (project.function(J.str(hit.params, "function", "")) != null) openFunction(project.function(J.str(hit.params, "function", "")).id);
                return true;
            }
            if (hasControlDown()) {
                if (!sel.remove(hit.id)) sel.add(hit.id);
            } else if (!sel.contains(hit.id)) {
                sel.clear();
                sel.add(hit.id);
            }
            double right = tx(hit.tick) + pieceW(hit);
            if (sel.size() == 1 && hit.type.equals("audio.play") && mx <= tx(hit.tick) + 3 && pieceW(hit) >= 16) {
                dragSnapshot = ProjectCodec.writeCompact(project);
                dragAnchor = hit.id;
                dragOrig.clear();
                dragOrig.put(hit.id, new int[]{hit.tick, displayTicks(hit), (int) J.d(hit.params, "offsetMs", 0)});
                drag = Drag.RESIZE_LEFT;
            } else if (sel.size() == 1 && mx >= right - 3 && pieceW(hit) >= 16) {
                dragSnapshot = ProjectCodec.writeCompact(project);
                dragAnchor = hit.id;
                drag = Drag.RESIZE;
            } else startMove(hit.id);
            buildInspector();
            return true;
        }
        if (dbl && ti >= 0) { insertPiece((int) Math.round(tickAt(mx)), selTrack); return true; }
        if (!hasControlDown()) sel.clear();
        drag = Drag.BOX;
        buildInspector();
        return true;
    }

    private static int indexOf(int[] a, int v) { for (int i = 0; i < a.length; i++) if (a[i] == v) return i; return 0; }

    private void startMove(String anchor) {
        dragSnapshot = ProjectCodec.writeCompact(project);
        dragAnchor = anchor;
        dragOrig.clear();
        for (Piece p : selected()) dragOrig.put(p.id, new int[]{p.tick, tl.trackIndex(p.trackId)});
        drag = Drag.MOVE;
    }

    private void openFirstCondition(Piece p) {
        PieceType t = PieceRegistry.piece(p.type);
        if (t == null) return;
        for (var spec : t.params()) {
            if (spec.type == csequencer.core.pieces.ParamType.CONDITION) {
                JsonElement e = p.params.get(spec.key);
                editCondition(spec.label, e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject(), out -> {
                    beforeEdit("!");
                    p.params.add(spec.key, out);
                    changed();
                    buildInspector();
                });
                return;
            }
            if (spec.type == csequencer.core.pieces.ParamType.BRANCHES) {
                var arr = p.params.has(spec.key) && p.params.get(spec.key).isJsonArray() ? p.params.getAsJsonArray(spec.key) : new com.google.gson.JsonArray();
                if (arr.size() == 0) {
                    JsonObject b = new JsonObject();
                    b.add("condition", new JsonObject());
                    b.addProperty("target", "");
                    arr.add(b);
                    p.params.add(spec.key, arr);
                }
                JsonObject b = arr.get(0).getAsJsonObject();
                editCondition("Branch 1", J.obj(b, "condition") == null ? new JsonObject() : J.obj(b, "condition"), out -> {
                    beforeEdit("!");
                    b.add("condition", out);
                    changed();
                    buildInspector();
                });
                return;
            }
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (menuOpen()) return true;
        switch (drag) {
            case PAN -> {
                if (Math.abs(mx - dragX0) + Math.abs(my - dragY0) > 3) rightMoved = true;
                scrollTick = Math.max(0, scrollTick - dx / ppt);
                return true;
            }
            case PLAYHEAD -> { playhead = Math.max(0, snap((int) Math.round(tickAt(mx)))); return true; }
            case MARKER -> {
                Marker m = marker(selMarker);
                if (m != null) m.tick = Math.max(0, snap((int) Math.round(tickAt(mx))));
                return true;
            }
            case RESIZE -> {
                Piece p = tl.piece(dragAnchor);
                if (p != null) {
                    int d = Math.max(1, snap((int) Math.round(tickAt(mx))) - p.tick);
                    if (p.type.equals("audio.play")) d = Math.min(d, audioRemaining(p));
                    p.duration = d;
                }
                return true;
            }
            case RESIZE_LEFT -> {
                Piece p = tl.piece(dragAnchor);
                int[] o = dragOrig.get(dragAnchor);
                if (p != null && o != null) {
                    int end = o[0] + o[1];
                    int newTick = Math.max(0, Math.min(end - 1, snap((int) Math.round(tickAt(mx)))));
                    int delta = newTick - o[0];
                    int offsetMs = Math.max(0, o[2] + delta * 50);
                    newTick = o[0] + (offsetMs - o[2]) / 50; // cannot trim before the start of the file
                    p.tick = newTick;
                    p.params.addProperty("offsetMs", offsetMs);
                    p.duration = Math.max(1, end - newTick);
                }
                return true;
            }
            case MOVE -> {
                int dt = (int) Math.round((mx - dragX0) / ppt);
                int dtr = (int) Math.floor((my - dragY0 + TRACK_H / 2.0) / TRACK_H);
                if (dragAnchor != null && dragOrig.containsKey(dragAnchor)) {
                    int target = snap(dragOrig.get(dragAnchor)[0] + dt);
                    dt = target - dragOrig.get(dragAnchor)[0];
                }
                int minTick = Integer.MAX_VALUE;
                for (int[] o : dragOrig.values()) minTick = Math.min(minTick, o[0]);
                if (minTick + dt < 0) dt = -minTick;
                for (Piece p : selected()) {
                    int[] o = dragOrig.get(p.id);
                    if (o == null) continue;
                    Track cur = tl.tracks.get(Math.max(0, Math.min(tl.tracks.size() - 1, o[1])));
                    if (cur.locked) continue;
                    p.tick = o[0] + dt;
                    int nt = Math.max(0, Math.min(tl.tracks.size() - 1, o[1] + dtr));
                    p.trackId = tl.tracks.get(nt).id;
                }
                return true;
            }
            case BOX, NEW -> { return true; }
            default -> { return super.mouseDragged(mx, my, button, dx, dy); }
        }
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        Drag d = drag;
        drag = Drag.NONE;
        if (d == Drag.NEW && dragNew != null) {
            PieceType t = dragNew;
            dragNew = null;
            flyoutCat = null;
            int ti = trackAt(my);
            boolean moved = Math.abs(mx - dragX0) + Math.abs(my - dragY0) > 4;
            if (moved && mx >= x0() && mx < x1() && ti >= 0) place(t, (int) Math.round(tickAt(mx)), tl.tracks.get(ti).id);
            else if (!moved) place(t, playhead, selTrack);
            return true;
        }
        if (d == Drag.PAN && button == 1 && !rightMoved) {
            Piece p = pieceAt(mx, my);
            int ti = trackAt(my);
            if (mx < x0() && ti >= 0) trackMenu(tl.tracks.get(ti), mx, my);
            else if (p != null) pieceMenu(p, mx, my);
            else if (ti >= 0) laneMenu(Math.max(0, snap((int) Math.round(tickAt(mx)))), tl.tracks.get(ti).id, mx, my);
            return true;
        }
        if (d == Drag.BOX) {
            double t0 = tickAt(Math.min(dragX0, mx)), t1 = tickAt(Math.max(dragX0, mx));
            for (Piece p : tl.pieces) {
                int ti = tl.trackIndex(p.trackId);
                if (ti < scrollTrack) continue;
                int y = trackTop(ti), h = trackH(tl.tracks.get(ti));
                boolean rowHit = y + h > Math.min(dragY0, my) && y < Math.max(dragY0, my);
                double pe = p.tick + Math.max(1, pieceW(p) / ppt);
                if (rowHit && pe >= t0 && p.tick <= t1) sel.add(p.id);
            }
            buildInspector();
        } else if ((d == Drag.MOVE || d == Drag.RESIZE || d == Drag.RESIZE_LEFT || d == Drag.MARKER) && dragSnapshot != null) {
            if (!dragSnapshot.equals(ProjectCodec.writeCompact(project))) {
                history.push(dragSnapshot);
                changed();
                buildInspector();
            }
            dragSnapshot = null;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (menuOpen()) return true;
        if (inspector.contains(mx, my)) return inspector.scroll(mx, my, delta);
        if (inFlyout(mx, my)) {
            flyoutScroll = Math.max(0, flyoutScroll - (int) Math.signum(delta) * 30);
            return true;
        }
        if (showPalette && mx < palW()) {
            paletteScroll = Math.max(0, paletteScroll - (int) Math.signum(delta) * 30);
            return true;
        }
        if (my >= tracksY() && my < tracksBottom() && (mx < x0() || hasControlDown())) {
            scrollTrack = Math.max(0, Math.min(tl.tracks.size() - 1, scrollTrack - (int) Math.signum(delta)));
            return true;
        }
        if (mx >= x0() && mx < x1()) {
            if (hasShiftDown()) scrollTick = Math.max(0, scrollTick - delta * 40 / ppt);
            else zoom(delta > 0 ? 1.2 : 1 / 1.2, mx);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (menuOpen()) return super.keyPressed(key, scan, mods);
        boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0, shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        if (getFocused() instanceof EditBox eb && eb.isFocused()) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) { setFocused(null); eb.setFocused(false); return true; }
            return super.keyPressed(key, scan, mods);
        }
        if (ctrl) {
            switch (key) {
                case GLFW.GLFW_KEY_Z -> { if (shift) redo(); else undo(); return true; }
                case GLFW.GLFW_KEY_Y -> { redo(); return true; }
                case GLFW.GLFW_KEY_S -> { save(null); return true; }
                case GLFW.GLFW_KEY_C -> { copySelection(); return true; }
                case GLFW.GLFW_KEY_X -> { copySelection(); deleteSelection(); return true; }
                case GLFW.GLFW_KEY_V -> { paste(); return true; }
                case GLFW.GLFW_KEY_D -> { duplicateSelection(); return true; }
                case GLFW.GLFW_KEY_A -> { sel.clear(); for (Piece p : tl.pieces) sel.add(p.id); buildInspector(); return true; }
                case GLFW.GLFW_KEY_F -> { if (paletteSearch != null) { setFocused(paletteSearch); paletteSearch.setFocused(true); } return true; }
                default -> {}
            }
        }
        switch (key) {
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> { deleteSelection(); return true; }
            case GLFW.GLFW_KEY_SPACE -> { togglePreview(); return true; }
            case GLFW.GLFW_KEY_HOME -> { playhead = 0; scrollTick = 0; return true; }
            case GLFW.GLFW_KEY_M -> { addMarker(); return true; }
            case GLFW.GLFW_KEY_G -> { if (shift) ungroupSelection(); else groupSelection(); return true; }
            case GLFW.GLFW_KEY_B -> { if (!sel.isEmpty()) { beforeEdit("!"); for (Piece p : selected()) p.breakpoint = !p.breakpoint; changed(); buildInspector(); } return true; }
            case GLFW.GLFW_KEY_L, GLFW.GLFW_KEY_ENTER -> { if (sel.size() == 1) openFirstCondition(tl.piece(sel.iterator().next())); return true; }
            case GLFW.GLFW_KEY_I -> { insertPiece(playhead, selTrack); return true; }
            case GLFW.GLFW_KEY_S -> {
                List<Piece> audio = new ArrayList<>();
                for (Piece p : selected()) if (p.type.equals("audio.play")) audio.add(p);
                if (audio.isEmpty()) { Piece a = audioPieceAt(playhead); if (a != null) audio.add(a); }
                if (audio.isEmpty()) Toasts.info("Select an audio piece (or put the cursor over one) to split it");
                for (Piece p : audio) splitAudio(p, playhead);
                return true;
            }
            case GLFW.GLFW_KEY_P -> { cyclePalette(); return true; }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> {
                int step = (key == GLFW.GLFW_KEY_LEFT ? -1 : 1) * (shift ? Math.max(1, ClientConfig.editorSnap) : 1);
                if (sel.isEmpty()) { playhead = Math.max(0, playhead + step); return true; }
                beforeEdit("nudge");
                TimelineOps.move(tl, selected(), step, 0);
                changed();
                return true;
            }
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                if (sel.isEmpty()) return true;
                beforeEdit("!");
                TimelineOps.move(tl, selected(), 0, key == GLFW.GLFW_KEY_UP ? -1 : 1);
                changed();
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (!sel.isEmpty() || selMarker != null) { sel.clear(); selMarker = null; buildInspector(); return true; }
                onClose();
                return true;
            }
            default -> {}
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        ClientAudio.stopPreview();
        previewing = false;
        if (dirty) Toasts.info("Unsaved edits are kept — reopen the Editor tab to continue");
        minecraft.setScreen(null);
    }

    @Override
    protected void onLeave() {
        ClientAudio.stopPreview();
        previewing = false;
    }

    /** Called by ProjectSettingsScreen after edits. */
    void settingsChanged() { changed(); }
}

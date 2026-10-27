package csequencer.client.gui;

import com.google.gson.JsonObject;
import csequencer.core.json.J;
import csequencer.core.model.ConditionGraph;
import csequencer.core.model.Ids;
import csequencer.core.model.Policies;
import csequencer.core.model.Project;
import csequencer.core.model.ProjectSettings;
import csequencer.core.model.Trigger;
import csequencer.core.pieces.EventType;
import csequencer.core.pieces.PieceRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** Execution policies, overlay announcement settings and automatic triggers of a project. */
public final class ProjectSettingsScreen extends CsqScreen {
    private final TimelineEditorScreen editor;
    private Form left, right;

    public ProjectSettingsScreen(TimelineEditorScreen parent, TimelineEditorScreen editor) {
        super("Project settings", parent);
        this.editor = editor;
    }

    @Override protected Tab tab() { return Tab.EDITOR; }

    private Project p() { return editor.project(); }

    private static <E extends Enum<E>> List<String> names(E[] values) {
        List<String> l = new ArrayList<>();
        for (E e : values) l.add(e.name());
        return l;
    }

    @Override
    protected void build() {
        btn("\u2714 Done", UiButton.Style.PRIMARY, "Back to the editor", width - 64, HEADER + 5, 58, 18, () -> minecraft.setScreen(parent));
        int half = (width - 18) / 2;
        left = new Form(this);
        left.bounds(6, HEADER + 26, half, height - HEADER - 32);
        right = new Form(this);
        right.bounds(12 + half, HEADER + 26, half, height - HEADER - 32);
        buildLeft();
        buildRight();
    }

    private void edit(Runnable r) {
        editor.beforeEdit("!");
        r.run();
        editor.changed();
    }

    private void buildLeft() {
        left.clear();
        ProjectSettings s = p().settings;
        int W = left.width();
        left.title = "Execution";
        left.section("Instances");
        left.row(left.cycle("Scope: ", names(Policies.ExecutionScope.values()), s.scope.name(), W, v -> edit(() -> s.scope = Policies.ExecutionScope.valueOf(v))));
        left.hint("PLAYER: one per player · SEQUENCE: one shared instance · SERVER: ignores disconnects");
        left.row(left.cycle("If already running: ", names(Policies.ConcurrencyPolicy.values()), s.concurrency.name(), W, v -> edit(() -> s.concurrency = Policies.ConcurrencyPolicy.valueOf(v))));
        left.row(left.cycle("Initiator disconnects: ", names(Policies.DisconnectPolicy.values()), s.disconnect.name(), W, v -> edit(() -> s.disconnect = Policies.DisconnectPolicy.valueOf(v))));
        left.label("Grace period before cancelling (ticks)");
        left.row(left.text("ticks", String.valueOf(s.disconnectGraceTicks), W, v -> edit(() -> s.disconnectGraceTicks = Math.max(0, parse(v, s.disconnectGraceTicks)))));
        left.row(left.cycle("After server restart: ", names(Policies.RecoveryPolicy.values()), s.recovery.name(), W, v -> edit(() -> s.recovery = Policies.RecoveryPolicy.valueOf(v))));
        left.row(left.cycle("Timing: ", names(Policies.TimingMode.values()), s.timing.name(), W, v -> edit(() -> s.timing = Policies.TimingMode.valueOf(v))));
        left.hint("REALTIME catches up after lag - use it with audio.");
        left.row(left.cycle("On action error: ", names(Policies.ErrorPolicy.values()), s.errorPolicy.name(), W, v -> edit(() -> s.errorPolicy = Policies.ErrorPolicy.valueOf(v))));
        left.label("Priority (higher runs first in a tick)");
        left.row(left.text("0", String.valueOf(s.priority), W, v -> edit(() -> s.priority = parse(v, 0))));
        left.label("Dimension (DIMENSION scope; empty = initiator's)");
        left.row(left.text("minecraft:overworld", s.dimension, W, v -> edit(() -> s.dimension = v.trim())));
        left.label("Fixed length in ticks (0 = auto, used for progress)");
        left.row(left.text("0", String.valueOf(s.lengthTicks), W, v -> edit(() -> s.lengthTicks = Math.max(0, parse(v, 0)))));
        left.section("Tracking");
        left.hint("The tracked player is who the pieces act on (targets set to Tracked player, and player conditions). Leave it empty to track whoever starts the sequence. A Track Player piece can change it while running.");
        left.label("Tracked player");
        EditBox tracked = left.text("(whoever starts it)", s.trackedPlayer, W - 22, v -> edit(() -> s.trackedPlayer = v.trim()));
        left.row(tracked, left.button("▾", 19, () -> {
            PopupMenu m = new PopupMenu().title("Track which player?");
            m.add((s.trackedPlayer.isEmpty() ? "✔ " : "   ") + "Whoever starts it", () -> { edit(() -> s.trackedPlayer = ""); buildLeft(); });
            var players = ParamForm.onlinePlayers();
            if (!players.isEmpty()) m.separator();
            for (String n : players) m.add((n.equalsIgnoreCase(s.trackedPlayer) ? "✔ " : "   ") + n, "online", true, () -> { edit(() -> s.trackedPlayer = n); buildLeft(); });
            openMenu(m, left.x + 10, HEADER + 60);
        }));
        left.section("Overlay announcements");
        left.row(left.cycle("Who sees it: ", names(Policies.AnnounceMode.values()), s.announce.name(), W, v -> edit(() -> s.announce = Policies.AnnounceMode.valueOf(v))));
        left.label("Teams (TEAMS mode, comma separated)");
        left.row(left.text("red, blue", String.join(", ", s.announceTeams), W, v -> edit(() -> {
            s.announceTeams = new ArrayList<>();
            for (String t : v.split(",")) if (!t.isBlank()) s.announceTeams.add(t.trim());
        })));
        left.row(left.button(() -> s.overlayKeepVisible ? "Visible for the whole run" : "Visible around phase changes", W, () -> edit(() -> s.overlayKeepVisible = !s.overlayKeepVisible)));
        left.label("Announce next phase this many ticks early");
        left.row(left.text("60", String.valueOf(s.preAnnounceTicks), W, v -> edit(() -> s.preAnnounceTicks = Math.max(0, parse(v, 60)))));
    }

    private void buildRight() {
        right.clear();
        int W = right.width();
        right.title = "Automatic triggers (" + p().triggers.size() + ")";
        right.hint("Start this sequence automatically when something happens, a condition becomes true, or a player walks into an area (▣ AREA).");
        for (Trigger t : new ArrayList<>(p().triggers)) {
            right.section(t.name.isEmpty() ? "Trigger" : t.name);
            right.row(right.text("name", t.name, W - 60, v -> edit(() -> t.name = v)),
                    right.button(() -> t.enabled ? "On" : "Off", 30, () -> edit(() -> t.enabled = !t.enabled)),
                    right.button("✕", 24, () -> { edit(() -> p().triggers.remove(t)); buildRight(); }));
            right.row(right.cycle("Kind: ", names(Policies.TriggerKind.values()), t.kind.name(), W, v -> {
                edit(() -> {
                    t.kind = Policies.TriggerKind.valueOf(v);
                    if (t.kind == Policies.TriggerKind.AREA && t.subject == Policies.TriggerSubject.EVENT_PLAYER) t.subject = Policies.TriggerSubject.EACH_PLAYER;
                });
                buildRight();
            }));
            if (t.kind == Policies.TriggerKind.AREA) buildArea(t, W);
            if (t.kind == Policies.TriggerKind.EVENT) {
                EventType et = PieceRegistry.event(t.eventType);
                right.row(right.button("Event: " + (et == null ? t.eventType : et.name()) + " ▾", W, () -> {
                    List<PickerScreen.Option> opts = new ArrayList<>();
                    for (EventType e : PieceRegistry.events()) if (!e.internal() || e.id().startsWith("sequence")) opts.add(new PickerScreen.Option(e.name(), e.id(), e.id()));
                    minecraft.setScreen(new PickerScreen(this, "Trigger event", opts, v -> { edit(() -> t.eventType = v); buildRight(); }));
                }));
            }
            if (t.kind != Policies.TriggerKind.SERVER_START) {
                int nodes = t.condition == null ? 0 : t.condition.nodes.size();
                String condLabel = switch (t.kind) {
                    case EVENT -> "Filter";
                    case AREA -> "Extra conditions";
                    default -> "Condition";
                };
                right.row(right.button(condLabel + " (" + (nodes == 0 && t.kind == Policies.TriggerKind.AREA ? "none — anyone entering" : nodes + " nodes") + ")", W, () ->
                        minecraft.setScreen(new LogicGraphScreen(this, p(), "Trigger " + t.name, t.condition == null ? new JsonObject() : t.condition.toJson(), out -> {
                            edit(() -> t.condition = out.size() == 0 ? null : ConditionGraph.fromJson(out));
                            buildRight();
                        }))));
            }
            if (t.kind == Policies.TriggerKind.CONDITION || t.kind == Policies.TriggerKind.AREA) {
                right.row(right.cycle("Fire when: ", names(Policies.EdgeMode.values()), t.edge.name(), W, v -> edit(() -> t.edge = Policies.EdgeMode.valueOf(v))));
                right.label("Check every N ticks");
                right.row(right.text("10", String.valueOf(t.intervalTicks), W, v -> edit(() -> t.intervalTicks = Math.max(1, parse(v, 10)))));
            }
            right.row(right.cycle("Initiator: ", names(Policies.TriggerSubject.values()), t.subject.name(), W, v -> edit(() -> t.subject = Policies.TriggerSubject.valueOf(v))));
            right.label("Cooldown (ticks)");
            right.row(right.text("0", String.valueOf(t.cooldownTicks), W, v -> edit(() -> t.cooldownTicks = Math.max(0, parse(v, 0)))));
            right.row(right.button(() -> t.oncePerPlayer ? "Once per player ✔" : "Once per player", W / 2 - 1, () -> edit(() -> t.oncePerPlayer = !t.oncePerPlayer)),
                    right.button(() -> t.oncePerServer ? "Once per server ✔" : "Once per server", W / 2 - 1, () -> edit(() -> t.oncePerServer = !t.oncePerServer)));
        }
        right.gap();
        right.row(right.primary("▣ + Area trigger (draw in world)", W, () -> newAreaTrigger(editor)));
        right.row(right.button("+ Add trigger", W, () -> {
            edit(() -> {
                Trigger t = new Trigger();
                t.id = Ids.next("tr");
                t.name = "Trigger " + (p().triggers.size() + 1);
                p().triggers.add(t);
            });
            buildRight();
        }));
        right.gap();
        right.hint("Triggers only work for sequences saved on the server and can be disarmed from the Sequence Monitor.");
    }

    /** Creates an AREA trigger and starts drawing it in the world; returns to the trigger settings afterwards. */
    public static void newAreaTrigger(TimelineEditorScreen editor) {
        ProjectSettingsScreen s = new ProjectSettingsScreen(editor, editor);
        Trigger t = new Trigger();
        t.id = Ids.next("tr");
        t.kind = Policies.TriggerKind.AREA;
        t.subject = Policies.TriggerSubject.EACH_PLAYER;
        t.name = "Area " + (editor.project().triggers.size() + 1);
        csequencer.client.AreaView.beginSelection(s, t.name, null, null, (a, b, dim) -> {
            editor.beforeEdit("!");
            t.areaFrom = a;
            t.areaTo = b;
            t.areaDim = dim;
            editor.project().triggers.add(t);
            editor.changed();
            csequencer.client.Toasts.info("Area trigger added — set its conditions, then save the sequence to arm it");
        });
    }

    private void buildArea(Trigger t, int W) {
        String size = t.hasArea()
                ? (t.areaMax()[0] - t.areaMin()[0] + 1) + "×" + (t.areaMax()[1] - t.areaMin()[1] + 1) + "×" + (t.areaMax()[2] - t.areaMin()[2] + 1) + " blocks"
                : "not set";
        right.hint("Fires for each player who walks into the box (and passes the extra conditions, if any). Area: " + size);
        right.row(right.primary("✎ Draw area in the world", W, () -> {
            csequencer.client.AreaView.beginSelection(this, t.name, t.areaFrom, t.areaTo, (a, b, dim) -> {
                edit(() -> {
                    t.areaFrom = a;
                    t.areaTo = b;
                    t.areaDim = dim;
                });
                csequencer.client.Toasts.info("Area set — save the sequence to arm it");
                buildRight();
            });
        }));
        right.label("Corner 1 (x y z)");
        right.row(right.text("x y z", vec(t.areaFrom), W - 52, v -> { int[] c = parseVec(v); if (c != null) edit(() -> t.areaFrom = c); }),
                right.button("Here", 50, () -> { edit(() -> t.areaFrom = here()); buildRight(); }));
        right.label("Corner 2 (x y z)");
        right.row(right.text("x y z", vec(t.areaTo), W - 52, v -> { int[] c = parseVec(v); if (c != null) edit(() -> t.areaTo = c); }),
                right.button("Here", 50, () -> { edit(() -> t.areaTo = here()); buildRight(); }));
        right.row(right.button(() -> t.areaDim == null || t.areaDim.isEmpty() ? "Dimension: any" : "Dimension: " + t.areaDim, W, () -> {
            edit(() -> t.areaDim = t.areaDim == null || t.areaDim.isEmpty()
                    ? (minecraft.player == null ? "minecraft:overworld" : minecraft.player.level().dimension().location().toString()) : "");
        }));
        right.row(right.button(() -> csequencer.client.AreaView.viewerOn() ? "Show areas in the world: On" : "Show areas in the world: Off", W,
                csequencer.client.AreaView::toggleViewer));
    }

    private static String vec(int[] v) { return v == null ? "" : v[0] + " " + v[1] + " " + v[2]; }

    private static int[] parseVec(String s) {
        String[] p = s.trim().split("[,\\s]+");
        if (p.length != 3) return null;
        try {
            return new int[]{(int) Math.floor(Double.parseDouble(p[0])), (int) Math.floor(Double.parseDouble(p[1])), (int) Math.floor(Double.parseDouble(p[2]))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int[] here() {
        if (minecraft.player == null) return new int[]{0, 64, 0};
        var b = minecraft.player.blockPosition();
        return new int[]{b.getX(), b.getY(), b.getZ()};
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    @Override
    protected void drawContent(GuiGraphics g, int mx, int my, float pt) {
        g.drawString(font, "Project: " + p().name, 8, HEADER + 9, Theme.TEXT, false);
        left.render(g);
        right.render(g);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) { return left.scroll(mx, my, d) || right.scroll(mx, my, d); }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}

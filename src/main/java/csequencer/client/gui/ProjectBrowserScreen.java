package csequencer.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import csequencer.client.ClientNet;
import csequencer.client.ClientState;
import csequencer.client.Toasts;
import csequencer.core.json.J;
import csequencer.core.json.ProjectCodec;
import csequencer.core.model.Ids;
import csequencer.core.model.Project;
import csequencer.core.project.FileProjectStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Project Browser. Left: projects stored on the server (what can run). Right: local files in
 * {@code .minecraft/commandsequencer/csq} (exports, CommandSequencer 1.0 files) for import.
 */
public final class ProjectBrowserScreen extends CsqScreen {
    private static final SimpleDateFormat FMT = new SimpleDateFormat("dd/MM/yy HH:mm");
    private ListPane<JsonObject> server;
    private ListPane<Path> local;
    private final List<Path> localFiles = new ArrayList<>();
    private Button bOpen, bRun, bDup, bDel, bExport, bShare, bImport;

    public ProjectBrowserScreen(Screen parent) { super("Projects", parent); }

    public static Path localDir() { return FMLPaths.GAMEDIR.get().resolve("commandsequencer").resolve("csq"); }

    @Override protected Tab tab() { return Tab.PROJECTS; }

    @Override
    protected void build() {
        ClientNet.send("projects.list", null);
        scanLocal();
        int half = (width - 18) * 62 / 100;
        server = new ListPane<>(24, this::serverRows, this::serverRow);
        server.empty = "No projects on this server yet — create one.";
        server.bounds(6, HEADER + 30, half, height - HEADER - 62);
        local = new ListPane<>(14, () -> localFiles, (g, p, x, y, w, h, s, hv) ->
                g.drawString(font, Theme.clip(font, p.getFileName().toString(), w - 8), x + 4, y + 3, Theme.TEXT, false));
        local.empty = "No local files";
        local.bounds(half + 12, HEADER + 30, width - half - 18, height - HEADER - 62);
        int y = height - 28;
        btn("+ New", UiButton.Style.PRIMARY, "Create an empty project", 6, y, 40, 20, this::create);
        bOpen = btn("Open", 48, y, 44, 20, this::open);
        bRun = btn("Run", 94, y, 36, 20, this::run);
        bDup = btn("Duplicate", 132, y, 60, 20, this::duplicate);
        bShare = btn("Sharing", "Cycle visibility: private / shared / public", 194, y, 54, 20, this::share);
        bExport = btn("Export", "Save a copy to .minecraft/commandsequencer/csq", 250, y, 48, 20, this::export);
        bDel = btn("Delete", UiButton.Style.DANGER, "Delete this project (kept in the server trash)", 300, y, 46, 20, this::delete);
        bImport = btn("Import ▶", "Upload the selected local file to the server", half + 12, y, 60, 20, this::importLocal);
        btn("Folder", "Rescan the local folder", half + 76, y, 46, 20, () -> { scanLocal(); });
    }

    private void scanLocal() {
        localFiles.clear();
        try {
            Files.createDirectories(localDir());
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(localDir(), "*.json")) { for (Path p : ds) localFiles.add(p); }
        } catch (Exception e) {
            Toasts.error("Cannot read " + localDir());
        }
        localFiles.sort(null);
    }

    private List<JsonObject> serverRows() {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : J.arr(ClientState.projects, "projects")) out.add(e.getAsJsonObject());
        return out;
    }

    private void serverRow(GuiGraphics g, JsonObject p, int x, int y, int w, int h, boolean sel, boolean hov) {
        g.drawString(font, Theme.clip(font, J.str(p, "name", ""), w - 120), x + 4, y + 2, Theme.TEXT, false);
        int bx = x + w - 4;
        String vis = J.str(p, "visibility", "SHARED").toLowerCase();
        bx -= font.width(vis) + 8;
        Theme.badge(g, font, vis, bx, y + 2, vis.equals("private") ? Theme.WARN : Theme.INFO);
        if (J.i(p, "running", 0) > 0) { bx -= 50; Theme.badge(g, font, J.i(p, "running", 0) + " running", bx, y + 2, Theme.ACCENT); }
        String date;
        synchronized (FMT) { date = FMT.format(new Date(J.l(p, "updatedAt", 0))); }
        String sub = "by " + (J.str(p, "ownerName", "").isEmpty() ? "—" : J.str(p, "ownerName", "")) + " · rev " + J.l(p, "revision", 0) + " · " + J.i(p, "pieceCount", 0)
                + " pieces · " + date + (J.b(p, "canEdit", false) ? "" : " · read-only");
        g.drawString(font, Theme.clip(font, sub, w - 8), x + 4, y + 13, Theme.SUB, false);
    }

    private JsonObject sel() { return server.selectedItem(); }

    private void create() {
        minecraft.setScreen(new PromptScreen(this, "New project", "Name", "My Sequence", name -> {
            if (name.isBlank()) return;
            Project p = Project.create(name.trim());
            minecraft.setScreen(TimelineEditorScreen.open(p, null));
        }));
    }

    private void open() {
        JsonObject p = sel();
        if (p != null) ClientNet.downloadProject(J.str(p, "id", ""), proj -> minecraft.setScreen(TimelineEditorScreen.open(proj, null)));
    }

    private void run() {
        JsonObject p = sel();
        if (p == null) return;
        ClientNet.request("project.run", ClientNet.obj("id", J.str(p, "id", "")), r -> {
            if (J.b(r, "ok", false)) Toasts.success(J.str(r, "msg", "Started"));
            else Toasts.error(J.str(r, "msg", "Could not start"));
        });
    }

    private void duplicate() {
        JsonObject p = sel();
        if (p == null) return;
        ClientNet.downloadProject(J.str(p, "id", ""), proj -> minecraft.setScreen(new PromptScreen(this, "Duplicate", "Name of the copy", proj.name + " copy", name -> {
            Project c = proj.copy();
            c.id = Ids.next("proj");
            c.name = name.trim();
            c.revision = 0;
            ClientNet.uploadProject(c, -1, false, r -> {
                if (J.b(r, "ok", false)) { Toasts.success(J.str(r, "msg", "")); ClientNet.send("projects.list", null); }
                else Toasts.error(J.str(r, "msg", ""));
            });
        })));
    }

    private void share() {
        JsonObject p = sel();
        if (p == null) return;
        String next = switch (J.str(p, "visibility", "SHARED")) {
            case "PRIVATE" -> "SHARED";
            case "SHARED" -> "PUBLIC";
            default -> "PRIVATE";
        };
        ClientNet.request("project.share", ClientNet.obj("id", J.str(p, "id", ""), "visibility", next), r -> {
            if (!J.b(r, "ok", false)) Toasts.error(J.str(r, "msg", ""));
        });
    }

    private void export() {
        JsonObject p = sel();
        if (p == null) return;
        ClientNet.downloadProject(J.str(p, "id", ""), proj -> {
            try {
                Path f = localDir().resolve(Ids.slug(proj.name) + ".csq.json");
                FileProjectStore.writeAtomic(f, ProjectCodec.write(proj));
                Toasts.success("Exported to " + f.getFileName());
                scanLocal();
            } catch (Exception e) {
                Toasts.error("Export failed: " + e.getMessage());
            }
        });
    }

    private void delete() {
        JsonObject p = sel();
        if (p == null) return;
        minecraft.setScreen(new ConfirmScreen(ok -> {
            minecraft.setScreen(this);
            if (ok) ClientNet.request("project.delete", ClientNet.obj("id", J.str(p, "id", "")), r -> {
                if (J.b(r, "ok", false)) Toasts.success(J.str(r, "msg", "")); else Toasts.error(J.str(r, "msg", ""));
            });
        }, Component.literal("Delete '" + J.str(p, "name", "") + "'?"), Component.literal("The server keeps a copy in its trash folder.")));
    }

    private void importLocal() {
        Path f = local.selectedItem();
        if (f == null) return;
        try {
            var rr = ProjectCodec.read(Files.readString(f, StandardCharsets.UTF_8));
            Project p = rr.project();
            for (JsonElement e : J.arr(ClientState.projects, "projects")) {
                if (J.str(e.getAsJsonObject(), "id", "").equals(p.id) && !J.b(e.getAsJsonObject(), "canEdit", false)) { p.id = Ids.next("proj"); break; }
            }
            for (String w : rr.warnings()) Toasts.warn(w);
            ClientNet.uploadProject(p, -1, true, r -> {
                if (J.b(r, "ok", false)) { Toasts.success(J.str(r, "msg", "Imported")); ClientNet.send("projects.list", null); }
                else Toasts.error(J.str(r, "msg", "Import failed"));
            });
        } catch (Exception e) {
            Toasts.error("Cannot import " + f.getFileName() + ": " + e.getMessage());
        }
    }

    @Override
    public void tick() {
        JsonObject p = sel();
        bOpen.active = p != null;
        bRun.active = p != null && J.b(p, "canRun", false);
        bDup.active = p != null && J.b(ClientState.projects, "canCreate", false);
        bShare.active = p != null && J.b(p, "canDelete", false);
        bExport.active = p != null;
        bDel.active = p != null && J.b(p, "canDelete", false);
        bImport.active = local.selectedItem() != null && J.b(ClientState.projects, "canCreate", false);
    }

    @Override
    protected void drawContent(GuiGraphics g, int mx, int my, float pt) {
        g.drawString(font, "On this server", server.x, HEADER + 18, Theme.SUB, false);
        g.drawString(font, "Local files (" + localDir().getParent().getFileName() + "/csq)", local.x, HEADER + 18, Theme.SUB, false);
        server.render(g, font, mx, my);
        local.render(g, font, mx, my);
        JsonObject p = sel();
        if (p != null && !J.str(p, "description", "").isEmpty())
            g.drawString(font, Theme.clip(font, J.str(p, "description", ""), server.w), server.x, height - 40, Theme.SUB, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int b) {
        if (super.mouseClicked(mx, my, b)) return true;
        int before = server.selected;
        int i = server.click(mx, my);
        if (i >= 0) { if (i == before) open(); return true; }
        return local.click(mx, my) >= 0;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) { return server.scroll(mx, my, d) || local.scroll(mx, my, d); }
}

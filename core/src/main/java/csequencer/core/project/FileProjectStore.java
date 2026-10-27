package csequencer.core.project;

import com.google.gson.JsonObject;
import csequencer.core.engine.ProjectCatalog;
import csequencer.core.json.J;
import csequencer.core.json.ProjectCodec;
import csequencer.core.model.Ids;
import csequencer.core.model.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Server-side project storage: {@code <root>/projects/<id>.csq.json} plus {@code <root>/registry.json}
 * holding server-owned {@link ProjectMeta}. Projects are cached in memory; reads never touch disk.
 * Writes are atomic (temp file + move) and run on the supplied IO executor.
 *
 * <p>Thread model: all public methods are called on the server thread; only the serialized bytes
 * are handed to the IO executor.
 */
public final class FileProjectStore implements ProjectCatalog {
    private final Path root;
    private final Executor io;
    private final Map<String, Entry> byId = new LinkedHashMap<>();
    private final List<String> loadReport = new ArrayList<>();

    public FileProjectStore(Path root, Executor io) {
        this.root = root;
        this.io = io;
    }

    public Path root() { return root; }
    public Path projectsDir() { return root.resolve("projects"); }
    public Path audioDir() { return root.resolve("audio"); }
    public List<String> loadReport() { return List.copyOf(loadReport); }

    /** Loads everything synchronously (server start). Corrupt files are reported and skipped, never deleted. */
    public void loadAll() {
        byId.clear();
        loadReport.clear();
        try {
            Files.createDirectories(projectsDir());
            Files.createDirectories(audioDir());
        } catch (IOException e) {
            loadReport.add("Cannot create directories: " + e.getMessage());
            return;
        }
        Map<String, ProjectMeta> metas = new LinkedHashMap<>();
        Path reg = root.resolve("registry.json");
        if (Files.exists(reg)) {
            try {
                JsonObject o = J.parseObject(Files.readString(reg, StandardCharsets.UTF_8));
                for (var e : J.arr(o, "projects")) if (e.isJsonObject()) {
                    ProjectMeta m = ProjectMeta.fromJson(e.getAsJsonObject());
                    metas.put(m.id, m);
                }
            } catch (Exception e) {
                loadReport.add("registry.json unreadable (" + e.getMessage() + "); ownership will be reset to unowned");
            }
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(projectsDir(), "*.csq.json")) {
            for (Path f : ds) {
                try {
                    ProjectCodec.ReadResult rr = ProjectCodec.read(Files.readString(f, StandardCharsets.UTF_8));
                    Project p = rr.project();
                    ProjectMeta m = metas.get(p.id);
                    if (m == null) {
                        m = new ProjectMeta();
                        m.id = p.id;
                        m.createdAt = System.currentTimeMillis();
                        m.trustLevel = 0;
                        loadReport.add("Project '" + p.name + "' had no registry entry; it is unowned and untrusted until an admin saves it");
                    }
                    m.name = p.name;
                    m.revision = p.revision;
                    m.pieceCount = p.pieceCount();
                    m.sizeBytes = Files.size(f);
                    byId.put(p.id, new Entry(p, m));
                    for (String w : rr.warnings()) loadReport.add(p.name + ": " + w);
                } catch (Exception e) {
                    loadReport.add("Skipped unreadable project " + f.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            loadReport.add("Cannot list projects: " + e.getMessage());
        }
    }

    @Override public Entry find(String idOrName) {
        if (idOrName == null) return null;
        Entry e = byId.get(idOrName);
        if (e != null) return e;
        for (Entry x : byId.values()) if (x.project().name.equalsIgnoreCase(idOrName)) return x;
        return null;
    }

    @Override public List<Entry> all() {
        List<Entry> l = new ArrayList<>(byId.values());
        l.sort(Comparator.comparing(x -> x.project().name.toLowerCase(Locale.ROOT)));
        return l;
    }

    public boolean nameTaken(String name, String exceptId) {
        for (Entry e : byId.values()) if (e.project().name.equalsIgnoreCase(name) && !e.project().id.equals(exceptId)) return true;
        return false;
    }

    /**
     * Stores a project (already validated and permission-checked). Assigns the next revision, stores
     * a parsed copy so later edits to {@code p} cannot affect running tasks, and writes asynchronously.
     */
    public Entry save(Project p, ProjectMeta meta) {
        Project copy = p.copy();
        Entry old = byId.get(copy.id);
        long rev = (old == null ? 0 : old.project().revision) + 1;
        copy.revision = rev;
        meta.revision = rev;
        meta.name = copy.name;
        meta.updatedAt = System.currentTimeMillis();
        if (meta.createdAt == 0) meta.createdAt = meta.updatedAt;
        meta.pieceCount = copy.pieceCount();
        String json = ProjectCodec.write(copy);
        meta.sizeBytes = json.getBytes(StandardCharsets.UTF_8).length;
        Entry e = new Entry(copy, meta);
        byId.put(copy.id, e);
        Path file = projectsDir().resolve(fileName(copy.id));
        io.execute(() -> writeAtomic(file, json));
        saveRegistry();
        return e;
    }

    public boolean delete(String id) {
        Entry e = byId.remove(id);
        if (e == null) return false;
        Path file = projectsDir().resolve(fileName(id));
        Path trash = root.resolve("trash");
        io.execute(() -> {
            // Soft delete: moved to trash/ so an admin can recover it.
            try {
                Files.createDirectories(trash);
                if (Files.exists(file)) Files.move(file, trash.resolve(fileName(id) + "." + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                // reported via logs by the platform
            }
        });
        saveRegistry();
        return true;
    }

    public void updateMeta(ProjectMeta m) {
        Entry e = byId.get(m.id);
        if (e == null) return;
        byId.put(m.id, new Entry(e.project(), m));
        saveRegistry();
    }

    private void saveRegistry() {
        JsonObject o = new JsonObject();
        o.addProperty("schemaVersion", 1);
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (Entry e : byId.values()) a.add(e.meta().toJson());
        o.add("projects", a);
        String json = J.PRETTY.toJson(o);
        Path reg = root.resolve("registry.json");
        io.execute(() -> writeAtomic(reg, json));
    }

    static String fileName(String id) { return Ids.slug(id) + ".csq.json"; }

    public static void writeAtomic(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static void writeAtomic(Path target, byte[] content) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, content);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}

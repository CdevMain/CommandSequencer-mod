package csequencer.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import csequencer.CommandSequencerMod;
import csequencer.core.engine.Engine;
import csequencer.core.json.J;
import csequencer.core.project.FileProjectStore;
import csequencer.core.security.ServerSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Server-side lifecycle of CommandSequencer: settings, project store, engine, persistence of
 * history and runtime state. Exists only while a server (integrated or dedicated) is running.
 *
 * <p>Storage layout:
 * <pre>
 *   config/commandsequencer-server.json        server settings (also migrates the 1.0 file)
 *   &lt;world&gt;/commandsequencer/projects/*.csq.json  project definitions
 *   &lt;world&gt;/commandsequencer/registry.json        ownership / sharing / trust
 *   &lt;world&gt;/commandsequencer/audio/&lt;sha1&gt;.*       audio files
 *   &lt;world&gt;/commandsequencer/history.json         execution history
 *   &lt;world&gt;/commandsequencer/runtime.json         variables, trigger state, recoverable tasks
 * </pre>
 */
public final class CSQServer {
    private static volatile CSQServer INSTANCE;

    public static CSQServer get() { return INSTANCE; }

    private final MinecraftServer server;
    private final ExecutorService io;
    private final ForgePlatform platform;
    private final FileProjectStore store;
    private final AudioStore audio;
    private final Path root;
    private ServerSettings settings;
    private final Engine engine;
    private boolean historyDirty;

    private CSQServer(MinecraftServer server) {
        this.server = server;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CommandSequencer-IO");
            t.setDaemon(true);
            return t;
        });
        this.root = server.getWorldPath(LevelResource.ROOT).resolve("commandsequencer");
        this.settings = loadSettings();
        this.platform = new ForgePlatform(server);
        this.store = new FileProjectStore(root, io);
        this.store.loadAll();
        for (String line : store.loadReport()) CommandSequencerMod.LOGGER.warn("[CSQ] {}", line);
        this.audio = new AudioStore(store.audioDir());
        this.engine = new Engine(platform, settings, store);
        Path hist = root.resolve("history.json");
        if (Files.exists(hist)) {
            try {
                JsonObject o = J.parseObject(Files.readString(hist, StandardCharsets.UTF_8));
                engine.history.load(J.arr(o, "records"));
            } catch (Exception e) {
                CommandSequencerMod.LOGGER.warn("[CSQ] history.json unreadable: {}", e.getMessage());
            }
        }
        engine.history.onChange = h -> historyDirty = true;
    }

    public static void start(MinecraftServer server) {
        CSQServer s = new CSQServer(server);
        INSTANCE = s;
        Path rt = s.root.resolve("runtime.json");
        if (Files.exists(rt)) {
            try {
                List<String> report = s.engine.importRuntime(J.parseObject(Files.readString(rt, StandardCharsets.UTF_8)));
                for (String line : report) CommandSequencerMod.LOGGER.info("[CSQ] Recovery: {}", line);
            } catch (Exception e) {
                CommandSequencerMod.LOGGER.error("[CSQ] runtime.json unreadable; starting clean", e);
            }
        }
        if (s.settings.installExamples) s.installExamples(false);
        s.engine.serverStarted();
        CommandSequencerMod.LOGGER.info("[CSQ] Engine ready: {} project(s), platform {} {}", s.store.all().size(),
                s.platform.info().loader(), s.platform.info().minecraftVersion());
    }

    public static void stop() {
        CSQServer s = INSTANCE;
        if (s == null) return;
        INSTANCE = null;
        try {
            JsonObject runtime = s.engine.shutdown();
            FileProjectStore.writeAtomic(s.root.resolve("runtime.json"), J.PRETTY.toJson(runtime));
            s.writeHistory();
        } catch (Exception e) {
            CommandSequencerMod.LOGGER.error("[CSQ] Failed to persist state on shutdown", e);
        }
        ServerNet.reset();
        s.io.shutdown();
        try {
            if (!s.io.awaitTermination(10, TimeUnit.SECONDS)) CommandSequencerMod.LOGGER.warn("[CSQ] IO thread did not finish in time");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    void tick() {
        engine.tick();
        ServerNet.tick(this);
        long t = engine.serverTick();
        if (historyDirty && t % 100 == 0) {
            historyDirty = false;
            String json = historyJson();
            io.execute(() -> FileProjectStore.writeAtomic(root.resolve("history.json"), json));
        }
        if (t % 6000 == 0) {
            String json = J.PRETTY.toJson(engine.exportRuntime());
            io.execute(() -> FileProjectStore.writeAtomic(root.resolve("runtime.json"), json));
        }
    }

    private String historyJson() {
        JsonObject o = new JsonObject();
        o.addProperty("schemaVersion", 1);
        JsonArray a = engine.history.toJson();
        o.add("records", a);
        return J.PRETTY.toJson(o);
    }

    private void writeHistory() { FileProjectStore.writeAtomic(root.resolve("history.json"), historyJson()); }

    // ── Built-in examples ──────────────────────────────────────────────────

    /**
     * Installs the built-in tutorials/examples (public, owned by nobody, editable by admins; others
     * can duplicate them). Each example is installed once per world: deleted examples do not come back
     * unless {@code force} is true (the /csq examples command), and edited ones are never overwritten.
     */
    public int installExamples(boolean force) {
        Path file = root.resolve("examples.json");
        JsonObject installed = new JsonObject();
        try {
            if (Files.exists(file)) installed = J.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            CommandSequencerMod.LOGGER.warn("[CSQ] examples.json unreadable: {}", e.getMessage());
        }
        int n = 0;
        for (var p : csequencer.core.examples.Examples.all()) {
            int have = J.i(installed, p.id, 0);
            boolean present = store.find(p.id) != null;
            if (present || (!force && have >= csequencer.core.examples.Examples.VERSION)) continue;
            if (store.nameTaken(p.name, p.id)) continue;
            csequencer.core.project.ProjectMeta m = new csequencer.core.project.ProjectMeta();
            m.id = p.id;
            m.ownerName = "CommandSequencer";
            m.visibility = csequencer.core.project.ProjectMeta.Visibility.PUBLIC;
            m.trustLevel = 4;
            m.triggersEnabled = true;
            m.lastSavedBy = "built-in example";
            store.save(p, m);
            installed.addProperty(p.id, csequencer.core.examples.Examples.VERSION);
            n++;
        }
        if (n > 0) {
            engine.setCatalog(store);
            String json = J.PRETTY.toJson(installed);
            io.execute(() -> FileProjectStore.writeAtomic(file, json));
            CommandSequencerMod.LOGGER.info("[CSQ] Installed {} built-in example(s)", n);
        }
        return n;
    }

    // ── Settings ───────────────────────────────────────────────────────────

    static Path settingsPath() { return FMLPaths.CONFIGDIR.get().resolve("commandsequencer-server.json"); }

    private static ServerSettings loadSettings() {
        Path p = settingsPath();
        ServerSettings s = new ServerSettings();
        if (Files.exists(p)) {
            try {
                s = ServerSettings.fromJson(J.parseObject(Files.readString(p, StandardCharsets.UTF_8)));
            } catch (Exception e) {
                CommandSequencerMod.LOGGER.warn("[CSQ] Server settings unreadable, using defaults: {}", e.getMessage());
            }
        }
        try {
            FileProjectStore.writeAtomic(p, J.PRETTY.toJson(s.toJson()));
        } catch (RuntimeException e) {
            CommandSequencerMod.LOGGER.warn("[CSQ] Could not write server settings: {}", e.getMessage());
        }
        return s;
    }

    public void applySettings(ServerSettings s) {
        this.settings = s;
        engine.applySettings(s);
        String json = J.PRETTY.toJson(s.toJson());
        io.execute(() -> FileProjectStore.writeAtomic(settingsPath(), json));
    }

    public void reloadSettings() { applySettings(loadSettings()); }

    // ── Accessors ──────────────────────────────────────────────────────────

    public MinecraftServer server() { return server; }
    public ForgePlatform platform() { return platform; }
    public FileProjectStore store() { return store; }
    public AudioStore audio() { return audio; }
    public Engine engine() { return engine; }
    public ServerSettings settings() { return settings; }
    public ExecutorService io() { return io; }
}

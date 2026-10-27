package csequencer.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import csequencer.core.engine.ProjectCatalog;
import csequencer.core.engine.StartRequest;
import csequencer.core.engine.StartResult;
import csequencer.core.engine.Task;
import csequencer.core.json.ProjectCodec;
import csequencer.core.project.ProjectMeta;
import csequencer.core.security.Permission;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Commands. {@code /csq} is the main tree; {@code /csq_run}, {@code /csq_stop} and {@code /csq_list}
 * from CommandSequencer 1.0 are kept as aliases.
 */
public final class CSQCommands {
    private CSQCommands() {}

    private static final SuggestionProvider<CommandSourceStack> PROJECTS = (ctx, b) -> {
        CSQServer s = CSQServer.get();
        List<String> names = new ArrayList<>();
        if (s != null) for (ProjectCatalog.Entry e : s.store().all()) names.add(e.project().name);
        return SharedSuggestionProvider.suggest(names.stream().map(n -> n.contains(" ") ? "\"" + n + "\"" : n), b);
    };

    private static final SuggestionProvider<CommandSourceStack> TASKS = (ctx, b) -> {
        CSQServer s = CSQServer.get();
        List<String> ids = new ArrayList<>();
        if (s != null) for (Task t : s.engine().tasks()) if (!t.state().terminal()) ids.add(t.id());
        return SharedSuggestionProvider.suggest(ids, b);
    };

    private static boolean has(CommandSourceStack src, Permission p) {
        CSQServer s = CSQServer.get();
        if (s == null) return false;
        if (src.getEntity() instanceof ServerPlayer pl) return ForgePermissions.check(pl, p);
        return src.hasPermission(s.settings().level(p));
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("csq")
                .then(Commands.literal("run").requires(s -> has(s, Permission.RUN))
                        .then(Commands.argument("name", StringArgumentType.string()).suggests(PROJECTS)
                                .executes(c -> run(c, 0))
                                .then(Commands.argument("fromTick", IntegerArgumentType.integer(0)).executes(c -> run(c, IntegerArgumentType.getInteger(c, "fromTick"))))))
                .then(Commands.literal("stop").requires(s -> has(s, Permission.CONTROL_OWN))
                        .executes(c -> stopAll(c))
                        .then(Commands.argument("task", StringArgumentType.word()).suggests(TASKS).executes(c -> control(c, "cancel"))))
                .then(Commands.literal("pause").requires(s -> has(s, Permission.CONTROL_OWN))
                        .then(Commands.argument("task", StringArgumentType.word()).suggests(TASKS).executes(c -> control(c, "pause"))))
                .then(Commands.literal("resume").requires(s -> has(s, Permission.CONTROL_OWN))
                        .then(Commands.argument("task", StringArgumentType.word()).suggests(TASKS).executes(c -> control(c, "resume"))))
                .then(Commands.literal("list").requires(s -> has(s, Permission.MONITOR)).executes(CSQCommands::list))
                .then(Commands.literal("tasks").requires(s -> has(s, Permission.MONITOR)).executes(CSQCommands::tasks))
                .then(Commands.literal("reload").requires(s -> has(s, Permission.ADMIN)).executes(CSQCommands::reload))
                .then(Commands.literal("import").requires(s -> has(s, Permission.ADMIN)).executes(CSQCommands::importLegacy))
                .then(Commands.literal("examples").requires(s -> has(s, Permission.ADMIN)).executes(c -> {
                    int n = CSQServer.get().installExamples(true);
                    c.getSource().sendSuccess(() -> Component.literal("[CSQ] " + (n == 0 ? "All examples are already installed" : "Installed " + n + " example(s)")), true);
                    return n;
                })));
        // CommandSequencer 1.0 aliases
        d.register(Commands.literal("csq_run").requires(s -> has(s, Permission.RUN))
                .then(Commands.argument("name", StringArgumentType.string()).suggests(PROJECTS).executes(c -> run(c, 0))));
        d.register(Commands.literal("csq_stop").requires(s -> has(s, Permission.CONTROL_OWN)).executes(CSQCommands::stopAll));
        d.register(Commands.literal("csq_list").requires(s -> has(s, Permission.MONITOR)).executes(CSQCommands::list));
    }

    private static int run(CommandContext<CommandSourceStack> c, int from) {
        CSQServer s = CSQServer.get();
        String name = StringArgumentType.getString(c, "name");
        ProjectCatalog.Entry e = s.store().find(name);
        if (e == null) { c.getSource().sendFailure(Component.literal("[CSQ] Sequence '" + name + "' not found")); return 0; }
        StartRequest r = new StartRequest(e.project(), e.meta()).fromTick(from).reason("command");
        if (c.getSource().getEntity() instanceof ServerPlayer p) r.initiator(ForgePlatform.ref(p)).dimension(ForgePlatform.dim(p.level()));
        else r.dimension(ForgePlatform.dim(c.getSource().getLevel()));
        StartResult res = s.engine().start(r);
        if (!res.ok()) { c.getSource().sendFailure(Component.literal("[CSQ] " + res.message())); return 0; }
        c.getSource().sendSuccess(() -> Component.literal("[CSQ] " + res.message()), true);
        return 1;
    }

    private static int stopAll(CommandContext<CommandSourceStack> c) {
        CSQServer s = CSQServer.get();
        int n = 0;
        boolean all = has(c.getSource(), Permission.CONTROL_ALL);
        ServerPlayer p = c.getSource().getEntity() instanceof ServerPlayer x ? x : null;
        for (Task t : s.engine().tasks()) {
            if (t.state().terminal()) continue;
            boolean mine = p != null && t.initiator() != null && t.initiator().uuid().equals(p.getUUID());
            if ((all || mine) && s.engine().cancel(t, "stopped by command")) n++;
        }
        int fn = n;
        c.getSource().sendSuccess(() -> Component.literal("[CSQ] Stopped " + fn + " task(s)"), true);
        return n;
    }

    private static int control(CommandContext<CommandSourceStack> c, String action) {
        CSQServer s = CSQServer.get();
        Task t = s.engine().task(StringArgumentType.getString(c, "task"));
        if (t == null) { c.getSource().sendFailure(Component.literal("[CSQ] No such task")); return 0; }
        boolean allowed = has(c.getSource(), Permission.CONTROL_ALL) || (c.getSource().getEntity() instanceof ServerPlayer p
                && t.initiator() != null && t.initiator().uuid().equals(p.getUUID()));
        if (!allowed) { c.getSource().sendFailure(Component.literal("[CSQ] You may not control this task")); return 0; }
        boolean ok = switch (action) {
            case "pause" -> s.engine().pause(t, Task.PauseReason.USER);
            case "resume" -> s.engine().resume(t);
            default -> s.engine().cancel(t, "cancelled by command");
        };
        c.getSource().sendSuccess(() -> Component.literal("[CSQ] " + action + " " + t.id() + (ok ? "" : " (no change)")), true);
        return ok ? 1 : 0;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        CSQServer s = CSQServer.get();
        List<String> names = new ArrayList<>();
        for (ProjectCatalog.Entry e : s.store().all()) names.add(e.project().name);
        c.getSource().sendSuccess(() -> Component.literal(names.isEmpty() ? "[CSQ] No sequences saved." : "[CSQ] Sequences: " + String.join(", ", names)), false);
        return names.size();
    }

    private static int tasks(CommandContext<CommandSourceStack> c) {
        CSQServer s = CSQServer.get();
        int n = 0;
        for (Task t : s.engine().tasks()) {
            if (c.getSource().getEntity() instanceof ServerPlayer p && !s.engine().sync.canView(t, p.getUUID())) continue;
            String line = String.format("[CSQ] %s %s %s %d/%d", t.id(), t.project().name, t.state(), t.mainPosition(), t.totalLength());
            c.getSource().sendSuccess(() -> Component.literal(line), false);
            n++;
        }
        if (n == 0) c.getSource().sendSuccess(() -> Component.literal("[CSQ] No active tasks"), false);
        return n;
    }

    private static int reload(CommandContext<CommandSourceStack> c) {
        CSQServer s = CSQServer.get();
        s.reloadSettings();
        c.getSource().sendSuccess(() -> Component.literal("[CSQ] Server settings reloaded"), true);
        return 1;
    }

    /** Imports CommandSequencer 1.0 files from {@code .minecraft/commandsequencer/csq} (server side). */
    private static int importLegacy(CommandContext<CommandSourceStack> c) {
        CSQServer s = CSQServer.get();
        Path dir = FMLPaths.GAMEDIR.get().resolve("commandsequencer").resolve("csq");
        int n = 0;
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
                for (Path f : ds) {
                    try {
                        var rr = ProjectCodec.read(Files.readString(f, StandardCharsets.UTF_8));
                        if (s.store().nameTaken(rr.project().name, rr.project().id)) continue;
                        ProjectMeta m = new ProjectMeta();
                        m.id = rr.project().id;
                        if (c.getSource().getEntity() instanceof ServerPlayer p) {
                            m.ownerUuid = p.getUUID().toString();
                            m.ownerName = p.getGameProfile().getName();
                        }
                        m.trustLevel = 4;
                        s.store().save(rr.project(), m);
                        n++;
                    } catch (Exception ex) {
                        c.getSource().sendFailure(Component.literal("[CSQ] Skipped " + f.getFileName() + ": " + ex.getMessage()));
                    }
                }
            } catch (Exception ex) {
                c.getSource().sendFailure(Component.literal("[CSQ] " + ex.getMessage()));
            }
        }
        s.engine().setCatalog(s.store());
        int fn = n;
        c.getSource().sendSuccess(() -> Component.literal("[CSQ] Imported " + fn + " legacy sequence(s) from " + dir), true);
        return n;
    }
}

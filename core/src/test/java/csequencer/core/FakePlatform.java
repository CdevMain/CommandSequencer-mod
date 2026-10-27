package csequencer.core;

import com.google.gson.JsonObject;
import csequencer.core.platform.*;
import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;
import csequencer.core.value.Vec3d;

import java.util.*;

/** Headless platform used by the core tests. Records actions and client messages. */
public final class FakePlatform implements PlatformAdapter {
    public static final class Player {
        public final EntityRef ref;
        public String dim = "minecraft:overworld";
        public double health = 20, maxHealth = 20;
        public Vec3d pos = new Vec3d(0, 64, 0);
        public int op;
        public boolean sneaking;
        public final List<String> teams = new ArrayList<>();
        public final Map<String, Integer> items = new HashMap<>();

        Player(String name) { ref = EntityRef.player(UUID.nameUUIDFromBytes(name.getBytes()), name); }
    }

    public record Sent(UUID player, ClientChannel.Topic topic, JsonObject payload) {}

    public long now = 1_000_000;
    public final Map<UUID, Player> players = new LinkedHashMap<>();
    public final List<String> actions = new ArrayList<>();
    public final List<String> ended = new ArrayList<>();
    public final List<String> bossbar = new ArrayList<>();
    public final List<Sent> sent = new ArrayList<>();
    public final Set<Capability> caps = EnumSet.allOf(Capability.class);
    public Engine engineRef;
    /** Fake non-player entities: uuid -> {health, maxHealth, x, y, z}. */
    public final Map<UUID, double[]> entities = new LinkedHashMap<>();

    public interface Engine { long tick(); }

    public Player join(String name) {
        Player p = new Player(name);
        players.put(p.ref.uuid(), p);
        return p;
    }

    public void leave(Player p) { players.remove(p.ref.uuid()); }

    public List<Sent> sentTo(Player p, ClientChannel.Topic t) {
        List<Sent> out = new ArrayList<>();
        for (Sent s : sent) if (s.player.equals(p.ref.uuid()) && s.topic == t) out.add(s);
        return out;
    }

    @Override public PlatformInfo info() { return new PlatformInfo("fake", "1", "test", caps); }

    @Override public long millis() { return now; }

    @Override public CoreLogger logger() { return CoreLogger.SILENT; }

    @Override public PlayerDirectory players() {
        return new PlayerDirectory() {
            @Override public List<EntityRef> online() { List<EntityRef> l = new ArrayList<>(); for (Player p : players.values()) l.add(p.ref); return l; }
            @Override public EntityRef byName(String name) { for (Player p : players.values()) if (p.ref.name().equalsIgnoreCase(name)) return p.ref; return null; }
            @Override public EntityRef byUuid(UUID uuid) { Player p = players.get(uuid); return p == null ? null : p.ref; }
            @Override public String dimensionOf(UUID uuid) { Player p = players.get(uuid); return p == null ? null : p.dim; }
            @Override public csequencer.core.value.Vec3d positionOf(UUID uuid) { Player p = players.get(uuid); return p == null ? null : p.pos; }
            @Override public List<EntityRef> inTeam(String team) { List<EntityRef> l = new ArrayList<>(); for (Player p : players.values()) if (p.teams.contains(team)) l.add(p.ref); return l; }
            @Override public List<EntityRef> select(String selector, EntityRef origin, boolean playersOnly) { return online(); }
        };
    }

    @Override public WorldQueryAdapter queries() {
        return (id, args, ctx) -> {
            Player p = ctx.subject() == null ? null : players.get(ctx.subject().uuid());
            if (id.startsWith("entity.")) {
                Value ev = args.getOrDefault("entity", Value.NULL);
                double[] e = ev.asEntity() == null ? null : entities.get(ev.asEntity().uuid());
                return switch (id) {
                    case "entity.alive" -> Value.of(e != null && e[0] > 0);
                    case "entity.health" -> e == null ? Value.NULL : Value.of(e[0]);
                    case "entity.max_health" -> e == null ? Value.NULL : Value.of(e[1]);
                    case "entity.health_pct" -> e == null ? Value.NULL : Value.of(e[0] / e[1] * 100);
                    case "entity.pos" -> e == null ? Value.NULL : Value.of(new Vec3d(e[2], e[3], e[4]));
                    default -> Value.NULL;
                };
            }
            switch (id) {
                case "players.online": { List<Value> l = new ArrayList<>(); for (Player x : players.values()) l.add(Value.of(x.ref)); return Value.list(l); }
                case "players.count": return Value.of(players.size());
                default: break;
            }
            if (p == null) return Value.NULL;
            return switch (id) {
                case "player.name" -> Value.of(p.ref.name());
                case "player.health" -> Value.of(p.health);
                case "player.max_health" -> Value.of(p.maxHealth);
                case "player.health_pct" -> Value.of(p.health / p.maxHealth * 100);
                case "player.pos" -> Value.of(p.pos);
                case "player.x" -> Value.of(p.pos.x());
                case "player.y" -> Value.of(p.pos.y());
                case "player.z" -> Value.of(p.pos.z());
                case "player.dimension" -> Value.of(p.dim);
                case "player.online" -> Value.TRUE;
                case "player.state" -> Value.of("sneaking".equals(args.get("state").asText()) && p.sneaking);
                case "player.team" -> Value.of(p.teams.isEmpty() ? "" : p.teams.get(0));
                case "player.item_count" -> Value.of(p.items.getOrDefault(args.get("item").asText(), 0));
                case "player.has_item" -> Value.of(p.items.getOrDefault(args.get("item").asText(), 0) >= args.get("count").asNumber());
                case "player.in_region" -> {
                    Vec3d a = args.get("from").asVec(), b = args.get("to").asVec();
                    if (a == null || b == null) yield Value.FALSE;
                    Vec3d v = p.pos;
                    yield Value.of(v.x() >= Math.min(a.x(), b.x()) && v.x() <= Math.max(a.x(), b.x())
                            && v.y() >= Math.min(a.y(), b.y()) && v.y() <= Math.max(a.y(), b.y())
                            && v.z() >= Math.min(a.z(), b.z()) && v.z() <= Math.max(a.z(), b.z()));
                }
                case "player.in_radius" -> {
                    Vec3d c = args.get("center").asVec();
                    yield Value.of(c != null && p.pos.distanceTo(c) <= args.get("radius").asNumber());
                }
                case "player.distance_to" -> Value.of(p.pos.distanceTo(args.get("pos").asVec() == null ? Vec3d.ZERO : args.get("pos").asVec()));
                default -> Value.NULL;
            };
        };
    }

    @Override public ActionAdapter actions() {
        return new ActionAdapter() {
            @Override public ActionResult execute(String type, Map<String, Value> args, ActionContext ctx) {
                String detail = switch (type) {
                    case "action.command" -> args.get("command").asText();
                    case "action.chat", "action.actionbar" -> args.get("message").asText();
                    case "action.title" -> args.get("title").asText();
                    case "action.set_block" -> args.get("pos").asText() + "=" + args.get("block").asText();
                    case "action.spawn_entity" -> args.get("entity").asText();
                    default -> "";
                };
                if (detail.contains("THROW")) throw new IllegalStateException("adapter exploded");
                StringBuilder targets = new StringBuilder();
                for (EntityRef e : ctx.targets()) targets.append(targets.length() == 0 ? "" : ",").append(e.name());
                actions.add((engineRef == null ? "" : engineRef.tick() + ":") + type + ":" + detail + (targets.length() > 0 ? "@" + targets : ""));
                if (detail.contains("FAIL")) return ActionResult.fail("requested failure");
                if (type.equals("action.spawn_entity")) {
                    EntityRef boss = new EntityRef(UUID.randomUUID(), "zombie", "minecraft:zombie", false);
                    Vec3d at = args.get("pos").asVec() == null ? new Vec3d(1, 64, 1) : args.get("pos").asVec();
                    entities.put(boss.uuid(), new double[]{20, 20, at.x(), at.y(), at.z()});
                    return ActionResult.ok("spawned", Value.of(boss));
                }
                if (type.equals("hud.bossbar")) bossbar.add(args.get("op").asText() + ":" + args.getOrDefault("key", Value.NULL).asText()
                        + (args.containsKey("progress") ? ":" + Math.round(args.get("progress").asNumber() * 100) : ""));
                return ActionResult.ok("ok");
            }

            @Override public void onTaskEnded(String taskId, boolean removeSpawned) { ended.add(taskId + (removeSpawned ? ":remove" : "")); }
        };
    }

    @Override public PermissionAdapter permissions() {
        return new PermissionAdapter() {
            @Override public int opLevel(UUID player) { Player p = players.get(player); return p == null ? 0 : p.op; }
            @Override public List<String> teams(UUID player) { Player p = players.get(player); return p == null ? List.of() : p.teams; }
        };
    }

    @Override public ClientChannel clients() { return (player, topic, payload) -> sent.add(new Sent(player, topic, payload)); }
}

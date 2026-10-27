package csequencer.core.engine;

import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;
import csequencer.core.value.Vec3d;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An event delivered to the engine. {@code id} is unique per server run; the engine drops
 * events whose id it has already processed, so a platform that fires an event twice (or a
 * client reconnect) cannot cause duplicate executions.
 */
public final class GameEvent {
    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    public final long id;
    public final String type;
    public final EntityRef player;
    public final EntityRef entity;
    public final String dimension;
    public final Vec3d pos;
    public final Map<String, Value> data;

    public GameEvent(long id, String type, EntityRef player, EntityRef entity, String dimension, Vec3d pos, Map<String, Value> data) {
        this.id = id;
        this.type = type;
        this.player = player;
        this.entity = entity;
        this.dimension = dimension == null ? "" : dimension;
        this.pos = pos;
        this.data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    public static GameEvent of(String type, EntityRef player, String dimension, Vec3d pos, Map<String, Value> data) {
        return new GameEvent(NEXT_ID.getAndIncrement(), type, player, null, dimension, pos, data);
    }

    public static GameEvent ofEntity(String type, EntityRef player, EntityRef entity, String dimension, Vec3d pos, Map<String, Value> data) {
        return new GameEvent(NEXT_ID.getAndIncrement(), type, player, entity, dimension, pos, data);
    }

    public static long nextId() { return NEXT_ID.getAndIncrement(); }

    /** Subject of the event: the player if any, otherwise the entity. */
    public EntityRef subject() { return player != null ? player : entity; }

    public Value field(String name) {
        return switch (name) {
            case "player" -> player == null ? Value.NULL : Value.of(player);
            case "entity" -> entity == null ? Value.NULL : Value.of(entity);
            case "dimension" -> Value.of(dimension);
            case "pos" -> pos == null ? Value.NULL : Value.of(pos);
            case "x" -> pos == null ? Value.NULL : Value.of(pos.x());
            case "y" -> pos == null ? Value.NULL : Value.of(pos.y());
            case "z" -> pos == null ? Value.NULL : Value.of(pos.z());
            case "type" -> Value.of(type);
            default -> data.getOrDefault(name, Value.NULL);
        };
    }
}

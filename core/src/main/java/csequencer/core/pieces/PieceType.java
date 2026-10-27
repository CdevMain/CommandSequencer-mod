package csequencer.core.pieces;

import csequencer.core.platform.Capability;

import java.util.List;

/**
 * Definition of a piece type. {@code id} is the stable serialized identifier.
 * {@code dispatched} pieces are executed by the platform's ActionAdapter; the others are
 * interpreted by the core engine.
 */
public record PieceType(
        String id,
        String name,
        String category,
        PieceKind kind,
        int color,
        String description,
        List<ParamSpec> params,
        Capability capability,
        boolean dispatched,
        boolean privileged,
        int defaultDuration) {

    public ParamSpec param(String key) {
        for (ParamSpec p : params) if (p.key.equals(key)) return p;
        return null;
    }

    /** True when the piece has a condition the logic graph editor can open. */
    public boolean hasConditionParam() {
        for (ParamSpec p : params) if (p.type == ParamType.CONDITION || p.type == ParamType.BRANCHES) return true;
        return false;
    }
}

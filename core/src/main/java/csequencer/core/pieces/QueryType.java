package csequencer.core.pieces;

import csequencer.core.platform.Capability;
import csequencer.core.value.ValueType;

import java.util.List;

/**
 * A value source usable on either side of a comparison or as a piece argument.
 * Core queries (capability == null) are evaluated by the engine; the rest by the platform's
 * WorldQueryAdapter. {@code subjectBound} queries act on the current subject (the context
 * player/entity) unless a "who" argument names another one.
 */
public record QueryType(String id, String name, String category, ValueType result, List<ParamSpec> params,
                        Capability capability, boolean subjectBound, String description) {
    public ParamSpec param(String key) {
        for (ParamSpec p : params) if (p.key.equals(key)) return p;
        return null;
    }
}

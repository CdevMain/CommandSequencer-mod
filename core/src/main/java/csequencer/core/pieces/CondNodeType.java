package csequencer.core.pieces;

import java.util.List;

/**
 * Logic graph node type. {@code stateful} nodes (transition detectors) keep per-subject state
 * between evaluations, which is what makes "just entered" differ from "is inside".
 */
public record CondNodeType(String id, String name, String category, int minChildren, int maxChildren,
                           List<ParamSpec> params, boolean stateful, String description) {
    public static final int UNBOUNDED = Integer.MAX_VALUE;

    public ParamSpec param(String key) {
        for (ParamSpec p : params) if (p.key.equals(key)) return p;
        return null;
    }
}

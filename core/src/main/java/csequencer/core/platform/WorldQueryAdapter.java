package csequencer.core.platform;

import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;

import java.util.Map;

/**
 * Evaluates platform queries (player/world/entity state). Arguments arrive fully resolved
 * (nested queries evaluated, templates rendered). Must be side-effect free and fast; must
 * return {@link Value#NULL} rather than throw for missing players/entities.
 */
public interface WorldQueryAdapter {
    Value query(String queryId, Map<String, Value> args, QueryContext ctx);

    /** Context of an evaluation: current subject (may be null) and default dimension. */
    record QueryContext(EntityRef subject, EntityRef initiator, String dimension) {}
}

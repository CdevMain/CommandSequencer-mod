package csequencer.core.pieces;

/** Parameter editor/validation types. Ids are stable. */
public enum ParamType {
    INT, NUMBER, TEXT, LONG_TEXT, BOOL, VEC3, ENUM,
    /** Inline {@link csequencer.core.model.ConditionGraph}. */
    CONDITION,
    /** Value expression: {"q": queryId, "args": {...}} or a literal. */
    QUERY,
    /** Reference to a function/subsequence (timeline id). */
    FUNCTION,
    /** List of function ids. */
    FUNCTIONS,
    /** Reference to another project by name. */
    PROJECT,
    AUDIO,
    /** Target selection object: {"mode": ..., ...}. */
    TARGET,
    /** IF / ELSE IF list: [{"condition": graph, "target": fnId}]. */
    BRANCHES,
    /** SWITCH cases: [{"match": text, "target": fnId}]. */
    CASES,
    /** Weighted outcomes: [{"weight": n, "target": fnId}]. */
    OUTCOMES,
    ITEM, BLOCK, ENTITY_TYPE, PARTICLE, SOUND, EFFECT, DIMENSION, EVENT_TYPE,
    VARIABLE, VAR_SCOPE,
    /** Player name; the editor offers the list of online players. Supports {placeholders}. */
    PLAYER,
    /** Ticks; editors accept "mm:ss.fff", "5s" or ticks. */
    DURATION,
    /** Free-form JSON object (function arguments, NBT-like data). */
    JSON
}

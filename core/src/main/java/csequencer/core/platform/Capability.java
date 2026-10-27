package csequencer.core.platform;

/**
 * Features a platform adapter may or may not provide. Pieces declare the capability they need;
 * the validator reports pieces that the running platform cannot execute instead of failing silently.
 */
public enum Capability {
    COMMANDS, CHAT, TITLES, TELEPORT, ENTITIES, PARTICLES, SOUNDS, AUDIO_STREAM, BLOCKS, STRUCTURES,
    SCOREBOARD, ITEMS, EFFECTS, PLAYER_STATE, TEAMS, PERMISSION_NODES, WORLD_QUERIES, PLAYER_QUERIES, ENTITY_QUERIES,
    /** Client screen elements (popups, overlays, markers, screen effects) and server boss bars. */
    HUD
}

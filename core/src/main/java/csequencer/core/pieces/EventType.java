package csequencer.core.pieces;

import java.util.List;

/** Game or internal event that listeners and triggers can react to. Fields are readable via "event.field". */
public record EventType(String id, String name, String description, List<String> fields, boolean internal) {}

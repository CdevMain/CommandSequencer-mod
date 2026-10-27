package csequencer.core.pieces;

/**
 * Display category of the piece library. Only affects presentation (palette, colours, search);
 * it is never serialized, so categories can be reorganised without breaking projects.
 */
public record PieceCategory(String id, String name, String icon, int color, String description) {}

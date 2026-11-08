package csequencer.core;

import csequencer.core.pieces.PieceHelp;
import csequencer.core.pieces.PieceRegistry;
import csequencer.core.pieces.PieceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Every piece explains how to use it, with an example. */
class PieceHelpTest {
    @Test void everyPieceHasHelp() {
        for (PieceType t : PieceRegistry.pieces()) {
            PieceHelp.Help h = PieceHelp.of(t.id());
            assertNotNull(h, "no help for " + t.id());
            assertFalse(h.howTo().isBlank() || h.example().isBlank(), t.id());
        }
    }
}

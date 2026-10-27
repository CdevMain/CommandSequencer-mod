package csequencer.core.value;

import java.util.Locale;

/** Comparison operators. Serialized ids are stable and must never change. */
public enum CompareOp {
    EQ("eq", "=", 1), NEQ("neq", "≠", 1),
    GT("gt", ">", 1), GTE("gte", "≥", 1), LT("lt", "<", 1), LTE("lte", "≤", 1),
    CONTAINS("contains", "contains", 1), NOT_CONTAINS("not_contains", "doesn't contain", 1),
    STARTS_WITH("starts_with", "starts with", 1), ENDS_WITH("ends_with", "ends with", 1),
    EXISTS("exists", "exists", 0), NOT_EXISTS("not_exists", "doesn't exist", 0),
    IN_RANGE("in_range", "in range", 2), OUT_OF_RANGE("out_of_range", "outside range", 2),
    IS_TRUE("is_true", "is true", 0), IS_FALSE("is_false", "is false", 0);

    public final String id;
    public final String symbol;
    /** Number of right-hand operands: 0 (unary), 1, or 2 (min/max). */
    public final int operands;

    CompareOp(String id, String symbol, int operands) {
        this.id = id;
        this.symbol = symbol;
        this.operands = operands;
    }

    public static CompareOp byId(String id) {
        if (id == null) return null;
        String k = id.trim().toLowerCase(Locale.ROOT);
        for (CompareOp op : values()) if (op.id.equals(k) || op.symbol.equals(k)) return op;
        return switch (k) {
            case "==", "equals" -> EQ;
            case "!=", "not_equals" -> NEQ;
            case ">=" -> GTE;
            case "<=" -> LTE;
            case "range", "between" -> IN_RANGE;
            default -> null;
        };
    }

    /** Applies the operator. {@code b} and {@code c} may be null for unary operators. */
    public boolean test(Value a, Value b, Value c) {
        if (a == null) a = Value.NULL;
        if (b == null) b = Value.NULL;
        if (c == null) c = Value.NULL;
        return switch (this) {
            case EQ -> a.looseEquals(b);
            case NEQ -> !a.looseEquals(b);
            case GT -> a.isNumeric() && b.isNumeric() ? a.asNumber() > b.asNumber() : a.asText().compareToIgnoreCase(b.asText()) > 0;
            case GTE -> a.isNumeric() && b.isNumeric() ? a.asNumber() >= b.asNumber() : a.asText().compareToIgnoreCase(b.asText()) >= 0;
            case LT -> a.isNumeric() && b.isNumeric() ? a.asNumber() < b.asNumber() : a.asText().compareToIgnoreCase(b.asText()) < 0;
            case LTE -> a.isNumeric() && b.isNumeric() ? a.asNumber() <= b.asNumber() : a.asText().compareToIgnoreCase(b.asText()) <= 0;
            case CONTAINS -> a.contains(b);
            case NOT_CONTAINS -> !a.contains(b);
            case STARTS_WITH -> a.asText().toLowerCase(Locale.ROOT).startsWith(b.asText().toLowerCase(Locale.ROOT));
            case ENDS_WITH -> a.asText().toLowerCase(Locale.ROOT).endsWith(b.asText().toLowerCase(Locale.ROOT));
            case EXISTS -> a.exists();
            case NOT_EXISTS -> !a.exists();
            case IN_RANGE -> a.isNumeric() && a.asNumber() >= Math.min(b.asNumber(), c.asNumber()) && a.asNumber() <= Math.max(b.asNumber(), c.asNumber());
            case OUT_OF_RANGE -> !a.isNumeric() || a.asNumber() < Math.min(b.asNumber(), c.asNumber()) || a.asNumber() > Math.max(b.asNumber(), c.asNumber());
            case IS_TRUE -> a.asBool();
            case IS_FALSE -> !a.asBool();
        };
    }
}

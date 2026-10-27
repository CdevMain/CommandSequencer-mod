package csequencer.core.value;

/** Runtime value types understood by the logic evaluator. Serialized names are stable. */
public enum ValueType {
    NULL, NUMBER, TEXT, BOOL, VEC3, ENTITY, LIST;

    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }

    public static ValueType byId(String id) {
        if (id == null) return NULL;
        for (ValueType t : values()) if (t.id().equalsIgnoreCase(id)) return t;
        return NULL;
    }
}

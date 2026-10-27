package csequencer.core.value;

/** Immutable coordinate. Platform independent (no Minecraft Vec3). */
public record Vec3d(double x, double y, double z) {
    public static final Vec3d ZERO = new Vec3d(0, 0, 0);

    public double distanceTo(Vec3d o) { return Math.sqrt(distanceSq(o)); }

    public double distanceSq(Vec3d o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public Vec3d add(Vec3d o) { return new Vec3d(x + o.x, y + o.y, z + o.z); }

    /** Parses "x y z" or "x,y,z". Returns null on failure. */
    public static Vec3d parse(String s) {
        if (s == null) return null;
        String[] p = s.trim().split("[,\\s]+");
        if (p.length != 3) return null;
        try {
            return new Vec3d(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override public String toString() { return fmt(x) + " " + fmt(y) + " " + fmt(z); }

    private static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }
}

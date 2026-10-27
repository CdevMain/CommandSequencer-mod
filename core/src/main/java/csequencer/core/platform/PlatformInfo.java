package csequencer.core.platform;

import java.util.EnumSet;
import java.util.Set;

/** Identifies the running platform and declares which capabilities it implements. */
public record PlatformInfo(String loader, String loaderVersion, String minecraftVersion, Set<Capability> capabilities) {
    public boolean supports(Capability c) { return c == null || capabilities.contains(c); }

    public static PlatformInfo headless(Capability... caps) {
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        for (Capability c : caps) set.add(c);
        return new PlatformInfo("headless", "test", "none", set);
    }
}

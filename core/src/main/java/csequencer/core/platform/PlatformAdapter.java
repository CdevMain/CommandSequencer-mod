package csequencer.core.platform;

/**
 * Everything the core engine needs from a Minecraft loader/version. One implementation per
 * loader + Minecraft version (e.g. Forge 1.20.1). All methods are called on the server thread
 * unless documented otherwise. See docs/ADAPTER_CONTRACT.md for the full semantics.
 */
public interface PlatformAdapter {
    PlatformInfo info();

    PlayerDirectory players();

    WorldQueryAdapter queries();

    ActionAdapter actions();

    PermissionAdapter permissions();

    ClientChannel clients();

    CoreLogger logger();

    /** Wall-clock milliseconds (used for REALTIME timing and audio synchronization). */
    default long millis() { return System.currentTimeMillis(); }
}

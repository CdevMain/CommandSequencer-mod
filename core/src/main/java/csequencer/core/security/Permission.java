package csequencer.core.security;

import java.util.Locale;

/**
 * Permissions checked by the server. Each maps to a permission node "csequencer.&lt;id&gt;" on
 * platforms with a permission API and to a configurable operator level otherwise.
 */
public enum Permission {
    /** Open the editor and download projects the player may view. */
    EDIT("edit", 2),
    /** Upload/save projects to the server. */
    UPLOAD("upload", 2),
    /** Start sequences manually. */
    RUN("run", 2),
    /** Pause/resume/cancel tasks the player started. */
    CONTROL_OWN("control.own", 2),
    /** Control any task. */
    CONTROL_ALL("control.all", 3),
    /** See every task in the Task Manager / Monitor. */
    MONITOR_ALL("monitor.all", 2),
    /** See the monitor at all (limited by visibility settings). */
    MONITOR("monitor", 0),
    /** Receive top-of-screen sequence announcements (subject to project announce mode). */
    ANNOUNCEMENTS("announcements", 0),
    /** Change server settings and limits; edit/delete any project. */
    ADMIN("admin", 4),
    /** Upload audio files. */
    AUDIO_UPLOAD("audio.upload", 2);

    public final String id;
    public final int defaultLevel;

    Permission(String id, int defaultLevel) {
        this.id = id;
        this.defaultLevel = defaultLevel;
    }

    public String node() { return "csequencer." + id; }

    public static Permission byId(String id) {
        if (id == null) return null;
        for (Permission p : values()) if (p.id.equalsIgnoreCase(id) || p.name().equalsIgnoreCase(id)) return p;
        return null;
    }

    @Override public String toString() { return name().toLowerCase(Locale.ROOT); }
}

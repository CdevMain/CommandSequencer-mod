package csequencer.core.engine;

/** Task-owned scheduled callback (debounce). Cleared when the task ends, so it can never leak. */
final class Timer {
    final String key;
    final int dueTick;
    final Runnable action;
    /** Background timers (e.g. arena barriers) do not keep the task alive. */
    final boolean background;

    Timer(String key, int dueTick, Runnable action) { this(key, dueTick, action, false); }

    Timer(String key, int dueTick, Runnable action, boolean background) {
        this.key = key;
        this.dueTick = dueTick;
        this.action = action;
        this.background = background;
    }
}

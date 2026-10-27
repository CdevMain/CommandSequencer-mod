package csequencer.core.platform;

/** Minimal logging facade so the core does not depend on a logging framework. */
public interface CoreLogger {
    void info(String msg);

    void warn(String msg);

    void error(String msg, Throwable t);

    CoreLogger STDOUT = new CoreLogger() {
        @Override public void info(String msg) { System.out.println("[CSQ] " + msg); }
        @Override public void warn(String msg) { System.out.println("[CSQ][WARN] " + msg); }
        @Override public void error(String msg, Throwable t) {
            System.out.println("[CSQ][ERROR] " + msg);
            if (t != null) t.printStackTrace(System.out);
        }
    };

    CoreLogger SILENT = new CoreLogger() {
        @Override public void info(String msg) {}
        @Override public void warn(String msg) {}
        @Override public void error(String msg, Throwable t) {}
    };
}

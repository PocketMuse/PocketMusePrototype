package net.openid.appauth.internal;

public final class AppAuthLogging {
    private AppAuthLogging() {}

    public static void disable() {
        // AppAuth 0.11.1 logs both state values on mismatch and has no public logging setting.
        Logger.setInstance(new Logger(new Logger.LogWrapper() {
            @Override public boolean isLoggable(String tag, int level) { return false; }
            @Override public void println(int level, String tag, String message) {}
            @Override public String getStackTraceString(Throwable throwable) { return ""; }
        }));
    }
}

package android.util;

/**
 * 本机 JVM 单测专用的 {@link android.util.Log} 桩。
 *
 * <p>存在原因有两个：
 * <ol>
 *   <li>直接引用 SDK 里的 android.jar 时，所有方法体都是 {@code throw new RuntimeException("Stub!")}，
 *       被测代码只要走到打日志的分支就会抛异常，测试变成误报；</li>
 *   <li>AGP 的 {@code unitTests.isReturnDefaultValues} 依赖 mockable android jar，
 *       而本工程路径含中文，测试 JVM 的 classpath 会被 Gradle 写成 @argfile（UTF-8），
 *       java.exe 按 GBK 解码后整个项目路径失效，这条链路不可用。</li>
 * </ol>
 *
 * <p>本文件位于 test 源集，只会进入单元测试的 classpath，不会打进 APK。
 */
public final class Log {

    private Log() {
    }

    public static int v(String tag, String msg) {
        return 0;
    }

    public static int v(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
    }

    public static int d(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int i(String tag, String msg) {
        return 0;
    }

    public static int i(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int w(String tag, String msg) {
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int w(String tag, Throwable tr) {
        return 0;
    }

    public static int e(String tag, String msg) {
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int wtf(String tag, String msg) {
        return 0;
    }

    public static int wtf(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int wtf(String tag, Throwable tr) {
        return 0;
    }

    public static String getStackTraceString(Throwable tr) {
        return "";
    }

    public static boolean isLoggable(String tag, int level) {
        return false;
    }

    public static int println(int priority, String tag, String msg) {
        return 0;
    }
}

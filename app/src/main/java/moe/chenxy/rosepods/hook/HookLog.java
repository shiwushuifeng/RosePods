package moe.chenxy.rosepods.hook;

import android.util.Log;

/**
 * 统一日志。用 Java 类是为了同时方便 Kotlin 和移植过来的 Java 代码调用
 * （Kotlin 里仍然写 HookLog.i("...")）。
 */
public final class HookLog {

    private static final String TAG = "RosePods";

    private HookLog() {
    }

    public static void d(String msg) {
        Log.d(TAG, msg);
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
    }

    public static void w(String msg) {
        Log.w(TAG, msg);
    }

    public static void w(String msg, Throwable t) {
        Log.w(TAG, msg, t);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }
}
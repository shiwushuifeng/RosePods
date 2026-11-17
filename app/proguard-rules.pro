# Xposed 入口类不能被混淆/删除
-keep class moe.chenxy.rosepods.hook.HookEntry { *; }
-keep class moe.chenxy.rosepods.hook.** { *; }

# 保留协议类的公开方法（反射可能用到）
-keep class moe.chenxy.rosepods.pods.** { *; }

# 去掉日志（release）
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

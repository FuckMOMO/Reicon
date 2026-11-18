# Keep libsu
-keep class com.topjohnwu.superuser.** { *; }

# Keep Xposed 入口（release 构建时 LSPosed 需按类名加载）
-keep class com.darkicongen.app.xposed.** { *; }

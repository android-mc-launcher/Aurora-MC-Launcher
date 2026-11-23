# JNI 动态注册（RegisterNatives，12 个方法）依赖类名与方法描述符，必须保留
-keep class com.yuanbao.mcjlauncher.engine.NativeEngine { *; }
-keep class com.yuanbao.mcjlauncher.engine.GameStartResult { *; }
-keepnames class * { native <methods>; }

# 悬浮窗与前台服务
-keep class com.yuanbao.mcjlauncher.overlay.FloatingWindowService { *; }

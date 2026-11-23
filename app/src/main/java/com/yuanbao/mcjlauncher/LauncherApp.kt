package com.yuanbao.mcjlauncher

import android.app.Application
import android.util.Log
import com.yuanbao.mcjlauncher.core.LauncherContainer
import com.yuanbao.mcjlauncher.engine.NativeEngine

class LauncherApp : Application() {
    lateinit var container: LauncherContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // System.loadLibrary 触发 C++ 的 JNI_OnLoad → RegisterNatives 动态注册。
        val ok = NativeEngine.init(this)
        Log.i(TAG, "NativeEngine.init=$ok | ${NativeEngine.engineVersion()}")

        container = LauncherContainer(this)
        // 首次启动给一个可用的离线账号（真实 UUID + 持久化）。
        if (container.accounts.value.isEmpty()) {
            container.accounts.resetDemo()
        }
        Log.i(TAG, "LauncherContainer ready | 账号 ${container.accounts.value.size} | 版本仓库预热中")
    }

    private companion object {
        const val TAG = "Aurora-App"
    }
}

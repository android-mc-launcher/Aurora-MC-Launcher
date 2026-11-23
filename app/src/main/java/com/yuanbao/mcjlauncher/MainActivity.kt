package com.yuanbao.mcjlauncher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import com.yuanbao.mcjlauncher.core.LocalLauncherContainer
import com.yuanbao.mcjlauncher.ui.LauncherRoot

class MainActivity : ComponentActivity() {
    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { /* 结果由各页面按 Settings.canDrawOverlays / checkSelfPermission 自行判断 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()

        val app = application as LauncherApp
        setContent {
            CompositionLocalProvider(LocalLauncherContainer provides app.container) {
                LauncherRoot()
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needed =
            buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}

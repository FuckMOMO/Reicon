package com.darkicongen.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import com.darkicongen.app.ui.AppListScreen
import com.darkicongen.app.ui.HomeScreen
import com.darkicongen.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 模块被停用时的兜底：ColorOS 会拦开机广播，所以每次打开 App 也自检一次。
        // 模块停用 → 把桌面三层图标缓存清掉，图标回到原厂（10 分钟内不重复做）。
        Thread {
            runCatching {
                if (!com.darkicongen.app.mod.ModuleWriter.isModuleDisabled()) return@runCatching
                val sp = getSharedPreferences("darkicon_boot", MODE_PRIVATE)
                val last = sp.getLong("last_restore", 0L)
                if (System.currentTimeMillis() - last < 10 * 60_000L) return@runCatching
                com.darkicongen.app.mod.ModuleWriter.clearDesktopItemIcons(applicationContext)
                sp.edit().putLong("last_restore", System.currentTimeMillis()).apply()
                com.darkicongen.app.mod.ModuleWriter.appendAudit("app launch: module disabled, icon cache cleared")
            }
        }.start()
        setContent {
            DarkIconGenTheme {
                MainScaffold()
            }
        }
    }
}

/** 底部导航三页签（LSPosed 式布局）：首页 / 应用 / 设置 */
private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Filled.Home),
    APPS("应用", Icons.Filled.Apps),
    SETTINGS("设置", Icons.Filled.Settings),
}

@Composable
private fun MainScaffold() {
    var tab by remember { mutableStateOf(Tab.HOME) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.HOME -> HomeScreen()
                Tab.APPS -> AppListScreen()
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@Composable
fun DarkIconGenTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val monetOn = com.darkicongen.app.gen.Monet.enabled(ctx)
    val seed = remember(monetOn) {
        if (monetOn) com.darkicongen.app.gen.Monet.wallpaperSeed(ctx) else null
    }
    val ownScheme = remember(seed, dark, monetOn) {
        if (monetOn && seed != null) com.darkicongen.app.gen.Monet.schemeFromSeed(seed, dark) else null
    }
    val scheme = ownScheme ?: when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

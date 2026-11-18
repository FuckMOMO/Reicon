package com.darkicongen.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.darkicongen.app.mod.ModuleWriter

/**
 * 开机自检：模块被停用时把桌面留存的图标缓存清掉。
 *
 * 场景：用户在 KernelSU 里禁用本模块 → 重启。此时模块的 post-fs-data/service 都不会执行，
 * 挂载自然也没了（桌面上读的是原厂素材），但桌面自己的三层缓存
 * （app_icons.db、launcher.db 的 item blob、launcher cache 目录）里还是我们上次写入的成品图，
 * 所以看起来"模块关了图标却没复原"。
 *
 * 这里在开机后补一刀：模块被禁用 → 清这三次缓存并重建桌面，图标立刻回到原厂。
 * 模块正常启用时什么都不做。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        Thread {
            runCatching {
                if (ModuleWriter.isModuleDisabled()) {
                    ModuleWriter.clearDesktopItemIcons(app)
                    ModuleWriter.appendAudit("boot: module disabled, launcher icon cache cleared")
                }
            }
        }.start()
    }
}

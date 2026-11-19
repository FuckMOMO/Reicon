package com.darkicongen.app.mod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.topjohnwu.superuser.Shell
import java.io.ByteArrayOutputStream

/**
 * 模块目录写入：/data/adb/modules/DarkIconAuto/
 *
 * 机制（v6，主动执行模式 2026-08-30）：
 * - 生成纯声明式：App 只写素材 + 注册 + 版本 +1 + 双缓存 + 审计日志，不碰任何运行时状态。
 * - 运行时收敛由模块脚本一次执行完成（挂载/白名单/缓存对账），入口三个：
 *   开机 post-fs-data.sh（保底）、KSU 播放键 action.sh（手动，额外 force-stop 桌面）、
 *   service.sh（40s 兜底）；共享内核 apply.sh 的 run_apply()，幂等，日志写 run.log。
 * - 白名单移除靠 .managed 台账（只增）与 .packages 的集合差。
 * - 桌面主读取路径：/my_product/media/theme/ 整目录（完整主题包结构：themeInfo.xml、
 *   allApps.xml、drawableMapping.xml 第三方注册、icon_version.xml、uxicons/hdpi/<pkg>/）。
 * - 桌面 1x1 缓存 app_icons.db：版本变化时由执行脚本删除，桌面重启后重建。
 * - 首次使用：exportZip() 生成标准刷入包，用户经 KSU 管理器完整刷入。
 */
object ModuleWriter {

    const val MODULE_ID = "DarkIconAuto"
    const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"

    /**
     * 另一个同类别图标模块（伞姨 DarIconComplete）。
     *
     * 实测 coexistence 场景：它会把 `/my_product/media/theme` 反复 bind 到最上层，
     * 桌面只读最上层，我们的亮色件（day/mat/nsd/recfg）就被盖住 —— 暗色走逐包挂载所以看着正常。
     * 素材会镜像写一份到它的主题目录，保证不论哪层在上，读到的都是我们的。
     */
    const val SISTER_MODULE_DIR = "/data/adb/modules/DarIconComplete"

    /*
     * 素材根目录必须避开系统分区名（my_product / system / data / product …）。
     * hybrid_mount 这类模块会把 `/data/adb/modules/<模块>/my_product/…` 自动 overlay 到 `/my_product/…`
     * （它的 partition_layout 里就列了 my_product），我们再对同一路径 mount --bind 就是双重挂载：
     * 实测后果 —— system_server 的 PackageManager 扫描 /my_product 时卡死 610 秒，watchdog 重启手机
     * （dropbox: system_server_watchdog@…, "Blocked in handler on PackageManager"）。
     * 换成 payload/ 这种中性名字，hybrid_mount 不认识，也就不会插手。
     */
    private const val THEME_SRC = "$MODULE_DIR/payload/theme"
    private const val THEME_DST = "/my_product/media/theme"
    /*
     * 实测结论（把同一包两个位置染成不同颜色、清缓存重启桌面后看缓存颜色确认）：
     * 桌面渲染读的是**少一层**这份 `/my_product/media/theme/uxicons/hdpi/<包名>/`。
     * 多一层的 `…/theme/theme/uxicons/hdpi/` 是官方主题包内部结构，只作为"官方素材来源"读取。
     */
    private const val THEME_HDPI = "$THEME_SRC/uxicons/hdpi"

    /** 旧布局（会被 hybrid_mount 抢挂），启动时自动迁移到 payload/ */
    private const val LEGACY_THEME_SRC = "$MODULE_DIR/my_product/media/theme"
    private const val LEGACY_PKGS_SRC = "$MODULE_DIR/data/oplus/uxicons"

    /** 逐包挂载源：模块内 payload/uxicons/<pkg>/ */
    private const val PKGS_SRC = "$MODULE_DIR/payload/uxicons"
    private const val UXICONS_DST = "/data/oplus/uxicons"

    /**
     * 官方原件备份目录：`$MODULE_DIR/.official_backup/<pkg>/{theme,uxicons}/`
     * 取消勾选时用它把原厂图标原样还回去 —— 主题目录是整挂的，
     * 模块里少了哪个包，桌面上那个包的官方图标就等于被"藏"掉了。
     */
    private const val OFFICIAL_BACKUP = "$MODULE_DIR/.official_backup"
    private const val LAUNCHER_ICON_DB =
        "/data/user_de/0/com.android.launcher/databases/app_icons.db"
    const val LAUNCHER_PKG = "com.android.launcher"

    /** 官方图标标签（首选）；个别设备无该类型时回退伞姨用的 system_file */
    private const val LABEL_OFFICIAL = "u:object_r:uxicons_data_file:s0"
    private const val LABEL_FALLBACK = "u:object_r:system_file:s0"

    /** 主题目录文件标签（伞姨主题目录实测为 system_file） */
    private const val LABEL_THEME = "u:object_r:system_file:s0"

    /**
     * 暗色件前缀：我们生成并始终覆盖官方的暗色素材（含 _1x2/_2x1/_2x2 各档变体）。
     * 其余官方件（day/mat/nsd/peb/outline/快捷方式/art_off 等）一律整目录透传保留，仅官方缺失才生成兜底。
     */
    private val DARK_OVERRIDE_PREFIXES = listOf("rec_night", "recbg", "monochrome")

    /**
     * 我们可能生成的浅色件名单。**只在用户上传了自定义浅色图标时才生成**，未上传时一件都不写：
     * 目录里一旦存在 recfg，桌面浅色就按 recfg 叠 recbg 合成、不再读应用自身图标
     * （net.xmind.doughnut 实测接缝落在 168 渲染图 x=19..149，正是 recfg 那块 78% 方块的边界）；
     * 缺件则回退到应用原图标，浅色保持原样。伞姨模块实证同一结论：air.tv.douyu.android 已登记在
     * morph_icons_whitelist，整个目录却只有 rec_night.png 一个文件，浅色图标原样；bin.mt.plus 只有
     * rec_night 四件；me.ele / com.oplus.games 均无 day/mat/nsd/art_off。
     *
     * 两个用途：(1) customDay 非空时按此名单强制覆盖官方同名件，否则有官方下发的包会保留官方浅色素材、
     * 上传图完全不生效；(2) 作为清理历史遗留件的范围——本名单之外的文件（官方件、伞姨件、peb/outline/
     * 快捷方式等）一律不删。
     */
    private val LIGHT_GENERATED_NAMES = setOf(
        "day.png", "mat.png", "nsd.png", "recfg.png", "art_off.png",
        "mat_2x2.png", "recfg_2x2.png", "recfg_1x2.png", "recfg_2x1.png",
    )

    @Volatile
    private var selinuxLabel: String? = null

    /** 确保模块骨架存在（首次生成时调用） */
    fun ensureSkeleton(): Boolean {
        val sh = Shell.getShell()
        if (!sh.isRoot) return false
        Shell.cmd("mkdir -p $MODULE_DIR").exec()
        val prop = """
            id=$MODULE_ID
            name=Reicon
            version=1.0.0
            versionCode=100
            author=Carrot
            description=ColorOS17 dark and light icon completion module
        """.trimIndent()
        Shell.cmd("cat > $MODULE_DIR/module.prop <<'EOF'\n$prop\nEOF").exec()
        migrateLegacyPayload()
        // 挂载身份标记：只有我们这层在桌面路径的最上面时，才能从这个路径读到它。
        // service.sh 的开机守护靠它判断"还要不要重挂"，比解析 mountinfo 层级可靠。
        Shell.cmd(
            "mkdir -p $THEME_SRC",
            "touch $THEME_SRC/.darkicon_owner",
            "chcon $LABEL_THEME $THEME_SRC/.darkicon_owner 2>/dev/null",
        ).exec()
        migrateSnapshotLayout()
        return true
    }

    /**
     * 旧布局迁移：`my_product/`、`data/` 这两个目录名会被 hybrid_mount 当成"要 overlay 的分区"，
     * 和我们的 mount --bind 撞车（实测导致 PackageManager 卡死 + 重启）。
     * 这里把素材搬到 `payload/` 下，并把旧目录整个删掉，避免它继续被接管。
     */
    private fun migrateLegacyPayload() {
        val moved = mutableListOf<String>()
        if (dirExists(LEGACY_THEME_SRC)) {
            Shell.cmd(
                "mkdir -p $THEME_SRC",
                "cp -a $LEGACY_THEME_SRC/. $THEME_SRC/ 2>/dev/null",
                "rm -rf $LEGACY_THEME_SRC",
            ).exec()
            moved += "theme"
        }
        if (dirExists(LEGACY_PKGS_SRC)) {
            Shell.cmd(
                "mkdir -p $PKGS_SRC",
                "cp -a $LEGACY_PKGS_SRC/. $PKGS_SRC/ 2>/dev/null",
                "rm -rf $LEGACY_PKGS_SRC",
            ).exec()
            moved += "uxicons"
        }
        // 旧父目录清干净（空目录也会被 hybrid_mount 当成 overlay 目标）
        Shell.cmd(
            "rmdir $MODULE_DIR/my_product/media/theme 2>/dev/null; " +
                "rmdir $MODULE_DIR/my_product/media 2>/dev/null; rmdir $MODULE_DIR/my_product 2>/dev/null; " +
                "rmdir $MODULE_DIR/data/oplus/uxicons 2>/dev/null; rmdir $MODULE_DIR/data/oplus 2>/dev/null; " +
                "rmdir $MODULE_DIR/data 2>/dev/null",
        ).exec()
        if (moved.isNotEmpty()) appendAudit("migrated payload -> payload/ (${moved.joinToString()})")
    }

    /** v4→v5 布局迁移：仅保留 .packages 清单内的包目录，整目录镜像全部丢弃（刷入包瘦身） */
    private fun migrateSnapshotLayout() {
        val legacy = "$PKGS_SRC/_snapshot"
        if (!dirExists(legacy)) return
        Shell.cmd(
            "while IFS= read -r p; do [ -n \"\$p\" ] && [ -d \"$legacy/\$p\" ] && mv -f \"$legacy/\$p\" \"$PKGS_SRC/\$p\"; done < $MODULE_DIR/.packages 2>/dev/null",
            "rm -rf $legacy",
        ).exec()
    }

    /**
     * 主题包基底（首次一次性复制）：优先复制伞姨主题目录（需包含她全部内容，
     * 我们字母序在后整挂会盖住她），无伞姨则复制官方主题目录。
     */
    fun ensureThemeBase(): Boolean {
        val has = Shell.cmd("[ -f $THEME_SRC/themeInfo.xml ] && echo EXISTS").exec()
        if (has.out.any { it.contains("EXISTS") }) return true
        val src = if (dirExists("$SISTER_MODULE_DIR/my_product/media/theme")) {
            "$SISTER_MODULE_DIR/my_product/media/theme"
        } else {
            THEME_DST
        }
        Shell.cmd(
            "mkdir -p $MODULE_DIR/my_product/media",
            "cp -a $src $THEME_SRC",
            "chcon -R $LABEL_THEME $THEME_SRC",
        ).exec()
        return Shell.cmd("[ -f $THEME_SRC/themeInfo.xml ] && echo OK").exec()
            .out.any { it.contains("OK") }
    }

    /**
     * 生成模块脚本（固定模板，与包列表无关）：
     * apply.sh 共享内核（run_apply：挂载→残留清理→白名单同步→缓存对账→日志），
     * 三入口：post-fs-data.sh（boot，不 force-stop）/ action.sh（manual，force-stop 桌面）/
     * service.sh（40s 兜底）。增删包无需重写脚本、无需重新刷入。
     */
    fun regenerateMountScript(packages: List<String>) {
        val apply = buildApplyScript()
        val pfs = """
            #!/system/bin/sh
            MODDIR=${'$'}{0%/*}
            . ${'$'}MODDIR/apply.sh
            run_apply boot
        """.trimIndent() + "\n"
        // action.sh（KSU 播放键）：存在 .force_reload 标记时走全量重载，否则保持原「差量收敛」语义。
        // 标记由 App 的「全量重载」写入：App 自己有权执行时会直接跑完并删除标记；
        // 若 App 侧执行失败（无 root / 被 scheduler 打断），标记留下，用户按播放键即可完成同一件事。
        val action = """
            #!/system/bin/sh
            MODDIR=${'$'}{0%/*}
            . ${'$'}MODDIR/apply.sh
            if [ -f ${'$'}MODDIR/.force_reload ]; then
              rm -f ${'$'}MODDIR/.force_reload
              run_apply reload
            else
              run_apply manual
            fi
        """.trimIndent() + "\n"
        val svc = """
            #!/system/bin/sh
            sleep 40
            MODDIR=$MODULE_DIR
            . ${'$'}MODDIR/apply.sh
            run_apply service
            # 开机自愈：**定点检查 4 次，不是轮询**。
            # ColorOS 主题服务一般在开机 40~150 秒内起来，会把自己的挂载盖到最上层；
            # 所以只需要在这个窗口里查几次（累计 45/90/150/210 秒），修好一次就退出。
            # 原版是 20 轮 × 30 秒 = 10 分钟的 while 轮询，这里减到 4 次 grep，开销可以忽略。
            # 判据用模块 theme 目录里的标记文件 `.darkicon_owner`：
            # 只有我们那层在最上面时，桌面路径下才看得到它（比解析 mountinfo 层级顺序可靠）。
            (
              FIXED=0
              for spec in 45 45 60 60; do
                sleep ${'$'}spec
                if [ ! -f ${'$'}THEME_ROOT/.darkicon_owner ] && [ ${'$'}FIXED -eq 0 ]; then
                  if nsenter -t 1 -m -- mount --bind ${'$'}MODDIR/payload/theme/ ${'$'}THEME_ROOT/ 2>/dev/null; then
                    FIXED=1
                    echo "${'$'}(date '+%m-%d %H:%M:%S') [service] 主题挂载被挤掉，已重挂" >> ${'$'}MODDIR/run.log
                    clear_icon_db
                    am force-stop com.android.launcher
                    sleep 1
                    am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
                    echo "${'$'}(date '+%m-%d %H:%M:%S') [service] 已在健康挂载下重建桌面缓存，守护退出" >> ${'$'}MODDIR/run.log
                    break
                  fi
                fi
              done
            ) &
        """.trimIndent() + "\n"
        // 单引号 heredoc：内容原样写入，不做任何 shell 展开（避免多层转义踩坑）
        Shell.cmd(
            "cat > $MODULE_DIR/apply.sh <<'DIEOF'\n$apply" + "DIEOF",
            "chmod 755 $MODULE_DIR/apply.sh",
            "cat > $MODULE_DIR/post-fs-data.sh <<'DIEOF'\n$pfs" + "DIEOF",
            "chmod 755 $MODULE_DIR/post-fs-data.sh",
            "cat > $MODULE_DIR/action.sh <<'DIEOF'\n$action" + "DIEOF",
            "chmod 755 $MODULE_DIR/action.sh",
            "cat > $MODULE_DIR/service.sh <<'DIEOF'\n$svc" + "DIEOF",
            "chmod 755 $MODULE_DIR/service.sh",
        ).exec()
        // Xposed 钩子读取的受管包名清单（一行一个）
        val conf = packages.joinToString("\n")
        Shell.cmd("cat > $MODULE_DIR/xposed.conf <<'EOF'\n$conf\nEOF").exec()
    }

    /** apply.sh 内核：状态自检式全链路收敛（幂等），日志追加至 run.log 并同步 stdout */
    private fun buildApplyScript(): String {
        val head = """
            # DarkIconAuto apply core - state self-check convergence (idempotent)
            # usage: MODDIR=<module dir>; . apply.sh; run_apply <boot|manual|service|reload>
            #   boot    : 开机，只补缺（不重启桌面）
            #   manual  : KSU 播放键，差量收敛 + 有改动才重启桌面
            #   service : 开机 40s 兜底
            #   reload  : 全量重载，忽略"已经装过"的判据，强制重挂 + 强制清缓存 + 强制重启桌面
            run_apply() {
              TRIGGER=${'$'}1
              FORCE=0
              [ "${'$'}TRIGGER" = "reload" ] && FORCE=1
              THEME_ROOT="$THEME_DST"
              LOG=${'$'}MODDIR/run.log
              L() {
                echo "${'$'}(date '+%m-%d %H:%M:%S') ${'$'}1" >> ${'$'}LOG
                echo "${'$'}1"
              }
              # 清桌面图标缓存（app_icons.db）。
              # App 通过 libsu 执行时进程活在自己的 mount namespace 里，**看不到 /data/user_de/…**：
              # 普通 rm 静默失败、桌面虽然重启了但缓存还在 —— 这正是"KSU 执行有效、App 执行没反应"的原因。
              # 所以先在当前 ns 删一遍，再进 PID 1 的命名空间删一遍，两条路总有一条能成。
              clear_icon_db() {
                rm -f $LAUNCHER_ICON_DB $LAUNCHER_ICON_DB-journal $LAUNCHER_ICON_DB-wal $LAUNCHER_ICON_DB-shm 2>/dev/null
                nsenter -t 1 -m -- rm -f $LAUNCHER_ICON_DB $LAUNCHER_ICON_DB-journal $LAUNCHER_ICON_DB-wal $LAUNCHER_ICON_DB-shm 2>/dev/null
                [ -f $LAUNCHER_ICON_DB ] && L "警告：桌面缓存清理失败（app_icons.db 仍存在）"
                return 0
              }
              SZ=${'$'}(wc -c < ${'$'}LOG 2>/dev/null || echo 0)
              [ "${'$'}SZ" -gt 65536 ] && { tail -c 32768 ${'$'}LOG > ${'$'}LOG.tmp && mv ${'$'}LOG.tmp ${'$'}LOG; }
              L "=== ${'$'}(date '+%m-%d %H:%M:%S') [${'$'}TRIGGER] ==="
              if [ "${'$'}FORCE" = "1" ]; then
                L "全量重载：主题挂载已卸载，准备重新挂载"
              fi
              # 只在"我们不在最上层"时收拾，而且**只卸源路径属于本模块的层**。
              # 绝对不能无脑 umount：装了 hybrid_mount 的设备上，这个挂载点可能同时还挂着它做的
              # overlay / 别的模块的 bind，乱卸会连带把系统挂载拆掉（实测曾导致系统异常重启）。
              if [ "${'$'}FORCE" = "1" ] || ! grep -q "${'$'}MODDIR/payload/theme ${'$'}THEME_ROOT" /proc/1/mountinfo 2>/dev/null; then
                OURS=${'$'}(grep " ${'$'}THEME_ROOT " /proc/1/mountinfo 2>/dev/null | grep "DarkIconAuto/payload/theme" | awk '{print ${'$'}5}')
                n=0
                for m in ${'$'}OURS; do
                  nsenter -t 1 -m -- umount -l "${'$'}m" 2>/dev/null || umount -l "${'$'}m" 2>/dev/null
                  n=${'$'}((n+1))
                done
                [ ${'$'}n -gt 0 ] && L "主题挂载：已卸掉本模块的 ${'$'}n 层旧挂载"
              fi
              if grep -q "${'$'}MODDIR/payload/theme ${'$'}THEME_ROOT" /proc/1/mountinfo 2>/dev/null; then
                L "主题挂载就绪"
              elif nsenter -t 1 -m -- mount --bind ${'$'}MODDIR/payload/theme/ ${'$'}THEME_ROOT/ 2>/dev/null; then
                L "主题挂载就绪"
              elif mount --bind ${'$'}MODDIR/payload/theme/ ${'$'}THEME_ROOT/; then
                L "主题挂载就绪"
              else
                L "主题挂载 FAIL"
              fi
              # 「新增 / 移除」以**实际挂载状态**为准：
              #   新增 = 清单里有、但还没有挂载的包（本次要挂的）
              #   移除 = 已挂载、但清单里已经没有的包
              # 不能拿 .managed 比：App 写素材时就已经登记进 .managed 了，比出来永远是空。
              NEWP=""
              while IFS= read -r p; do
                [ -z "${'$'}p" ] && continue
                grep -q "payload/uxicons/${'$'}p " /proc/1/mountinfo 2>/dev/null || NEWP="${'$'}NEWP ${'$'}p"
              done < ${'$'}MODDIR/.packages 2>/dev/null
              GONE=""
              for mpath in ${'$'}(grep -o "payload/uxicons/[^ ]*" /proc/1/mountinfo 2>/dev/null | sort -u); do
                MPK=${'$'}{mpath##*/}
                [ -z "${'$'}MPK" ] && continue
                grep -qx "${'$'}MPK" ${'$'}MODDIR/.packages 2>/dev/null || GONE="${'$'}GONE ${'$'}MPK"
              done
        """.trimIndent()
        // 残留/堆叠挂载清理：素材已删的包全部卸载；仍在册但堆叠多于 1 层的卸到零（后续统一重挂一层）
        val staleCleanup = """
              PKMNTS=${'$'}(grep "DarkIconAuto/payload/uxicons/" /proc/1/mountinfo 2>/dev/null | awk '{print ${'$'}5}' | grep "^$UXICONS_DST/")
              for mnt in ${'$'}PKMNTS; do
                rp=${'$'}{mnt##*/}
                if [ ! -d "${'$'}MODDIR/payload/uxicons/${'$'}rp" ]; then
                  if umount "${'$'}mnt" 2>/dev/null || nsenter -t 1 -m -- umount "${'$'}mnt" 2>/dev/null; then
                    case "${'$'}REMOVED" in *" ${'$'}rp"*) ;; *) REMOVED="${'$'}REMOVED ${'$'}rp";; esac
                    L "${'$'}rp: 已卸载（素材已移除）"
                    rmdir "${'$'}mnt" 2>/dev/null && L "${'$'}rp: 空挂载点已清理"
                  else
                    L "${'$'}rp: 卸载 FAIL"
                  fi
                else
                  n=${'$'}(printf '%s\n' ${'$'}PKMNTS | grep -c "^${'$'}mnt${'$'}")
                  [ "${'$'}n" -gt 1 ] && { umount "${'$'}mnt" 2>/dev/null || nsenter -t 1 -m -- umount "${'$'}mnt" 2>/dev/null; }
                fi
              done
        """.trimIndent()
        // 逐包挂载 + 白名单注入（铁律：注入必须在删库之前）
        val mountLoop = """
              ADDED=""
              S=${'$'}MODDIR/payload/uxicons
              SIS=$SISTER_MODULE_DIR/data/oplus/uxicons
              W=$UXICONS_DST/morph_icons_whitelist.xml
              while IFS= read -r p; do
                [ -z "${'$'}p" ] && continue
                if [ ! -d "${'$'}S/${'$'}p" ]; then L "${'$'}p: 无素材，跳过"; continue; fi
                if [ -d "${'$'}SIS/${'$'}p" ]; then L "${'$'}p: 伞姨已覆盖，uxicons 跳过"; fi
                NEED=0
                if grep -q " $UXICONS_DST/${'$'}p " /proc/1/mountinfo 2>/dev/null; then
                  if [ "${'$'}FORCE" = "1" ]; then
                    # 全量重载：旧挂载一律拆掉重来（用户删过素材/挂载指向已删 inode 时只有重挂能救）
                    umount "$UXICONS_DST/${'$'}p" 2>/dev/null || nsenter -t 1 -m -- umount "$UXICONS_DST/${'$'}p" 2>/dev/null
                    NEED=1
                    L "${'$'}p: 全量重载，已卸载旧挂载"
                  else
                    L "${'$'}p: 挂载保持"
                  fi
                else
                  NEED=1
                fi
                if [ "${'$'}NEED" = "1" ]; then
                  [ -d "$UXICONS_DST/${'$'}p" ] || { mkdir -p "$UXICONS_DST/${'$'}p"; chcon $LABEL_OFFICIAL "$UXICONS_DST/${'$'}p"; }
                  if nsenter -t 1 -m -- mount --bind "${'$'}S/${'$'}p" "$UXICONS_DST/${'$'}p" 2>/dev/null || mount --bind "${'$'}S/${'$'}p" "$UXICONS_DST/${'$'}p"; then
                    ADDED="${'$'}ADDED ${'$'}p"
                    L "${'$'}p: 挂载成功"
                  else
                    L "${'$'}p: 挂载 FAIL"
                  fi
                fi
                if grep -q "<package_name>${'$'}p<morph>" ${'$'}W 2>/dev/null; then
                  L "${'$'}p: 白名单已在列"
                else
                  # 精确判断 + 写后校验：之前用包名子串 grep，
                  # 只要文件里出现 com.taobao.taobao 这种前缀（闲鱼、淘宝直播等）就误判"已在列"，
                  # 于是永远不注入，桌面拿不到 morph 标记 → 素材写了也不生效。
                  sed -i "s|</filter-conf>|<package_name>${'$'}p<morph>1</morph></package_name></filter-conf>|" ${'$'}W 2>/dev/null
                  if grep -q "<package_name>${'$'}p<morph>" ${'$'}W 2>/dev/null; then
                    L "${'$'}p: 白名单已注入"
                  else
                    L "${'$'}p: 白名单注入 FAIL"
                  fi
                fi
              done < ${'$'}MODDIR/.packages 2>/dev/null
        """.trimIndent()
        // 白名单清除：.managed 台账有但 .packages 没有的条目（取消勾选的包）
        val whitelistPurge = """
              while IFS= read -r mp; do
                [ -z "${'$'}mp" ] && continue
                if ! grep -qx "${'$'}mp" ${'$'}MODDIR/.packages 2>/dev/null; then
                  if grep -q "${'$'}mp" ${'$'}W 2>/dev/null; then
                    sed -i "/<package_name>${'$'}mp<morph>1<\/morph><\/package_name>/d" ${'$'}W 2>/dev/null && L "${'$'}mp: 白名单条目已清除"
                  fi
                fi
              done < ${'$'}MODDIR/.managed 2>/dev/null
        """.trimIndent()
        // 缓存对账：版本变化才删桌面 1x1 缓存（.last_version 记录）
        val cacheBlock = """
              CACHE="kept"
              F=${'$'}MODDIR/payload/theme/icon_version.xml
              V=${'$'}(grep -o '<version>[0-9]*' ${'$'}F 2>/dev/null | grep -o '[0-9]*' | head -1)
              LV=${'$'}(cat ${'$'}MODDIR/.last_version 2>/dev/null)
              if [ "${'$'}FORCE" = "1" ]; then
                # 全量重载：不看版本号，无条件清缓存（"装过了所以没变化"正是要解决的问题）
                clear_icon_db
                echo "${'$'}V" > ${'$'}MODDIR/.last_version
                CACHE="强制清除（全量重载）"
                L "全量重载：已清桌面图标缓存（版本 ${'$'}V 不变也清）"
              elif [ "${'$'}V" != "${'$'}LV" ]; then
                clear_icon_db
                echo "${'$'}V" > ${'$'}MODDIR/.last_version
                CACHE="已清除（'${'$'}LV'→'${'$'}V'）"
                L "图标版本变更（'${'$'}LV' → '${'$'}V'）：已清桌面缓存"
              else
                L "图标版本 ${'$'}V 未变：缓存保留"
              fi
        """.trimIndent()
        val tail = """
              if [ "${'$'}TRIGGER" = "manual" ] || [ "${'$'}TRIGGER" = "reload" ]; then
                if [ "${'$'}FORCE" = "1" ] || [ -n "${'$'}NEWP" ] || [ -n "${'$'}GONE" ] || [ "${'$'}CACHE" != "kept" ]; then
                  # 对齐上游的最小做法：清 app_icons.db + force-stop 就够，**不要**再主动 am start。
                  # 实测结论（美团对照实验）：
                  #   · 只 rm app_icons.db + am force-stop      → 图标立刻变（正确）
                  #   · 再加 am start / 清 launcher cache/ / 改 launcher.db 的 item blob → 桌面只是"闪一下"，
                  #     图标反而停在旧图（桌面在缓存没重建完时被提前拉起，又写回一轮旧数据）。
                  clear_icon_db
                  am force-stop com.android.launcher
                  L "已重启桌面（重建图标）"
                else
                  L "无需收敛：桌面未改动"
                fi
              fi
              L "新增:${'$'}NEWP"
              L "移除:${'$'}GONE"
              # 台账同步：本次执行完，"已处理"= 当前清单，下次才能算对差量
              cp -f ${'$'}MODDIR/.packages ${'$'}MODDIR/.managed 2>/dev/null
              L "缓存: ${'$'}CACHE"
              L "=== 完成 ==="
            }
        """.trimIndent()
        return head + "\n" + staleCleanup + "\n" + mountLoop + "\n" + whitelistPurge +
            "\n" + cacheBlock + "\n" + tail + "\n"
    }

    /** 列出我们模块已适配的包名（生成时写入的清单文件） */
    fun listPackages(): List<String> {
        val r = Shell.cmd("cat $MODULE_DIR/.packages 2>/dev/null").exec()
        return r.out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 包名登记到清单（幂等） */
    private fun registerPackageList(pkg: String) {
        Shell.cmd(
            "grep -qx '$pkg' $MODULE_DIR/.packages 2>/dev/null || echo '$pkg' >> $MODULE_DIR/.packages"
        ).exec()
    }

    /** 伞姨模块已适配的包名（用于 UI 提示） */
    fun sisterPackages(): List<String> {
        val r = Shell.cmd("ls $SISTER_MODULE_DIR/data/oplus/uxicons 2>/dev/null").exec()
        return r.out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 写入一个包的素材（纯声明式，不碰任何运行时状态）：
     * 整目录透传官方素材为基底 + 覆盖暗色件 + 注册 + 版本+1 + .managed 台账 + run.log 审计 + 末次缓存。
     * 挂载/白名单/缓存重建由执行脚本（开机或 KSU 播放键）收敛。
     *
     * @param customDay 非空则由它派生全部浅色件并强制覆盖官方同名件；null = 不写任何浅色件，
     *                  且清理历史遗留的浅色件，桌面浅色回退到应用自身图标（保持原样）。
     */
    fun writePackageIcons(
        context: Context,
        pkg: String,
        foreground240: Bitmap,     // 已按风格处理好的 240 前景（或自定义上传归一后的前景）
        monochrome240: Bitmap,     // 黑色 monochrome
        bgColor: Int,              // recbg 颜色
        style: String = "CUTOUT",  // 方案标记（缓存元数据用）
        customDay: Bitmap? = null, // 自定义浅色图标原料；null = 按亮色方案（由调用方决定是否已含生成结果）
        darkEnabled: Boolean = true, // false = 不写暗色件，并把历史残留的暗色件删掉（"原图标"选项）
    ): Boolean {
        // 写之前先把官方原件备份一份：否则一旦覆盖/清理了官方件，取消勾选就再也找不回原厂图标了
        backupOfficialOnce(context, pkg)
        /*
         * 官方适配过的应用：**只改亮色两件，暗色完全不动**。
         * 官方亮色 = recbg(底) + recfg(字形)；官方暗色 = rec_night(字形) + 黑底。
         * 所以只需：
         *   1. recfg* ← 复制 rec_night*（拿到真字形）
         *   2. recbg* ← 生成同尺寸纯白
         * 暗色件保持官方原样 —— 不需要我们渲染，也不需要比例。
         */
        if (writeOfficialLightOnly(context, pkg)) {
            registerPackageList(pkg)
            registerManaged(pkg)
            bumpIconVersion()
            appendAudit("generated $pkg v${readIconVersion()} style=OFFICIAL-LIGHT (recfg=rec_night, recbg=white)")
            return true
        }
        val generated = buildPackagePngs(foreground240, monochrome240, bgColor, customDay, darkEnabled)
        // 整目录透传：以官方 /data/oplus/uxicons/<pkg>/ 全量素材为基底，只覆盖暗色件，
        // 保留 day/mat/nsd/peb/outline/快捷方式/art_off 等官方件（修复浅色图标被顶替消失）。
        val official = readOfficialUxicons(context, pkg)
        val allFiles = mergePreserveOfficial(official, generated, customDay != null)
        pushAssetFiles(context, pkg, allFiles, darkEnabled)
        if (ensureThemeBase()) {
            registerDrawableMapping(pkg)
        }
        registerPackageList(pkg)
        registerManaged(pkg)
        bumpIconVersion()
        // 末次素材留档（含透传的官方件），供排查"上次到底写了哪些文件"
        com.darkicongen.app.gen.IconCache.storeGenerated(
            context, pkg, allFiles, style, bgColor, readIconVersion()
        )
        val lightN = allFiles.count { it.first in LIGHT_GENERATED_NAMES }
        val darkN = allFiles.count { n -> DARK_OVERRIDE_PREFIXES.any { n.first.startsWith(it) } }
        val dayNote = if (customDay != null) " customDay" else ""
        appendAudit(
            "generated $pkg v${readIconVersion()} style=$style base=${official.size} " +
                "dark=$darkN light=$lightN$dayNote"
        )
        return true
    }

    /**
     * 素材目录权限归一：**root 所有 + 755 目录 + 644 图片**。
     *
     * 这是"导入/生成后图标不生效"的头号原因：用 `cp -a` 从 App 私有目录拷文件时，
     * 会把 App 自己的 uid（u0_a168）和 `drwx--S---`(770) 一起带进模块目录，
     * 桌面是另一个 uid，连目录都进不去 —— 素材写了也读不到。
     */
    private fun normalizeAssetDir(dir: String, label: String = LABEL_THEME) {
        Shell.cmd(
            "chown -R 0:0 '$dir' 2>/dev/null",
            "chmod -R 755 '$dir' 2>/dev/null",
            "find '$dir' -name '*.png' -exec chmod 644 {} + 2>/dev/null",
            "chcon -R $label '$dir' 2>/dev/null",
        ).exec()
    }

    /** 官方素材源目录（优先 /data/oplus/uxicons，其次主题目录里多一层 theme 的那份） */
    private fun officialSourceDir(pkg: String): String? {
        val candidates = listOf(
            "$UXICONS_DST/$pkg",
            "$THEME_DST/theme/uxicons/hdpi/$pkg",
            "$THEME_DST/uxicons/hdpi/$pkg",
        )
        for (d in candidates) {
            val has = Shell.cmd("[ -f '$d/rec_night.png' ] && echo YES").exec().out.any { it.contains("YES") }
            if (has) return d
        }
        return null
    }

    /**
     * 官方适配包的亮色管线（文件级，不做任何渲染）：
     * - `recfg*` ← 复制 `rec_night*`
     * - `recbg*` ← 同尺寸纯白
     * - 其余文件（含暗色 rec_night / mat / nsd / monochrome / 快捷方式图标）原样保留
     *
     * 返回 false 表示这个包没有官方素材，交给常规管线处理。
     */
    private fun writeOfficialLightOnly(context: Context, pkg: String): Boolean = runCatching {
        writeOfficialLightOnlyInner(context, pkg)
    }.getOrElse { err ->
        appendAudit("official light FAILED $pkg: ${err.javaClass.simpleName}: ${err.message}")
        false
    }

    private fun writeOfficialLightOnlyInner(context: Context, pkg: String): Boolean {
        val src = officialSourceDir(pkg) ?: return false
        val tmp = java.io.File(context.cacheDir, "off_light_$pkg")
        tmp.deleteRecursively()
        tmp.mkdirs()
        Shell.cmd("cp -a '$src/.' '${tmp.absolutePath}/' 2>/dev/null", "chmod -R 644 '${tmp.absolutePath}'/*.png 2>/dev/null").exec()
        val files = tmp.listFiles()
        if (files.isNullOrEmpty()) {
            appendAudit("official light: $pkg 读取官方素材失败")
            tmp.deleteRecursively()
            return false
        }
        var touched = 0
        for (v in listOf("", "_2x2", "_1x2", "_2x1")) {
            val night = java.io.File(tmp, "rec_night$v.png")
            if (!night.exists()) continue
            /*
             * recfg ← rec_night，但按当前亮色比例重新摆位：
             * 直接复制的话字形占比被官方锁死，详情页的比例滑块对官方包就没作用了。
             */
            val lightRatio = com.darkicongen.app.gen.RatioPrefs.currentLight(context)
            runCatching {
                val bmp = android.graphics.BitmapFactory.decodeFile(night.absolutePath)
                if (bmp != null) {
                    val fitted = com.darkicongen.app.gen.ForegroundProcessor.fitToLayer(bmp, lightRatio)
                    java.io.FileOutputStream(java.io.File(tmp, "recfg$v.png")).use { fos ->
                        fitted.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                } else {
                    night.copyTo(java.io.File(tmp, "recfg$v.png"), overwrite = true)
                }
            }.onFailure {
                night.copyTo(java.io.File(tmp, "recfg$v.png"), overwrite = true)
            }
            // recbg ← 同尺寸纯白（尺寸优先取官方 recbg，没有就用 rec_night 的）
            val bgRef = java.io.File(tmp, "recbg$v.png").takeIf { it.exists() } ?: night
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(bgRef.absolutePath, opts)
            val w = if (opts.outWidth > 0) opts.outWidth else 180
            val h = if (opts.outHeight > 0) opts.outHeight else 180
            val white = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            white.eraseColor(android.graphics.Color.WHITE)
            java.io.FileOutputStream(java.io.File(tmp, "recbg$v.png")).use {
                white.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            touched++
        }
        if (touched == 0) {
            appendAudit("official light: $pkg 没有 rec_night，跳过")
            tmp.deleteRecursively()
            return false
        }
        // 写回两侧（主题侧 = 官方真实路径；逐包侧 = /data/oplus/uxicons 的挂载源）
        Shell.cmd(
            "mkdir -p $THEME_HDPI/$pkg",
            "rm -f $THEME_HDPI/$pkg/*.png 2>/dev/null",
            "cp -a '${tmp.absolutePath}/.' $THEME_HDPI/$pkg/ 2>/dev/null",
            "chmod 644 $THEME_HDPI/$pkg/*.png 2>/dev/null",
            "chown 0:0 $THEME_HDPI/$pkg/*.png 2>/dev/null",
            "chcon $LABEL_THEME $THEME_HDPI/$pkg/*.png 2>/dev/null",
            "mkdir -p $PKGS_SRC/$pkg",
            "rm -f $PKGS_SRC/$pkg/*.png 2>/dev/null",
            "cp -a '${tmp.absolutePath}/.' $PKGS_SRC/$pkg/ 2>/dev/null",
            "chmod 644 $PKGS_SRC/$pkg/*.png 2>/dev/null",
            "chown 0:0 $PKGS_SRC/$pkg/*.png 2>/dev/null",
            "chcon -R $LABEL_OFFICIAL $PKGS_SRC/$pkg 2>/dev/null",
        ).exec()
        // cp -a 会带进 App 的 uid/770 权限，桌面读不到 → 统一归一回 root:root 755/644
        normalizeAssetDir("$THEME_HDPI/$pkg", LABEL_THEME)
        normalizeAssetDir("$PKGS_SRC/$pkg", LABEL_OFFICIAL)
        tmp.deleteRecursively()
        return true
    }

    /**
     * 整目录读取官方 uxicons 素材（透传基底）。
     *
     * 顺序：**官方备份** → 外部官方源 → 伞姨模块。
     * 备份放第一位是因为它是唯一"永远不会被我们自己的输出污染"的一份：
     * 首次生成前抓的官方原件，之后无论我们怎么写、怎么清，透传基底都能从它恢复。
     */
    private fun readOfficialUxicons(context: Context, pkg: String): List<Pair<String, ByteArray>> {
        val backup = "$OFFICIAL_BACKUP/$pkg/uxicons"
        val sources = buildList {
            add(backup)
            if (!isSelfMounted(pkg)) add("$UXICONS_DST/$pkg")
            add("$SISTER_MODULE_DIR/data/oplus/uxicons/$pkg")
        }
        for (dir in sources) {
            val names = readRootDirFiles(dir)
            if (names.isEmpty()) continue
            val files = names.mapNotNull { n -> readRootFileBytes(context, "$dir/$n")?.let { n to it } }
            if (files.isNotEmpty()) return files
        }
        return emptyList()
    }

    /**
     * 首次为某个包生成时，把官方原件整目录备份到 `$OFFICIAL_BACKUP/<pkg>/`：
     *  - `theme/`   ← 模块主题目录里该包当时的全部文件（首次生成前它就是从系统主题拷贝来的官方件）
     *  - `uxicons/` ← `/data/oplus/uxicons/<pkg>`（未自挂载时才是官方原件）
     * 备份一次就不再覆盖（避免把后续生成物当官方件备份进去）。
     */
    private fun backupOfficialOnce(context: Context, pkg: String) {
        val base = "$OFFICIAL_BACKUP/$pkg"
        val hasTheme = dirExists("$base/theme")
        val hasUx = dirExists("$base/uxicons")
        if (hasTheme || hasUx) return
        var saved = 0
        try {
            // 系统底层（唯一没被我们污染过的官方源）：lazy umount → 拷 → 立刻挂回
            unmountThemeAll()
            val sysDir = "$THEME_DST/uxicons/hdpi/$pkg"
            if (readRootDirFiles(sysDir).isNotEmpty()) {
                Shell.cmd(
                    "mkdir -p '$base/theme'",
                    "cp -a '$sysDir'/. '$base/theme'/ 2>/dev/null",
                    "chcon -R $LABEL_THEME '$base/theme' 2>/dev/null",
                ).exec()
                saved += readRootDirFiles("$base/theme").size
            }
        } finally {
            remountTheme()
        }
        // 逐包侧：未自挂载时 `/data/oplus/uxicons/<pkg>` 就是官方原件
        if (!isSelfMounted(pkg) && readRootDirFiles("$UXICONS_DST/$pkg").isNotEmpty()) {
            Shell.cmd(
                "mkdir -p '$base/uxicons'",
                "cp -a $UXICONS_DST/$pkg/. '$base/uxicons/' 2>/dev/null",
                "chcon -R $LABEL_OFFICIAL '$base/uxicons' 2>/dev/null",
            ).exec()
            saved += readRootDirFiles("$base/uxicons").size
        }
        if (saved > 0) appendAudit("backup official $pkg ($saved files)")
    }

    /** /data/oplus/uxicons/<pkg> 当前是否已被本模块逐包 bind 挂载（挂载后读到的即本模块自身输出） */
    private fun isSelfMounted(pkg: String): Boolean =
        Shell.cmd("grep -q '$MODULE_ID/payload/uxicons/$pkg ' /proc/1/mountinfo && echo YES").exec()
            .out.any { it.contains("YES") }

    /** 列出一个 root 目录下的文件名（仅一层，不含子目录） */
    private fun readRootDirFiles(dir: String): List<String> {
        val r = Shell.cmd("find '$dir' -maxdepth 1 -type f 2>/dev/null").exec()
        return r.out.map { it.trim() }.filter { it.isNotEmpty() }.map { it.substringAfterLast('/') }
    }

    /**
     * 合并策略（保留官方、只改暗色）：
     * 1) 官方全量素材作基底；
     * 2) 我们生成的暗色件（rec_night/recbg/monochrome 各档）始终覆盖官方同名件；
     * 3) 浅色件只在 customDay 非空时才会出现在 generated 里，此时强制覆盖官方同名件——
     *    否则有官方下发的包会保留官方浅色素材、用户上传的图完全不生效；
     * 4) 其余官方件（peb/outline/快捷方式等）原样保留。
     */
    private fun mergePreserveOfficial(
        official: List<Pair<String, ByteArray>>,
        generated: List<Pair<String, ByteArray>>,
        includeLight: Boolean,
    ): List<Pair<String, ByteArray>> {
        val merged = LinkedHashMap<String, ByteArray>()
        for ((n, b) in official) merged[n] = b
        for ((n, b) in generated) {
            val isDark = DARK_OVERRIDE_PREFIXES.any { n.startsWith(it) }
            val isLight = includeLight && n in LIGHT_GENERATED_NAMES
            if (isDark || isLight || !merged.containsKey(n)) merged[n] = b
        }
        return merged.entries.map { it.key to it.value }
    }

    /** 以 root 拉取一个系统路径文件到 App 内存：cp 到 cacheDir 再读，避免二进制走 stdout 被破坏 */
    private fun readRootFileBytes(context: Context, path: String): ByteArray? {
        val ok = Shell.cmd("[ -s '$path' ] && echo OK").exec().out.any { it.contains("OK") }
        if (!ok) return null
        val tmp = context.cacheDir.resolve("artprobe_${path.hashCode()}.png")
        Shell.cmd(
            "cp '$path' ${tmp.absolutePath} 2>/dev/null",
            "chmod 644 ${tmp.absolutePath} 2>/dev/null",
        ).exec()
        val bytes = runCatching { if (tmp.length() > 0) tmp.readBytes() else null }.getOrNull()
        runCatching { tmp.delete() }
        return bytes
    }

    /** 把全套素材写入模块双路径（主题侧 + uxicons 侧），写入前先清理历史遗留浅色件 */
    private fun pushAssetFiles(
        context: Context,
        pkg: String,
        files: List<Pair<String, ByteArray>>,
        darkEnabled: Boolean = true,
    ) {
        ensureSkeleton()
        val tmp = context.cacheDir.resolve("push_$pkg").apply { mkdirs() }
        for ((name, bytes) in files) {
            tmp.resolve(name).writeBytes(bytes)
        }
        val label = resolveSelinuxLabel()
        val keep = files.map { it.first }.toSet()
        // uxicons 侧（逐包挂载源）
        val uxDst = "$PKGS_SRC/$pkg"
        Shell.cmd("mkdir -p $uxDst").exec()
        purgeStaleLightFiles(uxDst, keep)
        if (!darkEnabled) purgeStaleDarkFiles(uxDst, keep)
        for ((name, _) in files) {
            val remote = "$uxDst/$name"
            Shell.cmd(
                "cp ${tmp.resolve(name).absolutePath} $remote",
                "chmod 644 $remote",
                "chown 0:0 $remote",
                "chcon $label $remote",
            ).exec()
        }
        Shell.cmd("chcon $label $uxDst").exec()
        // 主题侧（桌面主读取路径）
        if (ensureThemeBase()) {
            val thDst = "$THEME_HDPI/$pkg"
            Shell.cmd("mkdir -p $thDst").exec()
            purgeStaleLightFiles(thDst, keep)
            if (!darkEnabled) purgeStaleDarkFiles(thDst, keep)
            for ((name, _) in files) {
                val remote = "$thDst/$name"
                Shell.cmd(
                    "cp ${tmp.resolve(name).absolutePath} $remote",
                    "chmod 644 $remote",
                    "chown 0:0 $remote",
                    "chcon $LABEL_THEME $remote",
                ).exec()
            }
            Shell.cmd("chcon $LABEL_THEME $thDst").exec()
        }
        /*
         * 镜像写到「另一个图标模块」的主题目录里。
         *
         * 实测：本机同时装了 DarIconComplete，它会周期性把自己那份 my_product/media/theme 重新 bind 到
         * /my_product/media/theme 的最上层，桌面只读最上层 —— 我们的 day/recfg 就被盖住了
         * （暗色走逐包挂载所以看起来正常，亮色永远读不到）。
         * 把同一批文件镜像过去，无论哪一层在上面，桌面读到的都是我们的素材。
         */
        val sisterTheme = "$SISTER_MODULE_DIR/my_product/media/theme"
        if (dirExists(sisterTheme)) {
            val sisDst = "$sisterTheme/uxicons/hdpi/$pkg"
            Shell.cmd("mkdir -p $sisDst").exec()
            for ((name, _) in files) {
                val remote = "$sisDst/$name"
                Shell.cmd(
                    "cp ${tmp.resolve(name).absolutePath} $remote",
                    "chmod 644 $remote",
                    "chown 0:0 $remote",
                    "chcon u:object_r:system_file:s0 $remote",
                ).exec()
            }
            Shell.cmd("chcon -R u:object_r:system_file:s0 $sisDst 2>/dev/null").exec()
            appendAudit("mirrored to sister module: $pkg (${files.size} files)")
        }
        // 兜底归一：不管走哪条分支，落盘后目录必须是 root:root 755，否则桌面读不到
        normalizeAssetDir("$THEME_HDPI/$pkg", LABEL_THEME)
        normalizeAssetDir("$PKGS_SRC/$pkg", LABEL_OFFICIAL)
        tmp.deleteRecursively()
    }

    /**
     * 清理本次没写的暗色件（rec_night_* / recbg_* / monochrome_*）。
     * 只在用户把暗色方案选成「原图标」时调用：此时本次不产出暗色件，
     * 若不清掉旧文件，桌面暗色仍读老的覆盖件，等于点了没反应。
     * keep 里有的（官方透传件）不动。
     */
    private fun purgeStaleDarkFiles(dir: String, keep: Set<String>) {
        val stale = readRootDirFiles(dir).filter { n ->
            DARK_OVERRIDE_PREFIXES.any { n.startsWith(it) } && n !in keep
        }
        if (stale.isEmpty()) return
        Shell.cmd("rm -f ${stale.joinToString(" ") { "'$dir/$it'" }}").exec()
    }

    /**
     * 清理历史遗留的浅色件：只删 LIGHT_GENERATED_NAMES 范围内、且不在本次写入清单里的文件。
     * 必须删——浅色件改为"仅上传时生成"后，此前写下的 recfg/day/mat/nsd/art_off 若残留，
     * 桌面浅色仍会拿它们叠 recbg 合成，用户除了取消勾选就没有别的恢复手段。
     * 名单外的文件（官方件、伞姨件、peb/outline/快捷方式等）一律不动；目录本身也不能重建，
     * uxicons 侧是逐包 bind 挂载源，rm -rf 会让挂载指向已删除的 inode。
     */
    private fun purgeStaleLightFiles(dir: String, keep: Set<String>) {
        val stale = readRootDirFiles(dir).filter { it in LIGHT_GENERATED_NAMES && it !in keep }
        if (stale.isEmpty()) return
        Shell.cmd("rm -f ${stale.joinToString(" ") { "'$dir/$it'" }}").exec()
    }

    /** drawableMapping.xml 第三方包注册（幂等）：桌面靠它知道哪些包有主题图标 */
    private fun registerDrawableMapping(pkg: String) {
        val f = "$THEME_SRC/drawableMapping.xml"
        Shell.cmd(
            "grep -q '$pkg' $f || sed -i \"s|</packageInfo>|<item package=\\\"$pkg\\\"/></packageInfo>|\" $f",
            "chcon $LABEL_THEME $f",
        ).exec()
    }

    /** icon_version.xml 版本号 +1：素材变更信号，执行脚本据此决定是否清桌面缓存 */
    private fun bumpIconVersion() {
        val f = "$THEME_SRC/icon_version.xml"
        Shell.cmd(
            "V=\$(grep -o '<version>[0-9]*' $f | grep -o '[0-9]*' | head -1); V=\$((V+1)); " +
                "sed -i \"s#<version>[0-9]*</version>#<version>\$V</version>#\" $f",
            "chcon $LABEL_THEME $f",
        ).exec()
    }

    /** 读当前图标版本号（缓存元数据用） */
    fun readIconVersion(): Int {
        val r = Shell.cmd(
            "grep -o '<version>[0-9]*' $THEME_SRC/icon_version.xml 2>/dev/null | grep -o '[0-9]*' | head -1"
        ).exec()
        return r.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
    }

    /** .managed 台账登记（只增不删）：执行脚本据此识别"我们注入过的白名单条目" */
    private fun registerManaged(pkg: String) {
        Shell.cmd(
            "grep -qx '$pkg' $MODULE_DIR/.managed 2>/dev/null || echo '$pkg' >> $MODULE_DIR/.managed"
        ).exec()
    }

    /** run.log 审计行（只记录，不参与执行判断） */
    fun appendAudit(msg: String) {
        Shell.cmd(
            "echo \"\$(date '+%m-%d %H:%M:%S') [app] $msg\" >> $MODULE_DIR/run.log"
        ).exec()
    }

    /** 模块开机挂载是否已激活（= 是否已经刷入并重启过；主题整挂是必有项） */
    fun isModuleActive(): Boolean {
        val r = Shell.cmd("grep -c '$MODULE_ID/my_product' /proc/1/mountinfo").exec()
        return r.out.any { it.trim().toIntOrNull()?.let { n -> n > 0 } == true }
    }

    /** LSPosed 是否激活（lspd 数据目录存在 = 框架已安装运行） */
    fun isLsposedActive(): Boolean =
        Shell.cmd("[ -d /data/adb/lspd ] && echo OK").exec().out.any { it.contains("OK") }

    /** DarkIcon Auto 是否已刷入（模块目录含 module.prop = 已被 KSU 注册） */
    fun isModuleFlashed(): Boolean =
        Shell.cmd("[ -f $MODULE_DIR/module.prop ] && echo OK").exec().out.any { it.contains("OK") }

    /** 读取 run.log 全文（首页日志页渲染用） */
    fun readRunLog(): List<String> =
        Shell.cmd("cat $MODULE_DIR/run.log 2>/dev/null").exec().out

    /** 读取模块版本号（module.prop 的 version 字段，首页信息卡展示） */
    fun readModuleVersion(): String {
        val r = Shell.cmd("grep '^version=' $MODULE_DIR/module.prop 2>/dev/null | cut -d= -f2").exec()
        return r.out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "?"
    }

    /**
     * 清除数据（设置页“清除数据”作用域 B）：
     * 模块侧删光全部包素材 + 清空 .packages/.managed + 注销注册 + 版本 +1。
     * 脚本与主题基底保留（无需重刷）；运行时恢复（卸载/清白名单/重建缓存）由下次执行收敛。
     */
    fun wipeAll() {
        // 清单可能被清空过、素材目录却还在：取并集避免漏清（漏清 = 桌面继续显示我们的图标）
        val pkgs = (listPackages() + listAssetPackages()).distinct()
        // 逐包走 removePackage：有官方备份的先还原官方件，再去掉清单登记
        for (p in pkgs) removePackage(p)
        // .managed 故意保留：apply.sh 的 whitelistPurge 要靠它把白名单条目删掉
        Shell.cmd(": > $MODULE_DIR/.packages").exec()
        bumpIconVersion()
        appendAudit("wiped all data (${pkgs.size} pkgs)")
    }

    /**
     * 导出标准刷入包到 /sdcard：module.prop 位于 zip 根部、脚本 0755、条目正斜杠。
     * 用户需在 KSU 管理器内完整刷入（直写模块目录/手动触发均不被承认）。
     */
    fun exportZip(context: Context, outPath: String = "/sdcard/DarkIconAuto.zip"): Boolean {
        if (!ensureSkeleton()) return false
        regenerateMountScript(listPackages())
        // 运行时状态不入包（设备本地专属）
        val exclude = setOf("run.log", ".last_version", ".managed")
        val files = Shell.cmd(
            "cd $MODULE_DIR && find . -type f | sed 's|^\\./||'"
        ).exec().out.map { it.trim() }.filter { it.isNotEmpty() && it !in exclude }
        val dirs = Shell.cmd(
            "cd $MODULE_DIR && find . -type d | sed 's|^\\./||' | grep -v '^\\.$'"
        ).exec().out.map { it.trim() }.filter { it.isNotEmpty() }
        return ZipWriter.buildFromModule(MODULE_DIR, dirs, files, outPath, context.cacheDir)
    }

    /** 官方暗色图标背景实测为 #0F0F0F，统一按这个来 */
    private const val DARK_BG = 0xFF0F0F0F.toInt()

    // ───────────────── 导入 / 导出（备份恢复） ─────────────────

    /** 导出目录：Download（用户能在文件管理器里直接看到） */
    private const val EXPORT_DIR = "/sdcard/Download"

    private fun timestamp(): String {
        val f = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
        return f.format(java.util.Date())
    }

    /** 默认导出路径：/sdcard/Download/Reicon_数据_20261117_2250.zip */
    fun defaultExportPath(): String = "$EXPORT_DIR/Reicon_数据_${timestamp()}.zip"

    /**
     * 导入本软件导出的数据包（备份恢复）。
     *
     * 数据包结构就是我们自己的模块布局：`payload/theme/uxicons/hdpi/<pkg>/…` +
     * `payload/uxicons/<pkg>/…` + `.packages`。解压到应用缓存后整块拷进模块目录，
     * 再按目录名重建清单，最后 +1 图标版本（下次执行会重建桌面）。
     *
     * 返回导入的包数；-1 表示不是有效的 Reicon 数据包。
     */
    fun importData(
        context: Context,
        uri: android.net.Uri,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Int {
        if (!Shell.getShell().isRoot) return 0
        val tmp = java.io.File(context.cacheDir, "import_tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val staging = java.io.File(tmp, "payload")
        var entries = 0
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                java.util.zip.ZipInputStream(ins.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val name = e.name
                        if (!e.isDirectory && (name.startsWith("payload/") || name == ".packages")) {
                            val out = java.io.File(tmp, name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { os -> zis.copyTo(os) }
                            entries++
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
        }.onFailure { appendAudit("import fail: ${it.message}"); tmp.deleteRecursively(); return 0 }
        if (!staging.isDirectory) {
            appendAudit("import fail: payload missing")
            tmp.deleteRecursively()
            return -1
        }
        // 拷进模块（先清空旧素材再放，避免残留旧包）
        Shell.cmd(
            "mkdir -p $MODULE_DIR/payload",
            "rm -rf $MODULE_DIR/payload/theme $MODULE_DIR/payload/uxicons",
            "cp -a '${staging.absolutePath}/.' $MODULE_DIR/payload/ 2>/dev/null",
            "chmod -R 755 $MODULE_DIR/payload 2>/dev/null",
        ).exec()
        normalizeAssetDir("$THEME_HDPI", LABEL_THEME)
        normalizeAssetDir("$PKGS_SRC", LABEL_OFFICIAL)
        // 按目录名重建清单
        val pkgs = listAssetPackages()
        Shell.cmd(": > $MODULE_DIR/.packages").exec()
        for (p in pkgs) registerPackageList(p)
        for (p in pkgs) registerManaged(p)
        bumpIconVersion()
        tmp.deleteRecursively()
        appendAudit("import done: ${pkgs.size} pkgs, $entries entries")
        return pkgs.size
    }

    /**
     * 导入 @天伞桜 模块的数据（DarkIconComplete / 欧加暗色图标补全）。
     *
     * 它只有暗色件，所以这里**只取暗色三层**：`rec_night*` / `recbg*` / `monochrome*`（含各自 1x2/2x1/2x2 变体），
     * 亮色件（day/mat/nsd/recfg）不碰 —— 那些要由本软件按自己的白底方案生成。
     * 包的目录可能出现在 `my_product/media/theme/uxicons/hdpi/<pkg>/` 或 `data/oplus/uxicons/<pkg>/`。
     *
     * 返回导入的包数；-1 表示不像伞姨模块的数据包。
     */
    fun importSisterModule(
        context: Context,
        uri: android.net.Uri,
        installedOnly: Boolean = false,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Int {
        if (!Shell.getShell().isRoot) return 0
        val tmp = java.io.File(context.cacheDir, "sister_tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val darkNames = listOf("rec_night", "recbg", "monochrome")
        fun isDarkAsset(name: String): Boolean {
            val base = name.substringAfterLast('/')
            if (!base.endsWith(".png")) return false
            return darkNames.any { base.startsWith(it) }
        }
        val found = linkedMapOf<String, MutableList<Pair<String, ByteArray>>>()
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                java.util.zip.ZipInputStream(ins.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val name = e.name
                        /*
                         * 两道目录形态都要支持：
                         *   data/oplus/uxicons/<pkg>/<file>            ← @天伞桜 模块 v17 实际就是这个
                         *   my_product/media/theme/uxicons/hdpi/<pkg>/<file>
                         * 所以 uxicons 后面那段可能是 hdpi（要跳过），也可能直接就是包名。
                         */
                        val segs = name.split("/")
                        val idx = segs.indexOfFirst { it.equals("uxicons", true) }
                        if (!e.isDirectory && idx >= 0 && segs.size >= idx + 3) {
                            val nextIsHdpi = segs.getOrNull(idx + 1)?.equals("hdpi", true) == true
                            val pkg = segs.getOrNull(if (nextIsHdpi) idx + 2 else idx + 1).orEmpty()
                            val file = segs.last()
                            if (pkg.contains('.') && isDarkAsset(file)) {
                                val bytes = zis.readBytes()
                                found.getOrPut(pkg) { mutableListOf() } += file to bytes
                            }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
        }.onFailure { appendAudit("sister import fail: ${it.message}") }
        if (found.isEmpty()) {
            tmp.deleteRecursively()
            return -1
        }
        // 先把清单里历史误导入的「没有桌面图标」的包清出去，避免数字继续对不上
        pruneNonLauncherPackages()
        // 「只导入已安装的」：口径与「应用列表」一致 —— 只看有桌面启动图标的包
        val installedSet = if (installedOnly) launcherPackages() else emptySet()
        // 官方 / 系统已自带暗色的（微信、淘宝、拼多多、Chrome、Google 全家桶、系统应用…）
        // 一律剔除：重复写没意义，还会把首页「已适配」算两遍
        val officialSet = runCatching { autoAdaptedPackages() }.getOrDefault(emptySet())
        val afterInstalled = if (installedOnly) found.filterKeys { it in installedSet } else found
        val targets = afterInstalled.filterKeys { it !in officialSet }
        lastSkippedOfficial = afterInstalled.size - targets.size
        if (targets.isEmpty()) {
            tmp.deleteRecursively()
            appendAudit(
                "sister import: nothing to do (found=${found.size}, skippedOfficial=$lastSkippedOfficial, installedOnly=$installedOnly)"
            )
            return 0
        }
        var done = 0
        for ((pkg, files) in targets) {
            val dir = java.io.File(tmp, pkg)
            dir.mkdirs()
            for ((n, b) in files) java.io.File(dir, n).writeBytes(b)
            Shell.cmd(
                "mkdir -p $THEME_HDPI/$pkg",
                "cp -a '${dir.absolutePath}/.' $THEME_HDPI/$pkg/ 2>/dev/null",
                "chmod 644 $THEME_HDPI/$pkg/*.png 2>/dev/null",
                "chown 0:0 $THEME_HDPI/$pkg/*.png 2>/dev/null",
                "chcon $LABEL_THEME $THEME_HDPI/$pkg/*.png 2>/dev/null",
                "mkdir -p $PKGS_SRC/$pkg",
                "cp -a '${dir.absolutePath}/.' $PKGS_SRC/$pkg/ 2>/dev/null",
                "chcon -R $LABEL_OFFICIAL $PKGS_SRC/$pkg 2>/dev/null",
            ).exec()
            normalizeAssetDir("$THEME_HDPI/$pkg", LABEL_THEME)
            normalizeAssetDir("$PKGS_SRC/$pkg", LABEL_OFFICIAL)
            registerPackageList(pkg)
            registerManaged(pkg)
            done++
            onProgress?.invoke(done, targets.size)
        }
        bumpIconVersion()
        tmp.deleteRecursively()
        appendAudit("sister import done: $done/${found.size} pkgs (dark only, installedOnly=$installedOnly)")
        return done
    }

    /** 清空运行日志（日志页的删除按钮） */
    fun clearRunLog(): Boolean {
        if (!Shell.getShell().isRoot) return false
        Shell.cmd(": > $MODULE_DIR/run.log").exec()
        return true
    }

    /**
     * 导出图标包（独立内容模块，一次刷入，id=DarkIconPack）：
     * 只含本软件适配过的素材（主题侧 + 逐包 uxicons）+ 自带挂载脚本，刷入后重启全部生效。
     */
     fun exportIconPack(
         context: Context,
         outPath: String = defaultExportPath(),
         onProgress: ((Int, Int) -> Unit)? = null,
     ): Boolean {
         if (!ensureSkeleton()) return false
         val stage = "$MODULE_DIR/.pack"
         val prop = """
             id=DarkIconPack
             name=Dark Icon Pack
             version=1.0.0
             versionCode=100
             author=Carrot
             description=Dark icon asset pack generated by DarkIconGen
         """.trimIndent()
         // 自包含挂载脚本（伞姨式）：主题整挂 + 逐包挂载（跳过伞姨已覆盖）
         val mountLoop = """
             S=${'$'}MODDIR/payload/uxicons
             SIS=$SISTER_MODULE_DIR/data/oplus/uxicons
             while IFS= read -r p; do
               [ -z "${'$'}p" ] && continue
               [ -d "${'$'}S/${'$'}p" ] || continue
               [ -d "${'$'}SIS/${'$'}p" ] && continue
               [ -d "$UXICONS_DST/${'$'}p" ] || { mkdir -p "$UXICONS_DST/${'$'}p"; chcon $LABEL_OFFICIAL "$UXICONS_DST/${'$'}p"; }
               grep -q " $UXICONS_DST/${'$'}p " /proc/1/mountinfo 2>/dev/null || nsenter -t 1 -m -- mount --bind "${'$'}S/${'$'}p" "$UXICONS_DST/${'$'}p" 2>/dev/null || mount --bind "${'$'}S/${'$'}p" "$UXICONS_DST/${'$'}p"
             done < ${'$'}MODDIR/.packages 2>/dev/null
         """.trimIndent()
         val pfs = """
             #!/system/bin/sh
             MODDIR=${'$'}{0%/*}
             grep -q " $THEME_DST " /proc/1/mountinfo 2>/dev/null || nsenter -t 1 -m -- mount --bind ${'$'}MODDIR/payload/theme/ $THEME_DST/ 2>/dev/null || mount --bind ${'$'}MODDIR/payload/theme/ $THEME_DST/
         """.trimIndent() + "\n" + mountLoop + "\n"
         val svc = """
             #!/system/bin/sh
             sleep 40
             MODDIR=${'$'}{0%/*}
             grep -q " $THEME_DST " /proc/1/mountinfo 2>/dev/null || nsenter -t 1 -m -- mount --bind ${'$'}MODDIR/payload/theme/ $THEME_DST/ 2>/dev/null || mount --bind ${'$'}MODDIR/payload/theme/ $THEME_DST/
         """.trimIndent() + "\n" + mountLoop + """
             # 开机自愈：定点检查 4 次（不是轮询），修好一次就退出（与 service.sh 同一策略）
             (
               FIXED=0
               for spec in 45 45 60 60; do
                 sleep ${'$'}spec
                 if [ ! -f ${'$'}THEME_ROOT/.darkicon_owner ] && [ ${'$'}FIXED -eq 0 ]; then
                   if nsenter -t 1 -m -- mount --bind ${'$'}MODDIR/payload/theme/ ${'$'}THEME_ROOT/ 2>/dev/null; then
                     FIXED=1
                     clear_icon_db
                     am force-stop com.android.launcher
                     break
                   fi
                 fi
               done
             ) &
         """.trimIndent() + "\n"
         Shell.cmd(
             "rm -rf $stage",
             "mkdir -p $stage",
             "cat > $stage/module.prop <<'DIEOF'\n$prop\nDIEOF",
             "cat > $stage/post-fs-data.sh <<'DIEOF'\n$pfs" + "DIEOF",
             "cat > $stage/service.sh <<'DIEOF'\n$svc" + "DIEOF",
             "cp $MODULE_DIR/.packages $stage/.packages 2>/dev/null",
         ).exec()
         /*
          * 素材清单：**只导出本软件真正适配过的包**（.packages 里的那批）。
          * 之前是 find 整个 payload/theme —— 那里面还躺着我们从官方主题里拷来的几百个包的原样副本，
          * 所以"把适配全删了导出依然几千张图"。现在按包名逐个取主题侧 + 逐包 uxicons 的文件。
          */
         val pkgs = listPackages()
         if (pkgs.isEmpty()) {
             appendAudit("export: no adapted packages, abort")
             Shell.cmd("rm -rf $stage").exec()
             return false
         }
         val allFiles = linkedSetOf<String>()
         // 主题根目录下的公共文件（icon_version.xml / drawableMapping.xml 等，桌面要对账）
         Shell.cmd("find $THEME_SRC -maxdepth 1 -type f 2>/dev/null").exec().out
             .map { it.trim() }.filter { it.startsWith("$MODULE_DIR/") }
             .forEach { allFiles += it.removePrefix("$MODULE_DIR/") }
         for (p in pkgs) {
             for (sub in listOf("$THEME_SRC/uxicons/hdpi/$p", "$PKGS_SRC/$p")) {
                 Shell.cmd("find '$sub' -type f 2>/dev/null").exec().out
                     .map { it.trim() }.filter { it.startsWith("$MODULE_DIR/") }
                     .forEach { allFiles += it.removePrefix("$MODULE_DIR/") }
             }
         }
         if (allFiles.isEmpty()) {
             appendAudit("export: no asset files for ${pkgs.size} pkgs, abort")
             Shell.cmd("rm -rf $stage").exec()
             return false
         }
         // 目录清单：由文件路径反推每一级父目录（ZipWriter 需要）
         val dirs = allFiles
             .mapNotNull { it.substringBeforeLast('/', "") }
             .filter { it.isNotEmpty() }
             .flatMap { path -> path.split("/").runningReduce { acc, seg -> "$acc/$seg" } }
             .distinct()
         val files = allFiles.toList() + listOf("module.prop", "post-fs-data.sh", "service.sh", ".packages")
         val alias = mapOf(
             "module.prop" to "$stage/module.prop",
             "post-fs-data.sh" to "$stage/post-fs-data.sh",
             "service.sh" to "$stage/service.sh",
             ".packages" to "$stage/.packages",
         )
         val ok = ZipWriter.buildFromModule(
             MODULE_DIR, dirs, files, outPath, context.cacheDir, alias, onProgress,
         )
         Shell.cmd("rm -rf $stage").exec()
         return ok
     }
     
     /** 移除一个包（撤销，纯声明式）：**把官方原件还回去** + 移出 .packages（.managed 保留）+
     * 删注册项 + 版本+1 + 审计。运行时收敛（umount/清白名单/缓存重建）由执行脚本处理。
     *
     * 关键：主题目录是整挂的，直接把包目录删掉 = 官方图标在桌面上跟着消失（拼多多就是这么丢的）。
     * 所以有备份就把备份原样拷回去（目录保留），没有备份才删目录。
     */
    fun removePackage(pkg: String) {
        val base = "$OFFICIAL_BACKUP/$pkg"
        val hasTheme = dirExists("$base/theme") && readRootDirFiles("$base/theme").isNotEmpty()
        // 主题侧
        if (hasTheme) {
            Shell.cmd(
                "mkdir -p $THEME_HDPI/$pkg",
                "find $THEME_HDPI/$pkg -mindepth 1 -delete 2>/dev/null",
                "cp -a $base/theme/. $THEME_HDPI/$pkg/ 2>/dev/null",
                "chcon $LABEL_THEME $THEME_HDPI/$pkg 2>/dev/null",
            ).exec()
            appendAudit("restore official $pkg (theme, from backup)")
        } else {
            Shell.cmd("rm -rf $THEME_HDPI/$pkg").exec()
        }
        // 伞姨模块那份镜像也要还原，否则它挂在上层时我们撤不干净
        val sisTheme = "$SISTER_MODULE_DIR/my_product/media/theme"
        if (dirExists(sisTheme)) {
            val sisHdpi = "$sisTheme/uxicons/hdpi/$pkg"
            if (hasTheme) {
                Shell.cmd(
                    "mkdir -p $sisHdpi",
                    "find $sisHdpi -mindepth 1 -delete 2>/dev/null",
                    "cp -a $base/theme/. $sisHdpi/ 2>/dev/null",
                    "chcon -R u:object_r:system_file:s0 $sisHdpi 2>/dev/null",
                ).exec()
            } else {
                Shell.cmd("rm -rf $sisHdpi").exec()
            }
        }
        // 逐包侧：这只是我们自己的挂载源，还原官方件没有意义（官方原件在系统目录里），
        // 一律删目录 —— 目录没了，apply.sh 才会把 /data/oplus/uxicons/<pkg> 上的 bind 挂载卸掉。
        Shell.cmd("rm -rf $PKGS_SRC/$pkg").exec()
        Shell.cmd(
            "sed -i \"/^$pkg\$/d\" $MODULE_DIR/.packages 2>/dev/null",
        ).exec()
        unregisterDrawableMapping(pkg)
        bumpIconVersion()
        appendAudit("removed $pkg")
    }

    /** drawableMapping.xml 移除注册项（撤销链路） */
    private fun unregisterDrawableMapping(pkg: String) {
        val f = "$THEME_SRC/drawableMapping.xml"
        Shell.cmd(
            "sed -i \"/<item package=\\\"$pkg\\\"\\/>/d\" $f 2>/dev/null",
            "chcon $LABEL_THEME $f 2>/dev/null",
        ).exec()
    }

    private fun dirExists(path: String): Boolean =
        Shell.cmd("[ -d $path ] && echo EXISTS").exec().out.any { it.contains("EXISTS") }

    /** 探测设备支持的目标标签：首选官方 uxicons_data_file，不支持则回退 system_file */
    private fun resolveSelinuxLabel(): String {
        selinuxLabel?.let { return it }
        val probe = Shell.cmd(
            "touch /data/local/tmp/_dilabel_probe",
            "chcon $LABEL_OFFICIAL /data/local/tmp/_dilabel_probe 2>/dev/null && echo OK",
            "rm -f /data/local/tmp/_dilabel_probe",
        ).exec()
        val label = if (probe.out.any { it.contains("OK") }) LABEL_OFFICIAL else LABEL_FALLBACK
        selinuxLabel = label
        return label
    }

    /**
     * 生成一个包的素材件。
     *
     * 暗色件（rec_night / recbg / monochrome 及各档变体）始终生成。
     * 浅色件（recfg / art_off / day / mat / nsd 及各档变体）**仅在 customDay 非空时**生成：
     * 未上传自定义浅色图标就一件都不写，桌面缺件即回退到应用自身图标，浅色保持原样
     * （依据与实测见 LIGHT_GENERATED_NAMES 注释）。
     */
    private fun buildPackagePngs(
        fg240: Bitmap,
        mono240: Bitmap,
        bgColor: Int,
        customDay: Bitmap?,
        darkEnabled: Boolean = true,
    ): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()

        fun png(b: Bitmap): ByteArray {
            val bos = ByteArrayOutputStream()
            android.graphics.Bitmap.CompressFormat.PNG.let { b.compress(it, 100, bos) }
            return bos.toByteArray()
        }

        /*
         * 暗色背景固定用近黑（官方素材实测 nsd.png 就是 #0F0F0F）。
         * 以前用品牌色当暗色底，桌面暗色图标会变成"橙底橙字"，与官方观感完全不同。
         */
        fun solid(w: Int, h: Int): ByteArray =
            png(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(DARK_BG) })

        fun canvas(src: Bitmap, w: Int, h: Int, logo: Int): ByteArray =
            png(com.darkicongen.app.gen.ForegroundProcessor.forCanvas(src, w, h, logo))

        if (darkEnabled) {
            // 暗色件：240 方档
            out.add("rec_night.png" to png(fg240))
            out.add("monochrome.png" to png(mono240))
            out.add("recbg.png" to solid(240, 240))

            // 暗色件：2x2（704×704，logo 高 500）
            out.add("rec_night_2x2.png" to canvas(fg240, 704, 704, 500))
            out.add("monochrome_2x2.png" to canvas(mono240, 704, 704, 500))
            out.add("recbg_2x2.png" to solid(704, 704))

            // 暗色件：1x2（240×820）/ 2x1（820×240），logo 高 170
            out.add("rec_night_1x2.png" to canvas(fg240, 240, 820, 170))
            out.add("rec_night_2x1.png" to canvas(fg240, 820, 240, 170))
            out.add("monochrome_1x2.png" to canvas(mono240, 240, 820, 170))
            out.add("monochrome_2x1.png" to canvas(mono240, 820, 240, 170))
            out.add("recbg_1x2.png" to solid(240, 820))
            out.add("recbg_2x1.png" to solid(820, 240))
        }

        val day = customDay ?: return out

        /*
         * 浅色件：源图为方形且小于 240 时按原生尺寸写，不放大——少一次重采样更锐利。
         * 真机实测（com.cn21.ecloud，源图 144²、桌面渲染 168²）：144→168 比 144→240→168
         * 边缘锐度 lap 高 7.0%（6.020 vs 5.626），且渲染尺寸/满版 bbox/不透明像素数逐项一致。
         */
        val full240 = if (day.width == 240 && day.height == 240) day else Bitmap.createScaledBitmap(day, 240, 240, true)
        val fullSquare = if (day.width == day.height && day.width < 240) day else full240
        out.add("mat.png" to png(fullSquare))
        out.add("nsd.png" to png(fullSquare))
        out.add("day.png" to png(fullSquare))
        out.add("recfg.png" to png(fullSquare))
        out.add("art_off.png" to png(Bitmap.createScaledBitmap(fullSquare, 200, 200, true)))
        val mat2 = Bitmap.createScaledBitmap(day, 704, 704, true)
        out.add("mat_2x2.png" to png(mat2))
        out.add("recfg_2x2.png" to png(mat2))
        val recfg12 = Bitmap.createBitmap(240, 820, Bitmap.Config.ARGB_8888)
            .also { Canvas(it).drawBitmap(full240, 0f, (820 - 240) / 2f, null) }
        val recfg21 = Bitmap.createBitmap(820, 240, Bitmap.Config.ARGB_8888)
            .also { Canvas(it).drawBitmap(full240, (820 - 240) / 2f, 0f, null) }
        out.add("recfg_1x2.png" to png(recfg12))
        out.add("recfg_2x1.png" to png(recfg21))
        return out
    }

    // ───────────────── 系统主题「底层视图」（绕过我们的整目录挂载） ─────────────────

    /**
     * 把主题整挂全部卸掉，露出系统分区里真正的官方主题目录。
     *
     * 必须用 `umount -l` + `nsenter -t 1 -m`：挂载是在 init 命名空间里做的，
     * 而且桌面进程正在用这些文件，普通 umount 会 "target is busy"。lazy umount 会立刻
     * 把挂载点摘掉（资源延后释放），路径马上就指向底层分区。
     */
    private fun unmountThemeAll() {
        Shell.cmd(
            "for i in 1 2 3 4 5 6; do nsenter -t 1 -m -- umount -l $THEME_DST 2>/dev/null || " +
                "umount -l $THEME_DST 2>/dev/null; done",
        ).exec()
    }

    /** 重新挂回主题（挂载点没被占就补一层） */
    private fun remountTheme() {
        Shell.cmd(
            "if ! grep -q \" $THEME_DST \" /proc/1/mountinfo 2>/dev/null; then " +
                "nsenter -t 1 -m -- mount --bind $THEME_SRC/ $THEME_DST/ 2>/dev/null || " +
                "mount --bind $THEME_SRC/ $THEME_DST/ 2>/dev/null; fi",
        ).exec()
    }

    /** 系统分区里官方主题的包目录（调用前必须已经 unmountThemeAll） */
    private fun systemThemePkgs(): List<String> =
        Shell.cmd("ls $THEME_DST/uxicons/hdpi 2>/dev/null").exec().out
            .map { it.trim() }.filter { it.isNotEmpty() }

    /** `.managed` 台账（历史管理过的全部包，只增不删） */
    fun managedPackages(): List<String> =
        Shell.cmd("cat $MODULE_DIR/.managed 2>/dev/null").exec().out
            .map { it.trim() }.filter { it.isNotEmpty() }

    // ───────────────── 停用 / 重新启用 ─────────────────

    /**
     * 停用模块并立刻把图标恢复原厂 —— 不需要重启手机。
     *
     * 做四件事：
     * 1. 卸掉我们所有的挂载（主题整挂 + 逐包），桌面立刻读回系统分区里的官方素材；
     * 2. 把模块里被改过的素材还原成官方件（有备份的按备份还原）；
     * 3. 清掉我们注入的白名单条目（否则桌面还在走 morph 路径）；
     * 4. 清桌面三层缓存并重启桌面 —— 这一步不做的话，即使素材回官方了，图标还是旧图。
     * 最后写一个 `disable` 标记，KernelSU 下次开机就不会再加载本模块。
     * 返回是否成功。
     */
    fun deactivateAndRestore(context: Context): Boolean {
        if (!Shell.getShell().isRoot) return false
        // 1) 卸载挂载
        unmountThemeAll()
        for (p in (listPackages() + listAssetPackages()).distinct()) {
            Shell.cmd(
                "umount $UXICONS_DST/$p 2>/dev/null || nsenter -t 1 -m -- umount $UXICONS_DST/$p 2>/dev/null",
            ).exec()
        }
        // 2) 素材还原
        for (p in (listPackages() + listAssetPackages()).distinct()) removePackage(p)
        // 3) 清残留白名单条目（逐包精确删）
        val w = "$UXICONS_DST/morph_icons_whitelist.xml"
        for (p in managedPackages().distinct()) {
            Shell.cmd("sed -i \"/<package_name>$p<morph>1<\\/morph><\\/package_name>/d\" '$w' 2>/dev/null").exec()
        }
        // 4) 桌面缓存 + 重启桌面
        clearDesktopItemIcons(context)
        // 5) 下次开机不再加载
        Shell.cmd("touch $MODULE_DIR/disable").exec()
        appendAudit("deactivated + restored official icons")
        return true
    }

    /** 重新启用模块（删掉 disable 标记），挂载要等下次执行或重启才恢复 */
    fun reactivate(): Boolean {
        if (!Shell.getShell().isRoot) return false
        Shell.cmd("rm -f $MODULE_DIR/disable").exec()
        appendAudit("reactivated module")
        return true
    }

    /** 模块当前是否被停用（KernelSU 的 disable 标记） */
    fun isModuleDisabled(): Boolean =
        Shell.cmd("[ -f $MODULE_DIR/disable ] && echo YES").exec().out.any { it.contains("YES") }

    // ───────────────── 桌面条目图标缓存（launcher.db） ─────────────────

    /**
     * 清掉桌面 `launcher.db → singledesktopitems.icon` 里缓存的**成品图标位图**。
     *
     * 实测：`app_icons.db` 只是"图标源"缓存，桌面最终画出来的那张图还存在 launcher.db 的 item blob 里。
     * 只清 app_icons.db 时，取消勾选/改素材后桌面依旧拿旧 blob 渲染 ——
     * 表现就是"素材回官方了、挂载也卸了、缓存也清了，图标还是我们那张"。
     *
     * 做法：停桌面 → 把 db 拷到应用私有目录 → SQLite 里只把 icon 列置 NULL（布局不动）→ 拷回 → 拉起桌面。
     * 返回被清掉图标缓存的条目数。
     */
    fun clearDesktopItemIcons(context: Context): Int {
        val db = "/data/user_de/0/com.android.launcher/databases/launcher.db"
        val tmp = java.io.File(context.cacheDir, "launcher_copy.db")
        return runCatching {
            Shell.cmd("am force-stop $LAUNCHER_PKG").exec()
            // 桌面还有一层文件缓存：/data/user_de/0/com.android.launcher/cache/。
            // 只清 app_icons.db + item blob 时，亮色图标仍会从这里读到旧图（实测差 27px）。
            Shell.cmd("rm -rf /data/user_de/0/com.android.launcher/cache/* 2>/dev/null").exec()
            /*
             * 必须走 init 的 mount namespace：App 进程（以及它的 su 子进程）活在自己的 mount ns 里，
             * `/data/user_de/0/com.android.launcher/databases/launcher.db` 在那里根本不存在
             * （cp 直接报 "No such file or directory"）；adb shell 是全局 ns 所以看得见。
             * 读写都用 nsenter 包一层脚本，避免多层引号。
             */
            val readSh = java.io.File(context.cacheDir, "di_read_db.sh")
            readSh.writeText(
                // chmod 666 必须加：脚本以 root 执行，副本属主是 root，App 进程（uid 不同）否则打不开可写
                "#!/system/bin/sh\ncp -f '$db' '${tmp.absolutePath}' && chmod 666 '${tmp.absolutePath}' && echo COPY_OK\n"
            )
            val r1 = Shell.cmd("nsenter -t 1 -m -- /system/bin/sh '${readSh.absolutePath}' 2>&1").exec()
            appendAudit("clear items: copy=${tmp.exists()} out=${r1.out.joinToString("|").take(120)}")
            if (!tmp.exists()) {
                return@runCatching 0
            }
            var rows = 0
            val err = runCatching {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    tmp.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
                ).use { d ->
                    rows = d.rawQuery("SELECT count(*) FROM singledesktopitems", null).use {
                        if (it.moveToFirst()) it.getInt(0) else 0
                    }
                    // 只清成品图，不动布局；modified +1 让桌面认为条目变过、必须重新取图标
                    d.execSQL("UPDATE singledesktopitems SET icon=NULL WHERE icon IS NOT NULL")
                    d.execSQL("UPDATE singledesktopitems SET modified = modified + 1")
                }
            }
            if (err.isFailure) {
                appendAudit("clear items: sqlite fail ${err.exceptionOrNull()?.message}")
                Shell.cmd("rm -f '${tmp.absolutePath}'").exec()
                return@runCatching 0
            }
            /*
             * 写回用 `cat > db`：目标已存在时是原地覆写，inode / 属主 / 权限 / SELinux label 全保留。
             * 同样必须在 init ns 里做。
             *
             * 注意：这里**不启动桌面**。调用方（applyNow / reloadAll）会在之后跑 apply.sh，
             * 由脚本 rm app_icons.db + force-stop 后让系统自己把桌面拉起来 —— 这样桌面才会完整重建图标。
             * 如果我们在这里 am start，桌面会在缓存尚未重建完时被提前拉起，又写回一轮旧图（表现为"只是闪一下"）。
             */
            val writeSh = java.io.File(context.cacheDir, "di_write_db.sh")
            writeSh.writeText(
                "#!/system/bin/sh\n" +
                    "cat '${tmp.absolutePath}' > '$db'\n" +
                    "rm -f '$db-journal' '$db-wal' '$db-shm'\n" +
                    "echo WRITE_OK\n"
            )
            Shell.cmd("nsenter -t 1 -m -- /system/bin/sh '${writeSh.absolutePath}' 2>&1").exec()
            Shell.cmd("rm -f '${tmp.absolutePath}' '${readSh.absolutePath}' '${writeSh.absolutePath}'").exec()
            appendAudit("cleared desktop item icons (rows=$rows)")
            rows
        }.getOrDefault(0)
    }

    /**
     * 修复历史上被删掉的官方图标（拼多多那种"官方重绘版没了"的补救）。
     *
     * 原理：桌面的主题目录是**整挂**的，所以模块 theme 目录里少了哪个包，系统分区里那个包的官方件就被"藏"住了。
     * 这里先临时卸掉整挂，让系统分区的 `/my_product/media/theme` 露出来，把缺的官方件按文件补回模块目录，
     * 顺手补一份备份，最后重新挂回去。返回修好的包数。
     */
    fun restoreOfficialIcons(): Int {
        if (!isModuleFlashed()) return 0
        val enabled = listPackages().toSet()
        // 1) 全部拆掉落出系统分区（lazy umount）
        unmountThemeAll()
        val sysHdpi = "$THEME_DST/uxicons/hdpi"
        val sysPkgs = systemThemePkgs()
        // 2) 系统里有、模块里没有的包 → 整包拷回（拼多多就是这种：早期取消勾选把目录删了）
        val modulePkgs = Shell.cmd("ls $THEME_HDPI 2>/dev/null").exec().out
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        var fixed = 0
        for (p in sysPkgs) {
            if (p in modulePkgs) continue
            val sysDir = "$sysHdpi/$p"
            val moduleDir = "$THEME_HDPI/$p"
            Shell.cmd(
                "mkdir -p '$moduleDir'",
                "cp -af '$sysDir'/. '$moduleDir'/ 2>/dev/null",
                "chcon -R $LABEL_THEME '$moduleDir' 2>/dev/null",
            ).exec()
            fixed++
            appendAudit("restored official $p from system (whole dir)")
        }
        // 3) 已取消勾选、但模块里还是我们图标的包 → 系统件整包覆盖
        for (p in managedPackages()) {
            if (p in enabled) continue
            val sysDir = "$sysHdpi/$p"
            if (readRootDirFiles(sysDir).isEmpty()) continue
            val moduleDir = "$THEME_HDPI/$p"
            Shell.cmd(
                "mkdir -p '$moduleDir'",
                "cp -af '$sysDir'/. '$moduleDir'/ 2>/dev/null",
                "chcon -R $LABEL_THEME '$moduleDir' 2>/dev/null",
            ).exec()
            fixed++
            appendAudit("restored official $p (unchecked, full overwrite)")
        }
        // 4) 给「系统里有官方件但没有备份」的包补一份备份（用底层原件，最干净）
        val backed = Shell.cmd("ls $OFFICIAL_BACKUP 2>/dev/null").exec().out
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        for (p in (enabled + managedPackages()).distinct()) {
            if (p in backed) continue
            val sysDir = "$sysHdpi/$p"
            if (readRootDirFiles(sysDir).isEmpty()) continue
            Shell.cmd(
                "mkdir -p '$OFFICIAL_BACKUP/$p/theme'",
                "cp -a '$sysDir'/. '$OFFICIAL_BACKUP/$p/theme'/ 2>/dev/null",
                "chcon -R $LABEL_THEME '$OFFICIAL_BACKUP/$p/theme' 2>/dev/null",
            ).exec()
        }
        // 5) 挂回去 + 版本 +1 让桌面重建
        remountTheme()
        bumpIconVersion()
        appendAudit("restore-official done fixed=$fixed system=${sysPkgs.size} module=${modulePkgs.size}")
        return fixed
    }

    // ───────────────────────── 立即执行（等价 KSU 播放键） ─────────────────────────

    /**
     * 白名单自检并补注入（App 侧）。
     *
     * 桌面只有在 `morph_icons_whitelist.xml` 里看到 `<package_name>pkg<morph>1</morph>` 时，
     * 才会去读 uxicons 素材；条目一丢，桌面就退回应用原图标——素材写得再对也白搭。
     * 系统云控会周期性整文件重写，所以每次「立即执行 / 全量重载」前都过一遍。
     * 返回本次补注入的条目数。
     */
    fun ensureWhitelist(pkgs: List<String> = listPackages()): Int {
        if (pkgs.isEmpty()) return 0
        val w = "$UXICONS_DST/morph_icons_whitelist.xml"
        var fixed = 0
        for (p in pkgs) {
            val has = Shell.cmd("grep -q '<package_name>$p<morph>' '$w' 2>/dev/null && echo YES").exec()
                .out.any { it.contains("YES") }
            if (has) continue
            Shell.cmd("sed -i \"s|</filter-conf>|<package_name>$p<morph>1</morph></package_name></filter-conf>|\" '$w' 2>/dev/null").exec()
            val ok = Shell.cmd("grep -q '<package_name>$p<morph>' '$w' 2>/dev/null && echo YES").exec()
                .out.any { it.contains("YES") }
            if (ok) {
                fixed++
                appendAudit("whitelist re-injected $p")
            } else {
                appendAudit("whitelist inject FAIL $p")
            }
        }
        return fixed
    }

    /** 一次「差量执行」的结果（首页提示用） */
    data class ApplyResult(
        val ok: Boolean,
        val added: String,
        val removed: String,
        val cache: String,
        val logTail: String,
    )

    /**
     * 立即执行 = 不用打开 KernelSU 管理器、直接跑模块的 `run_apply manual`。
     *
     * 行为与原「KSU 播放键」逐字一致：补挂载缺口、补白名单、版本变了才清桌面缓存、
     * 有改动才重启桌面。跑之前会按当前模板重写一遍脚本，所以老模块也能吃到新逻辑。
     */
    fun applyNow(context: Context): ApplyResult {
        if (!isModuleFlashed()) return ApplyResult(false, "", "", "", "模块未刷入")
        ensureSkeleton()
        // 模块若被停用（disable 标记），这里直接恢复启用：
        // 否则会出现"素材写进去了、也挂上了，但开机脚本不跑、状态半死不活"的怪现象。
        if (isModuleDisabled()) {
            Shell.cmd("rm -f $MODULE_DIR/disable").exec()
            appendAudit("module was disabled -> re-enabled by apply")
        }
        // 保证脚本是最新版本（含 reload / 新入口），无需重刷模块
        regenerateMountScript(listPackages())
        // 白名单先自检补注入：条目被云控冲掉时，桌面根本不会读我们的素材
        val wl = ensureWhitelist()

        /*
         * 关键：桌面只在 launcher.db 的 item blob 为空时才会重算图标。
         * 顺序必须是「先清 blob（停桌面 → 改库 → 不启动）→ 再跑 apply.sh（清 app_icons.db + force-stop）
         * → 系统自己把桌面拉起来」。缺任何一步图标都停在旧图（实测美团反复验证）。
         */
        clearDesktopItemIcons(context)
        /*
         * 「立即执行」= 差量：只处理清单里新增/移除的包，重复点不会重复"新增"。
         * 素材换了想强刷，用首页的「全量重载全部图标」（那条才是 reload）。
         */
        val r = Shell.cmd("MODDIR=$MODULE_DIR; . $MODULE_DIR/apply.sh; run_apply manual").exec()
        val log = r.out.joinToString("\n")
        fun field(prefix: String): String = log.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)?.trim() ?: ""
        appendAudit("apply-now via app (ok=${r.isSuccess}, wl+=$wl)")
        val removed = field("移除:")
        return ApplyResult(
            ok = r.isSuccess,
            added = field("新增:"),
            removed = removed,
            // kept 是脚本内部的哨兵值，别直接怼到用户脸上
            cache = field("缓存:").let { if (it == "kept") "无需清除" else it },
            logTail = if (log.length > 4000) log.takeLast(4000) else log,
        )
    }

    // ───────────────────────── 全量重载（force reload） ─────────────────────────

    /**
     * 全量重载结果（首页提示与日志展示用）。
     * restored = 从 App 缓存回填的包数；regenerated = 素材缺失、从已安装应用重新生成的包数；
     * kept = 素材本来就在、刻意不动的包数（保护用户手调过的素材）；skipped = 应用已卸载且无素材的包。
     */
    data class ReloadResult(
        val total: Int,
        val restored: Int,
        val regenerated: Int,
        val kept: Int,
        val skipped: Int,
        val failed: Int,
        val ok: Boolean,
        val logTail: String,
    )

    /**
     * 全量重载：把「已经装过所以不动」这条捷径整个绕开。
     *
     * 要解决的坑（用户实报）：Chrome 的暗色图标已经写好，后来不小心把模块目录里的素材删了；
     * 再执行模块时 apply.sh 走全是幂等分支——主题挂载"就绪"、逐包"挂载保持"、白名单"已在列"、
     * 图标版本没变 → "缓存保留"，于是桌面纹丝不动，看起来像完全没生效，而且没有任何办法补救。
     *
     * 逐包判定（顺序很重要，不能一上来就重新生成，会覆盖用户精心调过的素材）：
     * 1) App 内 generated/ 缓存存在 → 按缓存**原样回填**两条路径（主题侧 + 逐包 uxicons），
     *    这是"文件被删了"最准的恢复方式，恢复出来的就是当初写进去的那一份；
     * 2) 没缓存但素材核心件齐（rec_night/recbg/monochrome）→ 一个字节都不动，只让运行时重挂重读；
     * 3) 素材缺件/整个目录都没了 → 从已安装应用重新提取 + 重新生成（暗色自动推荐，亮色按全局偏好）；
     * 4) 应用已卸载且什么都没有 → 跳过，不污染台账。
     * 之后统一：icon_version +1 → 直接以 root 跑 `run_apply reload`（强制拆挂重挂 + 强制清
     * app_icons.db + 强制重启桌面）。App 侧执行不了就留下 `.force_reload`，KSU 播放键会接着做完。
     *
     * 包清单来源：`.packages` 与 `data/oplus/uxicons` 下的全部目录（后者是我们自己的素材目录，
     * 清单被误清空时靠它把包"捡回来"，否则空清单会让整个功能变成空转）。
     *
     * 只会：① 用 App 缓存回填他生成过的素材；② 素材齐全的保持不动；③ 其余跳过。
     * **不会**自动生成任何新素材 —— 生成只能由用户在详情页手动触发。
     */
    fun reloadAll(context: Context): ReloadResult {
        if (!isModuleFlashed()) {
            return ReloadResult(0, 0, 0, 0, 0, 0, false, "模块未刷入")
        }
        ensureSkeleton()
        val pkgs = (listPackages() + listAssetPackages()).distinct().sorted()
        // 老模块的 apply.sh 里没有 reload 分支：跑之前先按当前模板重写一遍（不必重刷模块）
        regenerateMountScript(pkgs)
        // 白名单同理：云控冲掉条目时全量重载也一样白做
        ensureWhitelist(pkgs)
        val installed = installedPackages()
        var restored = 0
        val regenerated = 0  // 已不再自动生成，恒为 0（仅兼容结果结构）
        var kept = 0
        var skipped = 0
        var failed = 0
        for (p in pkgs) {
            val cached = runCatching {
                com.darkicongen.app.gen.IconCache.readGenerated(context, p)
            }.getOrNull()
            val ok: Boolean
            val branch: String
            when {
                /*
                 * 只回填"他自己生成过的"素材（App 缓存里有记录的），绝不主动绘制。
                 * 以前这里还有两条 regeneratePackage 分支：素材缺失就从已安装应用重新生成，
                 * 结果导入伞姨那种"只有暗色件"的包一重载，就被自动补了一套亮色 —— 已删除。
                 */
                !cached.isNullOrEmpty() -> {
                    ok = restorePackage(context, p, cached)
                    branch = "restore"
                }
                hasCoreAssets(p) -> {
                    // 素材齐全：不重生成（避免覆盖手调素材），只登记台账，靠 reload 重挂生效
                    registerPackageList(p)
                    registerManaged(p)
                    ok = true
                    branch = "keep"
                }
                else -> {
                    // 没有缓存、也没有素材：什么都不做（不生成、不补全）
                    ok = true
                    branch = "skip"
                }
            }
            when {
                !ok -> failed++
                branch == "restore" -> restored++
                branch == "keep" -> kept++
                else -> skipped++
            }
        }
        bumpIconVersion()
        appendAudit(
            "reload-all pkgs=${pkgs.size} restore=$restored regen=$regenerated " +
                "keep=$kept skip=$skipped fail=$failed"
        )
        // 标记先落盘：即便下面这次直连执行被打断，KSU 播放键也能接着完成同一件事
        Shell.cmd("printf '1\\n' > $MODULE_DIR/.force_reload").exec()
        // 与 applyNow 同一顺序：先清 launcher.db 的 item blob（不启动桌面），再让 apply.sh 清 app_icons + force-stop
        clearDesktopItemIcons(context)
        val r = Shell.cmd("MODDIR=$MODULE_DIR; . $MODULE_DIR/apply.sh; run_apply reload").exec()
        val log = r.out.joinToString("\n")
        if (r.isSuccess) Shell.cmd("rm -f $MODULE_DIR/.force_reload").exec()
        // 同 applyNow：走脚本内的 rm app_icons.db + force-stop 即可，不再额外动 launcher.db
        return ReloadResult(
            total = pkgs.size,
            restored = restored,
            regenerated = regenerated,
            kept = kept,
            skipped = skipped,
            failed = failed,
            ok = r.isSuccess,
            logTail = if (log.length > 4000) log.takeLast(4000) else log,
        )
    }

    /** 模块自己的逐包素材目录名（= 我们写过的包；清单被清空时的兜底来源） */
    private fun listAssetPackages(): List<String> =
        Shell.cmd("ls $PKGS_SRC 2>/dev/null").exec().out
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith(".") }

    /** 已安装应用包名集合（一次 shell 取全量，避免逐包 pm 查询） */
    private fun installedPackages(): Set<String> =
        Shell.cmd("pm list packages 2>/dev/null | cut -d: -f2").exec().out
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * 「能在桌面/列表里看到的」应用包名集合 —— 口径与「应用」页完全一致。
     *
     * 注意 `cmd package query-activities --brief` 的真实输出长这样（**没有** `Package:` 前缀）：
     *
     *     80 activities found:
     *       Activity #0:
     *         com.android.contacts/.DialtactsActivityAlias
     *
     * 所以只能按 `包名/活动名` 取斜杠前那段。之前用 `grep 'Package:'` 一个都匹配不到，
     * 于是回落到了 `pm list packages`（整机 559 个包，含大量没有桌面图标的系统组件）——
     * 这就是"只导入已安装"却把 Chrome、系统组件也导进来、首页数字和列表对不上的原因。
     */
    private fun launcherPackages(): Set<String> {
        val out = Shell.cmd(
            "cmd package query-activities -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER --brief 2>/dev/null | " +
                "grep '/' | sed 's|/.*||' | sed 's/^ *//' | sort -u",
        ).exec().out.map { it.trim() }.filter { it.contains('.') }.toSet()
        return out.ifEmpty { emptySet() }
    }

    /**
     * ColorOS 官方**本来就适配过**的包（系统分区里自带 morph 素材）。
     *
     * 这些包（微信、淘宝、拼多多，以及大批系统应用）官方已经给了暗色图标，
     * 从伞姨模块导入只会重复写一遍，还会把首页「已适配」的数字算两遍。
     * 所以导入时剔除。
     *
     * 读法：临时卸掉主题整挂 → 露出系统分区的官方素材目录列一份包名 → 立刻挂回；结果缓存 24 小时。
     */
    fun officialAdaptedPackages(): Set<String> {
        val cache = "$MODULE_DIR/.official_pkgs"
        val fresh = Shell.cmd(
            "[ -f '$cache' ] && [ \$(( \$(date +%s) - \$(stat -c %Y '$cache' 2>/dev/null || echo 0) )) -lt 86400 ] && echo FRESH",
        ).exec().out.any { it.contains("FRESH") }
        if (fresh) {
            return Shell.cmd("cat '$cache' 2>/dev/null").exec().out
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        var list = emptyList<String>()
        runCatching {
            unmountThemeAll()
            list = Shell.cmd("ls $THEME_DST/uxicons/hdpi 2>/dev/null").exec().out
                .map { it.trim() }.filter { it.isNotEmpty() }
        }
        remountTheme()
        if (list.isNotEmpty()) {
            Shell.cmd("printf '%s\\n' " + list.joinToString(" ") + " > '$cache' 2>/dev/null").exec()
        }
        return list.toSet()
    }

    /** 系统 / 预装应用包名集合（备用，当前不参与导入过滤） */
    private fun systemPackages(): Set<String> =
        Shell.cmd("pm list packages -s 2>/dev/null | cut -d: -f2").exec().out
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * 清理清单里「没有桌面图标」的包（历史版本误导入留下的）。
     *
     * 只对**官方素材目录里也没有的包**动手：这类包桌面本来就不从 theme 目录读它们，
     * 删掉我们写的目录不会有副作用；有官方素材的包走 removePackage 会被还原成官方件，绝不硬删。
     * 返回清理条数。
     */
    fun pruneNonLauncherPackages(): Int {
        val launcher = launcherPackages()
        if (launcher.isEmpty()) return 0
        val official = autoAdaptedPackages()
        var n = 0
        for (p in listPackages()) {
            if (p in launcher) continue
            if (p in official) continue
            Shell.cmd(
                "rm -rf $THEME_HDPI/$p $PKGS_SRC/$p",
                "sed -i \"/^$p\$/d\" $MODULE_DIR/.packages 2>/dev/null",
            ).exec()
            n++
        }
        if (n > 0) {
            bumpIconVersion()
            appendAudit("pruned $n non-launcher packages from list")
        }
        return n
    }

    /**
     * 官方**自己就带暗色图标**的包 = 系统主题素材目录里已经有它的包。
     *
     * 只按素材目录判断。之前我还额外排除了「系统/预装应用」，结果把 Chrome、Google 全家桶
     * 这类其实**没有**官方适配的应用也挡掉了 —— 那条判据已撤回。
     */
    private fun autoAdaptedPackages(): Set<String> =
        runCatching { officialAdaptedPackages() }.getOrDefault(emptySet())

    /** 上一次导入伞姨数据时，因为"官方已适配"而跳过的包数 */
    @Volatile
    var lastSkippedOfficial: Int = 0
        private set

    /** 素材核心件是否齐（rec_night / recbg / monochrome，任一路径有就算有） */
    private fun hasCoreAssets(pkg: String): Boolean {
        val probe = Shell.cmd(
            "for d in $PKGS_SRC/$pkg $THEME_HDPI/$pkg; do " +
                "if [ -f \"\$d/rec_night.png\" ] && [ -f \"\$d/recbg.png\" ] && [ -f \"\$d/monochrome.png\" ]; then echo HIT; fi;" +
                " done",
        ).exec()
        return probe.out.any { it.contains("HIT") }
    }

    /**
     * 用 App 缓存把某包素材原样回填（素材被删的恢复路径）。
     * 缓存里存的是「上次实际写入的完整清单」（含透传的官方件），所以这里不再重新推导，
     * 只按 includeLight 决定浅色件是否压过官方同名件——否则官方下发过浅色素材的包会把我们的白底图顶掉。
     */
    private fun restorePackage(context: Context, pkg: String, cached: List<Pair<String, ByteArray>>): Boolean =
        runCatching {
            val official = readOfficialUxicons(context, pkg)
            val includeLight = cached.any { it.first in LIGHT_GENERATED_NAMES }
            val merged = mergePreserveOfficial(official, cached, includeLight)
            pushAssetFiles(context, pkg, merged)
            if (ensureThemeBase()) registerDrawableMapping(pkg)
            registerPackageList(pkg)
            registerManaged(pkg)
            true
        }.getOrDefault(false)

    /** 缓存缺失时的重生成：从已安装应用重新走一遍完整生成管线（亮色按全局偏好） */
    private fun regeneratePackage(context: Context, pkg: String): Boolean = runCatching {
        val ext = com.darkicongen.app.gen.IconExtractor.extract(context, pkg)
        com.darkicongen.app.gen.IconCache.storeOriginal(
            context, pkg, ext.full, ext.foreground, ext.background
        )
        val glyph = com.darkicongen.app.gen.ForegroundProcessor.extractForeground(ext.foreground, ext.full)
        val brand = com.darkicongen.app.gen.IconExtractor.dominantColor(ext.full)
        val style = com.darkicongen.app.gen.ForegroundProcessor.suggestStyle(glyph)
        val styled = com.darkicongen.app.gen.ForegroundProcessor.applyStyle(
            glyph, style, 240, brand, com.darkicongen.app.gen.RatioPrefs.currentDark(context)
        )
        // 原厂 monochrome 剪影优先，省掉自己膨胀描边
        val mono = ext.monochrome ?: com.darkicongen.app.gen.ForegroundProcessor.toMonochrome(
            glyph, 240, com.darkicongen.app.gen.RatioPrefs.currentDark(context)
        )
        val day = com.darkicongen.app.gen.LightModePrefs.dayIcon(context, glyph, ext)
        writePackageIcons(context, pkg, styled, mono, brand, style.name, day)
        true
    }.getOrDefault(false)

    /**
     * 单包「重建」：删掉素材缓存后按当前亮色偏好重新生成一次（预览页"重新生成亮色"用）。
     * 返回是否成功；成功后仍由调用方决定何时让桌面收敛（写版本+1 由 writePackageIcons 完成）。
     */
    fun rebuildPackage(context: Context, pkg: String): Boolean = regeneratePackage(context, pkg)
}

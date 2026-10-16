package com.codebeacon.widget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.RemoteViews
import com.codebeacon.widget.R
import com.codebeacon.widget.data.PickupCodeStore
import com.codebeacon.widget.listener.CodeListenerService
import com.codebeacon.widget.listener.SourceFilter

/**
 * 桌面小部件 Provider。
 *
 * ## ⚠️ 关于需求中「顺滑下移消失」的技术真相
 *
 * Android RemoteViews **不支持布局动画**（ViewAnimator / TransitionDrawable /
 * ObjectAnimator 全部不可用），这是跨进程渲染的硬限制 —— 小部件视图由系统
 * Launcher 进程渲染，不是本 App 进程。
 *
 * 所以能做到的是：
 *  ✅ 点击后**立即更新**为下一条数据（视觉上是内容切换）
 *  ✅ 配合 [WidgetAnimator] 做**整块淡出再淡入**（用系统动画资源，非布局动画）
 *  ✅ 归档页里提供**恢复入口**，误点可找回
 *
 * 做不到真正的「逐项滑动下出列表」—— 那是 ListView/RecyclerView 的效果，
 * 而 RemoteViews 对它们基本不支持（仅能用 RemoteViewsService 的 collection，
 * 属于另一套更复杂的机制，V1 不引入）。
 *
 * 这一点必须诚实告知用户，不能假装实现了。
 */
class CodeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            render(context, appWidgetManager, widgetId)
        }
    }

    /** 小部件被删除 */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // 无需清理，数据库是全局的
    }

    /** 用户在配置界面修改尺寸后重建 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        render(context, appWidgetManager, appWidgetId)
    }

    companion object {
        // ── Action ────────────────────────────────────────────

        /** 点击取件码 → 复制到剪贴板 */
        const val ACTION_COPY = "com.codebeacon.widget.COPY"

        /** 点击"已取件" → 标记归档 */
        const val ACTION_MARK_PICKED = "com.codebeacon.widget.MARK_PICKED"

        /** 打开 App */
        const val ACTION_OPEN_APP = "com.codebeacon.widget.OPEN"

        /** 刷新所有小部件 */
        const val ACTION_REFRESH = "com.codebeacon.widget.REFRESH"

        /** 通知使用权变更后由 App 主动刷新 */
        const val ACTION_ACCESS_CHANGED = "com.codebeacon.widget.ACCESS_CHANGED"

        // ── 公共刷新入口 ──────────────────────────────────────

        /** 刷新桌面上的所有小部件（提取成功后调用） */
        fun refreshAll(context: Context) {
            val intent = Intent(context, CodeWidgetProvider::class.java)
                .setAction(ACTION_REFRESH)
            context.sendBroadcast(intent)
        }

        // ── 渲染 ──────────────────────────────────────────────

        fun render(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ) {
            val store = PickupCodeStore(context)
            val views = RemoteViews(context.packageName, R.layout.widget_code)

            // 权限未开启：给出引导，不显示空壳
            if (!SourceFilter.hasNotificationAccess(context)) {
                views.setTextViewText(R.id.badge, "!")
                views.setTextViewText(R.id.courier_name, context.getString(R.string.widget_need_access))
                views.setTextViewText(R.id.code, "--")
                views.setTextViewText(R.id.footer, context.getString(R.string.widget_tap_to_enable))
                views.setTextColor(R.id.code, Color.parseColor("#E53935"))
                views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
                manager.updateAppWidget(widgetId, views)
                return
            }

            val latest = store.getLatestActive()

            if (latest == null) {
                // 空态
                views.setTextViewText(R.id.badge, "?")
                views.setTextViewText(R.id.courier_name, context.getString(R.string.widget_idle))
                views.setTextViewText(R.id.code, "----")
                views.setTextViewText(R.id.footer, context.getString(R.string.widget_waiting))
                views.setTextColor(R.id.code, Color.parseColor("#9E9E9E"))
                views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
                manager.updateAppWidget(widgetId, views)
                return
            }

            // 正常态
            val isRedacted = latest.code == CodeListenerService.REDACTED_PLACEHOLDER

            if (isRedacted) {
                views.setTextViewText(R.id.badge, "?")
                views.setTextViewText(R.id.courier_name, context.getString(R.string.widget_blocked))
                views.setTextViewText(R.id.code, "----")
                views.setTextViewText(R.id.footer, context.getString(R.string.widget_blocked_hint))
                views.setTextColor(R.id.code, Color.parseColor("#FB8C00"))
                views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
                manager.updateAppWidget(widgetId, views)
                return
            }

            views.setTextViewText(R.id.badge, courierBadge(latest.courierName))
            views.setTextViewText(R.id.courier_name, latest.courierName)
            views.setTextViewText(R.id.code, latest.code)
            views.setTextViewText(
                R.id.footer,
                context.getString(R.string.widget_age, formatAge(latest.ageMinutes()))
            )
            views.setTextColor(R.id.code, Color.parseColor("#212121"))

            // 点按码区 → 复制
            views.setOnClickPendingIntent(
                R.id.code,
                copyIntent(context, latest.id, latest.code)
            )
            // 点按空白区 → 打开 App
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))

            manager.updateAppWidget(widgetId, views)
        }

        // ── 格式化辅助 ────────────────────────────────────────

        private fun courierBadge(name: String): String {
            return com.codebeacon.widget.core.Courier.entries
                .firstOrNull { it.displayName == name }
                ?.badge ?: name.take(1)
        }

        private fun formatAge(minutes: Long): String {
            return when {
                minutes < 1 -> "刚刚"
                minutes < 60 -> "${minutes}分钟前"
                minutes < 60 * 24 -> "${minutes / 60}小时前"
                else -> "${minutes / (60 * 24)}天前"
            }
        }

        // ── PendingIntent ─────────────────────────────────────

        private fun copyIntent(context: Context, id: Long, code: String): PendingIntent {
            val intent = Intent(context, CodeWidgetProvider::class.java).apply {
                action = ACTION_COPY
                putExtra(EXTRA_CODE, code)
                putExtra(EXTRA_ID, id)
            }
            return PendingIntent.getBroadcast(
                context,
                id.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun openAppIntent(context: Context): PendingIntent {
            val intent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: Intent(Intent.ACTION_MAIN)
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        const val EXTRA_CODE = "extra_code"
        const val EXTRA_ID = "extra_id"
    }

    // ── 广播处理（点击响应）──────────────────────────────────

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            android.content.ComponentName(context, CodeWidgetProvider::class.java)
        )

        when (intent.action) {
            ACTION_REFRESH, ACTION_ACCESS_CHANGED -> {
                ids.forEach { render(context, manager, it) }
            }

            ACTION_COPY -> {
                val code = intent.getStringExtra(EXTRA_CODE) ?: return
                copyToClipboard(context, code)
                // 复制后刷新（状态可能变化）
                ids.forEach { render(context, manager, it) }
            }

            ACTION_MARK_PICKED -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                if (id > 0) {
                    PickupCodeStore(context).markPicked(id) {
                        // 归档后立即刷新 —— 内容切换即"顺滑下移"的替代效果
                        WidgetAnimator.animateSwap(context, ids)
                    }
                }
            }
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("取件码", text))
        // Android 13+ 系统会自己弹"已复制"提示，不需要自己发通知
    }
}
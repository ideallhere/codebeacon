package com.codebeacon.widget.bridge

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.ClipboardManager
import android.content.ClipData
import androidx.annotation.NonNull
import com.codebeacon.widget.data.PickupCodeStore
import com.codebeacon.widget.listener.SourceFilter
import com.codebeacon.widget.widget.CodeWidgetProvider
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * Flutter ↔ Kotlin 桥接层。
 *
 * 小部件本体必须是原生（RemoteViews 限制），但管理界面用 Flutter 写更高效，
 * 两者通过 MethodChannel 通信。
 *
 * 设计原则：**数据只存在 Kotlin 侧**，Flutter 只负责展示。
 * 避免双份数据源导致的不一致。
 */
class AppBridge : FlutterPlugin, MethodChannel.MethodCallHandler {

    private var channel: MethodChannel? = null
    private var context: Context? = null

    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL).also {
            it.setMethodCallHandler(this)
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        context = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val ctx = context
        if (ctx == null) {
            result.error("NO_CONTEXT", "上下文未初始化", null)
            return
        }
        val store = PickupCodeStore(ctx)

        when (call.method) {
            // ── 权限 ────────────────────────────────────────────

            "hasNotificationAccess" -> {
                result.success(SourceFilter.hasNotificationAccess(ctx))
            }

            "openNotificationAccessSettings" -> {
                // 注意：Android 13+ 侧载 App 不能直接跳通知使用权设置页，
                // 系统会限制。需要先跳应用详情页，让用户手动进。
                val ok = openNotificationSettings(ctx)
                result.success(ok)
            }

            // ── 数据读取 ────────────────────────────────────────

            "getActiveCodes" -> {
                result.success(store.getActive().map { it.toMap() })
            }

            "getArchivedCodes" -> {
                result.success(store.getArchived().map { it.toMap() })
            }

            "getLatestCode" -> {
                result.success(store.getLatestActive()?.toMap())
            }

            "getSourceStats" -> {
                val stats = store.countBySource()
                result.success(
                    stats.map { (pkg, count) ->
                        mapOf(
                            "package" to pkg,
                            "name" to SourceFilter.displayName(ctx, pkg),
                            "count" to count
                        )
                    }
                )
            }

            "getEnabledSources" -> {
                val disabled = SourceFilter.disabledPackages(ctx)
                result.success(
                    SourceFilter.DEFAULT_PACKAGES.map { pkg ->
                        mapOf(
                            "package" to pkg,
                            "name" to SourceFilter.displayName(ctx, pkg),
                            "enabled" to (pkg !in disabled)
                        )
                    }
                )
            }

            // ── 状态变更 ────────────────────────────────────────

            "markPicked" -> {
                val id = (call.argument<Any>("id") as? Number)?.toLong()
                if (id == null) {
                    result.error("BAD_ARG", "缺少 id", null)
                } else {
                    store.markPicked(id) {
                        CodeWidgetProvider.refreshAll(ctx)
                        result.success(true)
                    }
                }
            }

            "unmarkPicked" -> {
                val id = (call.argument<Any>("id") as? Number)?.toLong()
                if (id == null) {
                    result.error("BAD_ARG", "缺少 id", null)
                } else {
                    store.unmarkPicked(id) {
                        CodeWidgetProvider.refreshAll(ctx)
                        result.success(true)
                    }
                }
            }

            "deleteCode" -> {
                val id = (call.argument<Any>("id") as? Number)?.toLong()
                if (id == null) {
                    result.error("BAD_ARG", "缺少 id", null)
                } else {
                    store.delete(id) {
                        CodeWidgetProvider.refreshAll(ctx)
                        result.success(true)
                    }
                }
            }

            "clearActive" -> {
                store.clearActive {
                    CodeWidgetProvider.refreshAll(ctx)
                    result.success(true)
                }
            }

            "copyCode" -> {
                val code = call.argument<String>("code")
                if (code.isNullOrEmpty()) {
                    result.error("BAD_ARG", "取件码为空", null)
                } else {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("取件码", code))
                    result.success(true)
                }
            }

            // ── 来源开关 ────────────────────────────────────────

            "setSourceEnabled" -> {
                val pkg = call.argument<String>("package")
                val enabled = call.argument<Boolean>("enabled") ?: true
                if (pkg == null) {
                    result.error("BAD_ARG", "缺少 package", null)
                } else {
                    if (enabled) SourceFilter.enable(ctx, pkg)
                    else SourceFilter.disable(ctx, pkg)
                    result.success(true)
                }
            }

            // ── 记事本导出 ──────────────────────────────────────

            "exportMemoText" -> {
                val text = MemoExporter.exportText(ctx)
                result.success(text)
            }

            "exportMemoFile" -> {
                val uri = MemoExporter.exportAll(ctx)
                if (uri == null) {
                    result.success(null)
                } else {
                    // 私有目录的情况下需要 FileProvider 授权
                    if (uri.scheme == "content") {
                        MemoExporter.openWithMemo(ctx, uri)
                    }
                    result.success(uri.toString())
                }
            }

            "openMemo" -> {
                val uriStr = call.argument<String>("uri")
                if (uriStr == null) {
                    result.error("BAD_ARG", "缺少 uri", null)
                } else {
                    val ok = MemoExporter.openWithMemo(ctx, android.net.Uri.parse(uriStr))
                    result.success(ok)
                }
            }

            // ── 调试 ────────────────────────────────────────────

            "refreshWidget" -> {
                CodeWidgetProvider.refreshAll(ctx)
                result.success(true)
            }

            "getDebugStats" -> {
                result.success(
                    mapOf(
                        "activeCount" to store.getActive().size,
                        "archivedCount" to store.getArchived().size,
                        "hasAccess" to SourceFilter.hasNotificationAccess(ctx),
                        "listenerPackages" to androidx.core.app.NotificationManagerCompat
                            .getEnabledListenerPackages(ctx).toList()
                    )
                )
            }

            else -> {
                result.notImplemented()
            }
        }
    }

    /**
     * 打开通知使用权设置页。
     *
     * Android 13+ 对**侧载 App** 有限制：不能直接跳到通知使用权页，
     * 会抛异常或被忽略。这里做双路兜底：先试直接跳，失败则跳应用详情页。
     */
    private fun openNotificationSettings(ctx: Context): Boolean {
        // 路径 1：直接跳通知使用权
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        } catch (t: Throwable) {
            // 落到路径 2
        }

        // 路径 2：跳应用详情页，用户从"特殊权限"进
        return try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (t2: Throwable) {
            false
        }
    }

    /** 数据模型 → Map，供 Flutter 读取 */
    private fun PickupCodeStore.PickupCode.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "code" to code,
        "courierName" to courierName,
        "confidence" to confidence,
        "sourcePackage" to sourcePackage,
        "createdAt" to createdAt,
        "rawSnippet" to rawSnippet,
        "markedPicked" to markedPicked,
        "pickedAt" to pickedAt,
        "ageMinutes" to ageMinutes()
    )

    companion object {
        const val CHANNEL = "com.codebeacon/bridge"
    }
}
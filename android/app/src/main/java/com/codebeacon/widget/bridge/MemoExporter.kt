package com.codebeacon.widget.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.codebeacon.widget.data.PickupCodeStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记事本导出。
 *
 * ## ⚠️ 技术现实（必须诚实告知用户）
 *
 * **系统不允许第三方 App 静默写入手机自带备忘录**。实测原因：
 *  - 华为/小米/OPPO/vivo 的备忘录 App 均**未开放对外写入接口**
 *  - Android 没有标准的"写备忘录" API
 *  - `ACTION_INSERT` 只能让用户手动确认，无法静默写入
 *
 * 因此本功能的实现方式是：
 *  1. 把全部记录导出为格式化文本（.txt）
 *  2. 存到公共存储的「文档」目录
 *  3. 用户用系统自带备忘录 / 文件管理器打开即可
 *
 * 这样数据不丢，且符合各厂商的合规要求。
 * 若某天厂商开放了写入接口，可在此扩展静默写入。
 */
object MemoExporter {

    private const val FILE_NAME_PREFIX = "取件码记录_"
    private const val MIME_TEXT = "text/plain"

    /**
     * 导出全部记录到公共存储，返回导出文件的 Uri。
     *
     * 格式示例：
     * ```
     * 取件码记录 · 共 12 条
     * 导出时间：2026-10-15 17:30
     *
     * 【待取】
     * 2026-10-15 15:42  顺丰速运   8-2-3456
     * 2026-10-15 10:03  菜鸟裹裹   A1234
     *
     * 【已取件 · 归档】
     * 2026-10-14 09:12  京东物流   5521
     * ```
     */
    fun exportAll(context: Context): Uri? {
        val store = PickupCodeStore(context)
        val all = store.getAll()
        if (all.isEmpty()) return null

        val text = buildMemoText(all)
        val fileName = FILE_NAME_PREFIX +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date()) +
            ".txt"

        // 优先存公共存储，失败则退到应用私有目录再通过 FileProvider 分享
        val publicFile = try {
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS
            )
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).apply { writeText(text, Charsets.UTF_8) }
        } catch (t: Throwable) {
            null
        }

        val target = publicFile ?: File(context.filesDir, fileName).apply {
            writeText(text, Charsets.UTF_8)
        }

        return if (publicFile != null) {
            Uri.fromFile(publicFile)
        } else {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target
            )
        }
    }

    /** 构造记事本文本 */
    private fun buildMemoText(items: List<PickupCodeStore.PickupCode>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val sb = StringBuilder()

        sb.appendLine("取件码记录 · 共 ${items.size} 条")
        sb.appendLine("导出时间：${fmt.format(Date())}")
        sb.appendLine()
        sb.appendLine("=".repeat(34))
        sb.appendLine()

        val active = items.filter { !it.markedPicked }
        val archived = items.filter { it.markedPicked }

        sb.appendLine("【待取】${active.size} 条")
        sb.appendLine("-".repeat(34))
        if (active.isEmpty()) {
            sb.appendLine("（无）")
        } else {
            active.forEach { item ->
                sb.appendLine(
                    "${fmt.format(Date(item.createdAt))}  " +
                        "${item.courierName.padEnd(6, '　')}  ${item.code}"
                )
            }
        }

        sb.appendLine()
        sb.appendLine("【已取件 · 归档】${archived.size} 条")
        sb.appendLine("-".repeat(34))
        if (archived.isEmpty()) {
            sb.appendLine("（无）")
        } else {
            archived.forEach { item ->
                val pickedAt = item.pickedAt?.let { fmt.format(Date(it)) } ?: "—"
                sb.appendLine(
                    "${fmt.format(Date(item.createdAt))} → $pickedAt  " +
                        "${item.courierName.padEnd(6, '　')}  ${item.code}"
                )
            }
        }

        sb.appendLine()
        sb.appendLine("-".repeat(34))
        sb.appendLine("由「取件码信标」导出")

        return sb.toString()
    }

    /**
     * 调起系统的"打开文件"Intent，让用户用备忘录查看。
     *
     * 各家 ROM 都支持 ACTION_VIEW 文本文件，用户在弹出的应用列表里选备忘录即可。
     */
    fun openWithMemo(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_TEXT)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 分享文本 —— 备选路径，部分备忘录支持作为分享目标接收。
     */
    fun shareText(context: Context, text: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TEXT
            putExtra(Intent.EXTRA_SUBJECT, "取件码记录")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(intent, "保存到备忘录")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 导出纯文本，供 MethodChannel 返回给 Flutter 展示/分享 */
    fun exportText(context: Context): String? {
        val all = PickupCodeStore(context).getAll()
        if (all.isEmpty()) return null
        return buildMemoText(all)
    }
}
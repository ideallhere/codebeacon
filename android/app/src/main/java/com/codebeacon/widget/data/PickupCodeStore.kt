package com.codebeacon.widget.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.Executors

/**
 * 取件码存储。
 *
 * 为什么用 SQLite 而非 Room：
 *  - 小部件的 onUpdate() 是**同步**上下文，Room 的 suspend 查询在那里很难调用
 *  - 数据模型极简（一张表），SQLite 直写反而更可控
 *  - 避免引入 kapt/ksp 带来的编译复杂度
 *
 * 设计要点：
 *  - [markedPicked] 为 true 表示用户已点击"已取件"，进入归档区
 *  - 归档记录长期保留（对应需求中的"记事本"）
 *  - 全部操作走单线程 executor，避免主线程 IO
 */
class PickupCodeStore(context: Context) {

    private val helper = Helper(context.applicationContext)
    private val io = Executors.newSingleThreadExecutor()

    // ── 数据模型 ────────────────────────────────────────────

    data class PickupCode(
        val id: Long = 0,
        val code: String,
        val courierName: String,
        val confidence: Int,
        val sourcePackage: String,
        val createdAt: Long,
        val rawSnippet: String?,
        /** 用户是否已点击"已取件" */
        val markedPicked: Boolean = false,
        /** 归档时间；null 表示还在待取列表 */
        val pickedAt: Long? = null
    ) {
        /** 距今多久 */
        fun ageMinutes(now: Long = System.currentTimeMillis()): Long =
            (now - createdAt) / 60_000
    }

    // ── 写入 ────────────────────────────────────────────────

    /**
     * 保存一条取件码。
     *
     * **去重逻辑**：若库中已有相同取件码（同快递 + 同码），不重复插入，
     * 避免同一短信被多次投递（如 BigText 展开、通知更新）导致刷屏。
     *
     * @return 新记录的 id；若因去重未插入返回 -1
     */
    fun insert(item: PickupCode, onDone: ((Long) -> Unit)? = null) {
        io.execute {
            val db = helper.writableDatabase
            // 去重检查
            val exists = db.query(
                TABLE, arrayOf(COL_ID),
                "$COL_CODE = ? AND $COL_COURIER = ?",
                arrayOf(item.code, item.courierName),
                null, null, null
            ).use { it.moveToFirst() }

            val id = if (exists) {
                -1L
            } else {
                val cv = ContentValues().apply {
                    put(COL_CODE, item.code)
                    put(COL_COURIER, item.courierName)
                    put(COL_CONFIDENCE, item.confidence)
                    put(COL_SOURCE, item.sourcePackage)
                    put(COL_CREATED_AT, item.createdAt)
                    put(COL_RAW, item.rawSnippet)
                    put(COL_MARKED, 0)
                    put(COL_PICKED_AT, null as Long?)
                }
                db.insert(TABLE, null, cv)
            }
            onDone?.invoke(id)
        }
    }

    // ── 读取 ────────────────────────────────────────────────

    /** 待取列表：未标记已取件的，按时间倒序 */
    fun getActive(limit: Int = 50): List<PickupCode> = query(
        selection = "$COL_MARKED = 0",
        orderBy = "$COL_CREATED_AT DESC",
        limit = limit
    )

    /** 归档区：已标记已取件的，按标记时间倒序 */
    fun getArchived(limit: Int = 500): List<PickupCode> = query(
        selection = "$COL_MARKED = 1",
        orderBy = "$COL_PICKED_AT DESC",
        limit = limit
    )

    /** 全部记录，按时间倒序 —— 用于导出记事本 */
    fun getAll(limit: Int = 2000): List<PickupCode> = query(
        selection = null,
        orderBy = "$COL_CREATED_AT DESC",
        limit = limit
    )

    /** 最新一条待取记录；小部件主视图用 */
    fun getLatestActive(): PickupCode? =
        query("$COL_MARKED = 0", "$COL_CREATED_AT DESC", 1).firstOrNull()

    private fun query(
        selection: String?,
        orderBy: String,
        limit: Int
    ): List<PickupCode> {
        val result = mutableListOf<PickupCode>()
        helper.readableDatabase.query(
            TABLE, null, selection, null, null, null, orderBy, limit.toString()
        ).use { c ->
            val idIdx = c.getColumnIndexOrThrow(COL_ID)
            val codeIdx = c.getColumnIndexOrThrow(COL_CODE)
            val courierIdx = c.getColumnIndexOrThrow(COL_COURIER)
            val confIdx = c.getColumnIndexOrThrow(COL_CONFIDENCE)
            val srcIdx = c.getColumnIndexOrThrow(COL_SOURCE)
            val createdIdx = c.getColumnIndexOrThrow(COL_CREATED_AT)
            val rawIdx = c.getColumnIndexOrThrow(COL_RAW)
            val markedIdx = c.getColumnIndexOrThrow(COL_MARKED)
            val pickedIdx = c.getColumnIndexOrThrow(COL_PICKED_AT)

            while (c.moveToNext()) {
                result += PickupCode(
                    id = c.getLong(idIdx),
                    code = c.getString(codeIdx),
                    courierName = c.getString(courierIdx),
                    confidence = c.getInt(confIdx),
                    sourcePackage = c.getString(srcIdx),
                    createdAt = c.getLong(createdIdx),
                    rawSnippet = c.getString(rawIdx),
                    markedPicked = c.getInt(markedIdx) == 1,
                    pickedAt = if (c.isNull(pickedIdx)) null else c.getLong(pickedIdx)
                )
            }
        }
        return result
    }

    // ── 状态变更 ────────────────────────────────────────────

    /**
     * 标记为已取件（进入归档）。
     * 小部件点击时调用 —— 对应需求中"顺滑下移消失 + 存入记录"。
     */
    fun markPicked(id: Long, onDone: (() -> Unit)? = null) {
        io.execute {
            val cv = ContentValues().apply {
                put(COL_MARKED, 1)
                put(COL_PICKED_AT, System.currentTimeMillis())
            }
            helper.writableDatabase.update(TABLE, cv, "$COL_ID = ?", arrayOf(id.toString()))
            onDone?.invoke()
        }
    }

    /** 从归档恢复回待取列表（误点补救） */
    fun unmarkPicked(id: Long, onDone: (() -> Unit)? = null) {
        io.execute {
            val cv = ContentValues().apply {
                put(COL_MARKED, 0)
                put(COL_PICKED_AT, null as Long?)
            }
            helper.writableDatabase.update(TABLE, cv, "$COL_ID = ?", arrayOf(id.toString()))
            onDone?.invoke()
        }
    }

    /** 物理删除（归档页里的"彻底删除"） */
    fun delete(id: Long, onDone: (() -> Unit)? = null) {
        io.execute {
            helper.writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
            onDone?.invoke()
        }
    }

    /** 清空待取列表（保留归档） */
    fun clearActive(onDone: (() -> Unit)? = null) {
        io.execute {
            helper.writableDatabase.delete(TABLE, "$COL_MARKED = 0", null)
            onDone?.invoke()
        }
    }

    /** 各来源应用识别到的条数 —— 设置页显示"已识别 N 条" */
    fun countBySource(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        helper.readableDatabase.rawQuery(
            "SELECT $COL_SOURCE, COUNT(*) FROM $TABLE WHERE $COL_MARKED = 0 GROUP BY $COL_SOURCE",
            null
        ).use { c ->
            while (c.moveToNext()) {
                result[c.getString(0)] = c.getInt(1)
            }
        }
        return result
    }

    // ── SQLite 助手 ─────────────────────────────────────────

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_CODE TEXT NOT NULL,
                    $COL_COURIER TEXT NOT NULL,
                    $COL_CONFIDENCE INTEGER NOT NULL DEFAULT 0,
                    $COL_SOURCE TEXT NOT NULL DEFAULT '',
                    $COL_CREATED_AT INTEGER NOT NULL,
                    $COL_RAW TEXT,
                    $COL_MARKED INTEGER NOT NULL DEFAULT 0,
                    $COL_PICKED_AT INTEGER
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_active ON $TABLE($COL_MARKED, $COL_CREATED_AT DESC)")
            // 去重查询用
            db.execSQL("CREATE INDEX idx_code ON $TABLE($COL_CODE, $COL_COURIER)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "codebeacon.db"
        private const val DB_VERSION = 1

        const val TABLE = "pickup_codes"
        const val COL_ID = "id"
        const val COL_CODE = "code"
        const val COL_COURIER = "courier"
        const val COL_CONFIDENCE = "confidence"
        const val COL_SOURCE = "source_pkg"
        const val COL_CREATED_AT = "created_at"
        const val COL_RAW = "raw_snippet"
        const val COL_MARKED = "marked_picked"
        const val COL_PICKED_AT = "picked_at"
    }
}
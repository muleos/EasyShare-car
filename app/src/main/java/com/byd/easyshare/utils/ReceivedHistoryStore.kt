package com.byd.easyshare.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Log
import com.byd.easyshare.models.ReceivedFile
import com.byd.easyshare.models.ReceivedHistoryItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 接收历史（首页「最近接收」列表）的本地存储。
 *
 * 只在接收全部完成时追加一条记录，按 Uri 去重、按时间倒序、条数封顶，
 * 写入走 AtomicFile，进程被杀也不会留下半个 JSON。
 */
object ReceivedHistoryStore {
    private const val TAG = "ReceivedHistory"
    private const val FILE_NAME = "received-history.json"
    private const val MAX_ENTRIES = 80

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun list(context: Context): List<ReceivedHistoryItem> = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) {
            emptyList()
        } else {
            Json.decodeFromString<List<ReceivedHistoryItem>>(file.readText(Charsets.UTF_8))
        }
    }.getOrElse {
        Log.w(TAG, "Failed to read received history", it)
        emptyList()
    }

    /** 接收完成后追加记录；[sender] 为空时界面上只显示时间。 */
    @Synchronized
    fun record(context: Context, files: List<ReceivedFile>, sender: String) {
        if (files.isEmpty()) return
        val now = System.currentTimeMillis()
        val added = files.map { file ->
            ReceivedHistoryItem(
                name = file.name,
                uri = file.uri.toString(),
                mimeType = file.mimeType,
                size = querySize(context, file.uri),
                sender = sender,
                time = now,
            )
        }
        val merged = (added + list(context))
            .distinctBy { it.uri }
            .sortedByDescending { it.time }
            .take(MAX_ENTRIES)
        write(context, merged)
    }

    @Synchronized
    fun clear(context: Context) = write(context, emptyList())

    private fun write(context: Context, entries: List<ReceivedHistoryItem>) {
        runCatching {
            val atomicFile = AtomicFile(File(context.filesDir, FILE_NAME))
            val output = atomicFile.startWrite()
            try {
                output.write(json.encodeToString(entries).toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
        }.onFailure { Log.w(TAG, "Failed to persist received history", it) }
    }

    private fun querySize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            ?: 0L
    }.getOrDefault(0L)
}

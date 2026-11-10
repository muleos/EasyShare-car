package com.byd.easyshare.models

import kotlinx.serialization.Serializable

/**
 * 一条「接收历史」记录。
 *
 * [uri] 保存的是收到文件的 Content Uri（MediaStore Downloads 或用户选择的 SAF 目录），
 * 应用重启后依然可以直接打开，因此不需要额外的 FileProvider。
 */
@Serializable
data class ReceivedHistoryItem(
    val name: String,
    val uri: String,
    val mimeType: String = "",
    val size: Long = 0L,
    val sender: String = "",
    val time: Long = 0L,
)

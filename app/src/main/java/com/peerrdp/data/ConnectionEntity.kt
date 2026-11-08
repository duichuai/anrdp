package com.peerrdp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 3389,
    val username: String,
    /** 空串表示不保存密码，连接时询问 */
    val password: String = "",
    val domain: String = "",
    /** "adaptive" 表示按手机屏幕可视区域；否则为 "宽x高"，如 "1920x1080" */
    val resolution: String = RESOLUTION_ADAPTIVE,
    /** 绑定的 Tailscale 配置 ID；0 表示不使用 Tailscale，直连 */
    val tailscaleConfigId: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0
) {
    companion object {
        const val RESOLUTION_ADAPTIVE = "adaptive"
    }
}

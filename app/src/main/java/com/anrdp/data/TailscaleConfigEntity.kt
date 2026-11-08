package com.anrdp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tailscale (tsnet) 节点配置。
 * 每个配置对应一个 tsnet 实例，连接到 Headscale 控制面并加入 tailnet。
 */
@Entity(tableName = "tailscale_configs")
data class TailscaleConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Headscale 控制面 URL，例如 https://hs.duichuai.com */
    val controlURL: String,
    /** pre-auth key，用于非交互式注册节点 */
    val authKey: String,
    /** 节点在 tailnet 中的主机名 */
    val hostname: String = "anrdp",
    /** 状态文件保存目录（应用内部存储），通常由应用自动填充 */
    val stateDir: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

package com.peerrdp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import com.peerrdp.data.AppDatabase
import com.peerrdp.data.ConnectionEntity
import com.peerrdp.tailscale.TailscaleManager
import com.freerdp.freerdpcore.presentation.SessionActivity
import kotlinx.coroutines.delay

/**
 * 把连接配置转成 freerdp:// URI 并交给 freeRDPCore 的 SessionActivity。
 * URI 由 LibFreeRDP 解析为 xfreerdp 参数：/v:host:port /u:user /p:pass /d:domain /gdi:sw
 * 若连接绑定了 Tailscale 配置，会自动拉起 tsnet 并通过 SOCKS5 代理走 tailnet。
 */
object RdpConnector {

    private const val TAG = "RdpConnector"

    /**
     * 建立 RDP 连接。挂起函数：若绑定 Tailscale，会等待 tsnet 就绪并启动 SOCKS5。
     */
    suspend fun connect(context: Context, conn: ConnectionEntity, password: String) {
        val builder = Uri.Builder()
            .scheme("freerdp")
            .encodedAuthority(buildAuthority(conn))
            .path("connect")

        builder.appendQueryParameter("size", desktopSize(context, conn.resolution))
        if (password.isNotEmpty()) {
            builder.appendQueryParameter("p", password)
        }
        if (conn.domain.isNotEmpty()) {
            builder.appendQueryParameter("d", conn.domain)
        }

        // Tailscale 联动：若绑定了配置，启动 tsnet + SOCKS5，注入 /proxy 参数
        if (conn.tailscaleConfigId > 0) {
            val socksAddr = ensureTailscaleReady(context, conn.tailscaleConfigId)
            // FreeRDP 支持 /proxy:socks5://host:port
            builder.appendQueryParameter("proxy", "socks5://$socksAddr")
            Log.i(TAG, "RDP via Tailscale SOCKS5: $socksAddr")
        }

        val intent = Intent(context, SessionActivity::class.java).setData(builder.build())
        context.startActivity(intent)
    }

    private fun buildAuthority(conn: ConnectionEntity): String = buildString {
        if (conn.username.isNotEmpty()) {
            append(Uri.encode(conn.username)).append('@')
        }
        append(conn.host)
        if (conn.port !in 1..65535) throw IllegalArgumentException("非法端口")
        if (conn.port != 3389) append(':').append(conn.port)
    }

    /**
     * 确保 tsnet 已连接到 tailnet，并返回本地 SOCKS5 地址。
     * 若 tsnet 未启动，用对应配置启动；轮询状态直到 running。
     */
    private suspend fun ensureTailscaleReady(context: Context, configId: Long): String {
        val dao = AppDatabase.get(context).tailscaleConfigDao()
        val config = dao.getById(configId)
            ?: throw IllegalStateException("Tailscale 配置不存在（可能已被删除），请重新编辑连接绑定配置")

        if (!TailscaleManager.isRunning()) {
            TailscaleManager.start(context.applicationContext, config)
        }

        // 等待状态变为 running（注册 + 握手需要时间）
        var waited = 0
        while (TailscaleManager.status() != "running" && waited < 60_000) {
            delay(500)
            waited += 500
        }
        if (TailscaleManager.status() != "running") {
            throw IllegalStateException(
                "Tailscale not ready, status=${TailscaleManager.status()}"
            )
        }

        Log.i(TAG, "tailnet IP: ${TailscaleManager.tailnetIP()}")
        return TailscaleManager.socks5Address() ?: TailscaleManager.startSOCKS5()
    }

    /** 连接配置的分辨率；自适应或非法值回退到屏幕可视区域 */
    private fun desktopSize(context: Context, resolution: String): String {
        if (resolution != ConnectionEntity.RESOLUTION_ADAPTIVE) {
            val parts = resolution.split('x')
            val w = parts.getOrNull(0)?.toIntOrNull()
            val h = parts.getOrNull(1)?.toIntOrNull()
            if (w != null && h != null && w > 0 && h > 0) return "${w}x$h"
        }
        val (w, h) = visibleScreenSize(context)
        return "${w}x$h"
    }

    /** 屏幕可视区域（去掉状态栏、导航栏、挖孔），直接作为远程桌面分辨率 */
    private fun visibleScreenSize(context: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(WindowManager::class.java)
            val metrics = wm.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val w = metrics.bounds.width() - insets.left - insets.right
            val h = metrics.bounds.height() - insets.top - insets.bottom
            if (w > 0 && h > 0) return w to h
        }
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }
}

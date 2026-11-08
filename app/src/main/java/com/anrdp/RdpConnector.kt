package com.anrdp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import com.anrdp.data.ConnectionEntity
import com.freerdp.freerdpcore.presentation.SessionActivity

/**
 * 把连接配置转成 freerdp:// URI 并交给 freeRDPCore 的 SessionActivity。
 * URI 由 LibFreeRDP 解析为 xfreerdp 参数：/v:host:port /u:user /p:pass /d:domain /gdi:sw
 */
object RdpConnector {

    fun connect(context: Context, conn: ConnectionEntity, password: String) {
        val authority = buildString {
            if (conn.username.isNotEmpty()) {
                append(Uri.encode(conn.username)).append('@')
            }
            append(conn.host)
            if (conn.port !in 1..65535) throw IllegalArgumentException("非法端口")
            if (conn.port != 3389) append(':').append(conn.port)
        }

        val builder = Uri.Builder()
            .scheme("freerdp")
            .encodedAuthority(authority)
            .path("connect")
        // 不传 size 时 FreeRDP 默认 1024x768，手机上看就是一块小画面；
        // 按连接配置的分辨率（自适应 = 手机可视区域）开会话。
        builder.appendQueryParameter("size", desktopSize(context, conn.resolution))
        if (password.isNotEmpty()) {
            builder.appendQueryParameter("p", password)
        }
        if (conn.domain.isNotEmpty()) {
            builder.appendQueryParameter("d", conn.domain)
        }

        val intent = Intent(context, SessionActivity::class.java).setData(builder.build())
        context.startActivity(intent)
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

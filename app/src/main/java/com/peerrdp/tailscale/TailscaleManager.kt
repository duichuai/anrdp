package com.peerrdp.tailscale

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.peerrdp.data.TailscaleConfigEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tsnetandroid.Server
import tsnetandroid.Tsnetandroid
import java.io.File

/**
 * 封装 tsnet 的启停与 SOCKS5 代理管理。
 * 单例。所有 gomobile JNI 调用均为阻塞式，统一切到 IO 线程，
 * 避免在主线程阻塞引发 ANR。
 */
object TailscaleManager {
    private const val TAG = "TailscaleManager"

    private val mutex = Mutex()

    @Volatile
    private var server: Server? = null

    @Volatile
    private var socks5Addr: String? = null

    /** 启动 tsnet 节点。JNI 阻塞调用在 IO 线程执行；启动后轮询 [status] 直到 "running"。 */
    suspend fun start(context: Context, config: TailscaleConfigEntity) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (server != null) {
                    Log.w(TAG, "tsnet already running, ignoring start")
                    return@withLock
                }

                val stateDir = config.stateDir.ifEmpty {
                    File(context.applicationContext.filesDir, "tailscale-state-${config.id}")
                        .apply { mkdirs() }.absolutePath
                }

                // 启动前把 Android ConnectivityManager 的真实网络信息注入 Go 侧，
                // 否则 netmon 拿到 loopback-only 的接口列表会在 magicsock 深处 panic（native crash）
                injectNetworkInfo(context.applicationContext)

                val srv = Server(
                    config.hostname,
                    config.controlURL,
                    config.authKey,
                    stateDir
                )
                try {
                    srv.start()
                } catch (t: Throwable) {
                    // 启动失败必须清理，避免残留半初始化状态导致下次启动崩溃
                    try {
                        srv.close()
                    } catch (_: Throwable) {
                    }
                    // 附带 tsnet 内部日志尾部，panic 细节会体现在 toast 里
                    val tail = File(stateDir, "tsnet.log")
                        .takeIf { it.exists() }
                        ?.let { f ->
                            val bytes = f.readBytes()
                            String(bytes, Charsets.UTF_8).takeLast(1200)
                        }
                        .orEmpty()
                    throw RuntimeException("${t.message}\n$tail", t)
                }
                server = srv
                Log.i(TAG, "tsnet started, control=${config.controlURL}")
            }
        }

    /** 启动本地 SOCKS5 代理，返回监听地址 host:port。 */
    suspend fun startSOCKS5(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val srv = server ?: throw IllegalStateException("tsnet not started")
            val addr = srv.startSOCKS5()
            if (addr.isNullOrEmpty()) {
                throw RuntimeException("socks5 start failed")
            }
            socks5Addr = addr
            Log.i(TAG, "socks5 listening on $addr")
            addr
        }
    }

    /** 停止 SOCKS5 代理。 */
    suspend fun stopSOCKS5() = withContext(Dispatchers.IO) {
        mutex.withLock {
            server?.stopSOCKS5()
            socks5Addr = null
        }
    }

    /** 当前 SOCKS5 地址，未启动返回 null。 */
    fun socks5Address(): String? = socks5Addr

    /** 返回节点状态：running / starting / stopped / error */
    suspend fun status(): String = withContext(Dispatchers.IO) {
        server?.status() ?: "stopped"
    }

    /** 返回节点的 tailnet IP，未就绪返回空串。 */
    suspend fun tailnetIP(): String = withContext(Dispatchers.IO) {
        server?.tailnetIP() ?: ""
    }

    /** 停止 tsnet 节点，释放所有资源。 */
    suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.withLock {
            server?.let {
                try {
                    it.stopSOCKS5()
                } catch (_: Throwable) {
                }
                try {
                    it.close()
                } catch (e: Throwable) {
                    Log.e(TAG, "tsnet close error: ${e.message}")
                }
            }
            server = null
            socks5Addr = null
            Log.i(TAG, "tsnet stopped")
        }
    }

    fun isRunning(): Boolean = server != null

    /** 从 ConnectivityManager 读取活动网络的接口名/地址/默认网关，注入 Go 侧 netmon。 */
    private fun injectNetworkInfo(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return
            val lp = cm.getLinkProperties(network) ?: return
            val ifName = lp.interfaceName.orEmpty()
            val addrs = lp.linkAddresses.joinToString(",") { it.toString() }
            val gateway = lp.routes
                .firstOrNull { it.isDefaultRoute && it.gateway != null }
                ?.gateway?.hostAddress.orEmpty()
            Tsnetandroid.setNetworkInfo(ifName, addrs, gateway)
            Log.i(TAG, "netinfo: if=$ifName gw=$gateway addrs=$addrs")
        } catch (t: Throwable) {
            Log.w(TAG, "injectNetworkInfo failed: ${t.message}")
        }
    }
}

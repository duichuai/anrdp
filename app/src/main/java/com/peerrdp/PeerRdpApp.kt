package com.peerrdp

import com.freerdp.freerdpcore.application.GlobalApp

/** 应用入口：复用 freeRDPCore 的全局初始化（LibFreeRDP 事件监听、会话表等） */
class PeerRdpApp : GlobalApp()

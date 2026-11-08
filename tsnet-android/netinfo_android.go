//go:build android

package tsnetandroid

import "tailscale.com/net/netmon"

// updateNetmonDefaultRoute 仅在 Android 构建下生效：netmon 的这两个函数定义于
// interfaces_android.go（//go:build android），由官方 Android 客户端同款机制提供。
func updateNetmonDefaultRoute(ifName, gateway string) {
	if ifName != "" {
		netmon.UpdateLastKnownDefaultRouteInterface(ifName)
	}
	if gateway != "" {
		netmon.UpdateLastKnownDefaultGateway(gateway)
	}
}

//go:build !android

package tsnetandroid

// updateNetmonDefaultRoute 非 Android 平台为空实现（宿主机 go build 通过即可，
// 该包只在 Android 上真正运行）。
func updateNetmonDefaultRoute(ifName, gateway string) {}

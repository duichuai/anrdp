// Package tsnetandroid wraps tailscale.com/tsnet for embedding into Android via gomobile.
package tsnetandroid

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/net/netmon"
	"tailscale.com/net/socks5"
	"tailscale.com/tsnet"
)

// Android 应用 UID 无权调用 net.Interfaces()（netlink permission denied），
// 也无法直接读路由表。Tailscale 官方 Android 客户端的做法是由 Java 侧通过
// ConnectivityManager 获取真实网络信息，再注入 Go 侧（见 netmon/interfaces_android.go
// 中 UpdateLastKnownDefaultRouteInterface/Gateway 的注释）。这里照此实现：
// Kotlin 在启动前调用 SetNetworkInfo 注入接口名/地址/默认网关，
// netmon 的接口枚举由我们注册的 getter 按注入的信息合成。
var (
	netInfoMu  sync.Mutex
	netIfName  = "wlan0"
	netAddrs   []netip.Prefix
	netGateway string
)

// SetNetworkInfo 注入 Android ConnectivityManager 获取的网络信息。
//
//	ifName:  活动网络接口名（如 "wlan0"）
//	addrs:   逗号分隔的 CIDR 列表（如 "192.168.1.2/24,fe80::1/64"）
//	gateway: 默认网关 IP（如 "192.168.1.1"）
func SetNetworkInfo(ifName, addrs, gateway string) {
	netInfoMu.Lock()
	defer netInfoMu.Unlock()
	if ifName != "" {
		netIfName = ifName
	}
	if gateway != "" {
		netGateway = gateway
	}
	updateNetmonDefaultRoute(ifName, gateway)
	netAddrs = nil
	for _, a := range strings.Split(addrs, ",") {
		a = strings.TrimSpace(a)
		if a == "" {
			continue
		}
		if p, err := netip.ParsePrefix(a); err == nil {
			netAddrs = append(netAddrs, p)
		}
	}
}

func init() {
	netmon.RegisterInterfaceGetter(androidInterfaces)
}

// androidInterfaces 合成接口列表：loopback + 由 SetNetworkInfo 注入的活动接口。
func androidInterfaces() ([]netmon.Interface, error) {
	netInfoMu.Lock()
	ifName := netIfName
	prefixes := append([]netip.Prefix(nil), netAddrs...)
	netInfoMu.Unlock()

	lo := netmon.Interface{
		Interface: &net.Interface{
			Index: 1,
			MTU:   65536,
			Name:  "lo",
			Flags: net.FlagLoopback | net.FlagUp,
		},
		AltAddrs: []net.Addr{
			&net.IPNet{IP: net.IPv4(127, 0, 0, 1), Mask: net.IPv4Mask(255, 0, 0, 0)},
			&net.IPNet{IP: net.IPv6loopback, Mask: net.CIDRMask(128, 128)},
		},
	}

	wlan := netmon.Interface{
		Interface: &net.Interface{
			Index: 2,
			MTU:   1500,
			Name:  ifName,
			Flags: net.FlagUp | net.FlagBroadcast | net.FlagMulticast,
		},
	}
	for _, p := range prefixes {
		ip := p.Addr()
		bits := 128
		if ip.Is4() {
			bits = 32
		}
		wlan.AltAddrs = append(wlan.AltAddrs, &net.IPNet{
			IP:   net.ParseIP(ip.String()),
			Mask: net.CIDRMask(p.Bits(), bits),
		})
	}
	if len(wlan.AltAddrs) == 0 {
		// 未注入地址时给占位私网地址，保证存在可用的非 loopback 接口
		wlan.AltAddrs = append(wlan.AltAddrs, &net.IPNet{
			IP:   net.IPv4(10, 0, 2, 15),
			Mask: net.IPv4Mask(255, 255, 255, 0),
		})
	}

	return []netmon.Interface{lo, wlan}, nil
}

// Server wraps a tsnet.Server with a gomobile-friendly API.
type Server struct {
	srv       *tsnet.Server
	socksSrv  *socks5.Server
	socksLn   net.Listener
	mu        sync.Mutex
	started   bool
	socksAddr string
}

// NewServer creates a tsnet server instance.
//
//	hostname:  node name in tailnet
//	controlURL: Headscale server URL (e.g. https://hs.duichuai.com)
//	authKey:   pre-auth key for non-interactive registration
//	stateDir:  directory for state files (app internal storage)
func NewServer(hostname, controlURL, authKey, stateDir string) *Server {
	// logpolicy.LogsDir 在 Android 上找不到任何可写日志目录会 panic
	// （"no safe place found to store log state"）；它最先检查 TS_LOGS_DIR，
	// 指向 stateDir 即可。
	os.Setenv("TS_LOGS_DIR", stateDir)

	// 把 tsnet 内部日志写到 stateDir/tsnet.log（每次启动截断），
	// Kotlin 侧出错时可读取尾部内容附在错误信息里，便于定位。
	logf := func(format string, args ...any) {}
	if logFile, err := os.OpenFile(
		filepath.Join(stateDir, "tsnet.log"),
		os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644,
	); err == nil {
		logf = func(format string, args ...any) {
			fmt.Fprintf(logFile, format+"\n", args...)
		}
	}

	srv := &tsnet.Server{
		Hostname:   hostname,
		ControlURL: controlURL,
		AuthKey:    authKey,
		Dir:        stateDir,
		Logf:       logf,
	}
	return &Server{srv: srv}
}

// Start launches the tsnet server. Non-blocking: returns once the server
// has been started. The caller should poll Status() until "running".
// Go panic 会被 recover 转为 error 返回（gomobile 侧表现为 Java 异常），
// 避免 native crash 直接杀掉整个进程。
func (s *Server) Start() (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("tsnet panic: %v", r)
		}
	}()

	s.mu.Lock()
	if s.started {
		s.mu.Unlock()
		return fmt.Errorf("already started")
	}
	s.mu.Unlock()

	if err := s.srv.Start(); err != nil {
		return fmt.Errorf("tsnet start: %w", err)
	}

	s.mu.Lock()
	s.started = true
	s.mu.Unlock()
	return nil
}

// Close stops the tsnet server and releases all resources.
func (s *Server) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.socksLn != nil {
		s.socksLn.Close()
		s.socksLn = nil
	}
	s.socksSrv = nil
	s.socksAddr = ""

	if s.srv != nil {
		err := s.srv.Close()
		s.srv = nil
		s.started = false
		return err
	}
	return nil
}

// StartSOCKS5 starts a local SOCKS5 proxy that routes traffic through the tailnet.
// Returns the listen address (host:port) on success.
// Must be called after Start().
func (s *Server) StartSOCKS5() (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.started {
		return "", fmt.Errorf("server not started")
	}
	if s.socksLn != nil {
		return s.socksAddr, nil
	}

	// Listen on localhost only
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", fmt.Errorf("socks5 listen: %w", err)
	}

	// SOCKS5 dialer routes through tsnet
	s.socksSrv = &socks5.Server{
		Logf: func(format string, args ...any) { /* discard logs */ },
		Dialer: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return s.srv.Dial(ctx, network, addr)
		},
	}

	s.socksLn = ln
	s.socksAddr = ln.Addr().String()

	go s.socksSrv.Serve(ln)
	return s.socksAddr, nil
}

// StopSOCKS5 stops the local SOCKS5 proxy.
func (s *Server) StopSOCKS5() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.socksLn != nil {
		s.socksLn.Close()
		s.socksLn = nil
	}
	s.socksSrv = nil
	s.socksAddr = ""
}

// SOCKS5Addr returns the local SOCKS5 listen address, or empty if not started.
func (s *Server) SOCKS5Addr() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.socksAddr
}

// Status returns the current backend state: "running", "starting", "stopped", or "error".
func (s *Server) Status() string {
	s.mu.Lock()
	if !s.started {
		s.mu.Unlock()
		return "stopped"
	}
	s.mu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	lc, err := s.srv.LocalClient()
	if err != nil {
		return "error"
	}
	st, err := lc.Status(ctx)
	if err != nil {
		return "error"
	}
	switch st.BackendState {
	case ipn.Running.String():
		return "running"
	case ipn.Starting.String():
		return "starting"
	default:
		return strings.ToLower(string(st.BackendState))
	}
}

// TailnetIP returns the node's Tailscale IPv4 address, or empty if not yet assigned.
func (s *Server) TailnetIP() string {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	lc, err := s.srv.LocalClient()
	if err != nil {
		return ""
	}
	st, err := lc.Status(ctx)
	if err != nil {
		return ""
	}
	if st.Self != nil && len(st.Self.TailscaleIPs) > 0 {
		return st.Self.TailscaleIPs[0].String()
	}
	return ""
}

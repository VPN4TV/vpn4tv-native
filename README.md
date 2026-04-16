# VPN4TV Native

Native Kotlin VPN client for Android TV, built on [sing-box](https://sing-box.sagernet.org/) core with embedded protocol bridges for xhttp, Outline SS, and AmneziaWG.

## Features

- **JNI in-process** — sing-box core via libbox.aar, no gRPC/TCP localhost issues
- **Xray bridge** — xhttp/splithttp transport support via embedded xray-core
- **Outline bridge** — SIP002 prefix Shadowsocks via embedded outline-sdk
- **AmneziaWG bridge** — WireGuard/AmneziaWG via embedded wireproxy-awg
- **Proxy mode (SOCKS5)** — for devices without VPN permission dialog (SberBox etc.)
- **Subscription formats** — URI links (vless/hy2/trojan/ss/wg), Xray JSON, sing-box JSON, AmneziaVPN vpn://, .conf/.wg/.vpn files
- **Telegram bot** — add subscription via @VPN4TV_Bot (10-digit code)
- **Server selection** — URLTest auto-select + manual switching with delay display
- **D-pad navigation** — designed for TV remote control
- **LAN bypass** — Chromecast, Plex/NAS, router admin work while VPN is on
- **Auto-refresh on expired** — detects expired subscription and refreshes before connecting
- **Subscription info** — shows expiry date and traffic usage from provider headers
- **Play Core in-app update** — force-prompt for critical hotfixes (graceful no-Play fallback)
- **Logs viewer** — real-time logs with level filtering, default Warn+
- **Multiple subscriptions** — add, update, switch between profiles with collision-free naming

## Supported protocols

| Protocol | URI | Xray JSON | Bridge |
|----------|-----|-----------|--------|
| VLESS (+ Reality, XTLS Vision) | `vless://` | `"protocol": "vless"` | sing-box native |
| VLESS xhttp/splithttp | `vless://` | `"protocol": "vless"` | Xray bridge |
| Hysteria2 | `hy2://` | — | sing-box native |
| Trojan | `trojan://` | `"protocol": "trojan"` | sing-box native |
| Shadowsocks | `ss://` | `"protocol": "shadowsocks"` | sing-box native |
| Shadowsocks SIP002 prefix | `ss://?prefix=` | — | Outline bridge |
| VMess | `vmess://` | `"protocol": "vmess"` | sing-box native |
| WireGuard / AmneziaWG | `wg://`, `vpn://`, `.conf` | — | wireproxy-awg bridge |

Transports: TCP, WebSocket, gRPC, HTTP/2, xHTTP, splitHTTP, HTTPUpgrade.

## Download

- **Play Store**: [com.vpn4tv.hiddify](https://play.google.com/store/apps/details?id=com.vpn4tv.hiddify)
- **Direct APK**: [bell.a4e.ar/vpn4tv-native-alpha.apk](https://bell.a4e.ar/vpn4tv-native-alpha.apk)

## Building

1. Build `libbox.aar` from the sing-box fork with all three bridges:
   ```bash
   cd sing-box-xhttp
   go run ./cmd/internal/build_libbox -target android -platform android/arm,android/arm64
   ```
2. Place `libbox.aar` in `app/libs/`
3. Create `local.properties` with signing config
4. Build:
   ```bash
   ./gradlew bundleRelease assembleRelease
   ```

## Architecture

```
com.vpn4tv.app/
├── ui/              # Compose screens (Home, Servers, Profiles, Settings, Logs, About)
├── bg/              # VPNService, ProxyService, BoxService, PlatformInterface
├── converter/       # ProxyParser, ConfigGenerator, HwidService
├── database/        # Room DB (profiles, settings)
├── xray/            # XrayBridge (xhttp/splithttp)
├── outline/         # OutlineBridge (SIP002 prefix SS)
├── wireproxy/       # WgBridge, WgConfigGenerator (AmneziaWG)
└── utils/           # UpdateChecker, PlayUpdateChecker
```

## Open Source Libraries

| Library | License | URL |
|---------|---------|-----|
| sing-box | GPLv3 | [github.com/SagerNet/sing-box](https://github.com/SagerNet/sing-box) |
| Xray-core | MPLv2 | [github.com/XTLS/Xray-core](https://github.com/XTLS/Xray-core) |
| Outline SDK | Apache 2.0 | [github.com/Jigsaw-Code/outline-sdk](https://github.com/Jigsaw-Code/outline-sdk) |
| amneziawg-go | MIT | [github.com/amnezia-vpn/amneziawg-go](https://github.com/amnezia-vpn/amneziawg-go) |
| wireproxy | ISC | [github.com/pufferffish/wireproxy](https://github.com/pufferffish/wireproxy) |

## License

GPLv3 — see [LICENSE](LICENSE)

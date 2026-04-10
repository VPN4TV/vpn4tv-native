# VPN4TV Native

Native Kotlin VPN client for Android TV, built on [sing-box](https://sing-box.sagernet.org/) core.

## Features

- **JNI in-process** — sing-box core via libbox.aar, no gRPC/TCP localhost issues
- **Subscription formats** — URI links (vless/hy2/trojan/ss), Xray JSON, sing-box JSON
- **Telegram bot** — add subscription via @VPN4TV_Bot (QR code + 10-digit code)
- **Server selection** — URLTest auto-select + manual switching with delay display
- **D-pad navigation** — designed for TV remote control
- **Logs viewer** — real-time logs with level filtering (All/Info/Warn/Error)
- **Multiple subscriptions** — add, update, switch between profiles
- **HWID headers** — device identification for Remnawave subscriptions
- **24MB release APK** — lightweight compared to Flutter-based alternatives

## Supported protocols

| Protocol | URI | Xray JSON |
|----------|-----|-----------|
| VLESS (+ Reality, XTLS Vision) | `vless://` | `"protocol": "vless"` |
| Hysteria2 | `hy2://` | — |
| Trojan | `trojan://` | `"protocol": "trojan"` |
| Shadowsocks | `ss://` | `"protocol": "shadowsocks"` |
| VMess | — | `"protocol": "vmess"` |

Transports: TCP, WebSocket, gRPC, HTTP/2, xHTTP, HTTPUpgrade.

## Download

- **Alpha APK**: [bell.a4e.ar/vpn4tv-native-alpha.apk](https://bell.a4e.ar/vpn4tv-native-alpha.apk)

## Building

1. Build `libbox.aar` from [sing-box](https://github.com/SagerNet/sing-box) v1.13.x:
   ```bash
   cd sing-box
   make lib_android
   ```
2. Place `libbox.aar` and `libbox-legacy.aar` in `app/libs/`
3. Create `local.properties` with signing config
4. Build:
   ```bash
   ./gradlew assembleRelease
   ```

## Architecture

```
com.vpn4tv.app/
├── ui/              # Compose screens (Home, Servers, Profiles, Logs, About)
├── bg/              # VPNService, BoxService, CommandServer, PlatformInterface
├── converter/       # ProxyParser (URI + Xray JSON), ConfigGenerator, HwidService
├── database/        # Room DB (profiles, settings)
└── utils/           # CommandClient, HTTPClient
```

## Based on

- [sing-box](https://github.com/SagerNet/sing-box) — universal proxy platform (GPLv3)
- [sing-box-for-android](https://github.com/SagerNet/sing-box-for-android) — reference Android client (GPLv3)

## License

GPLv3 — see [LICENSE](LICENSE)

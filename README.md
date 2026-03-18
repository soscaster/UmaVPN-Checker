# UmaVPN Checker

UmaVPN Checker is an Android app that fetches VPN server candidates from api.umavpn.top, lets you connect directly in-app via a built-in OpenVPN engine, or export profiles to an external OpenVPN client.

## Disclaimer

All VPN data is sourced from **NasuVPN Checker** (umavpn.top).

## Features

Three-tab UI:

### Servers tab
- Filter panel:
  - Country (default: JP)
  - Result count (default: 5)
  - Order by (default: Most recent)
- Required sites toggle pills (multi-select):
  - Umamusume (Japanese)
  - DMM
  - Umamusume (Global)
  - Default enabled: Umamusume (Japanese), DMM
- Accordion list results showing `country + IP`
- Lazy detail fetch on expand from `/api/server/{ip}`
- Favourite servers: pin servers to the top, persisted across launches
- Detail content:
  - ASN badge with ASN ID hyperlink to ipinfo.io
- Per-server actions:
  - **Connect** — in-app VPN connection via native OpenVPN engine
  - **Add to OpenVPN Client** — exports profile to external OpenVPN app with variant selection:
    - OPVN Current
    - OPVN Beta
    - OPVN Legacy

### Connection tab
- Live connection state (Disconnected / Connecting / Connected / Error)
- Connected server endpoint and real-time ↑/↓ speed
- Disconnect button
- Collapsible connection log with copy/clear actions

### Settings tab
- Per-app VPN routing (allowed apps list with search)
- Auto-connect on boot toggle
- Open source component attributions

## API Mapping

List endpoint:

`https://api.umavpn.top/api/server?sites=uma&sites=dmm&take=5&orderBy=timestamp&country=JP`

Detail endpoint:

`https://api.umavpn.top/api/server/{ip}`

OpenVPN config endpoint:

`https://api.umavpn.top/api/server/{ip}/config?variant={current|beta|legacy}`

OpenVPN import URI (external client):

`openvpn://import-profile/https://api.umavpn.top/api/server/{ip}/config?variant={current|beta|legacy}`

## Tech Stack

- Kotlin 2.1.0
- Android Gradle Plugin 8.8.0
- Jetpack Compose Material 3
- ViewModel + StateFlow
- Retrofit + OkHttp + Gson
- AndroidX DataStore Preferences
- Native VPN engines via JNI/NDK (OpenVPN3 + ics-openvpn)
- minSdk 29, targetSdk 36, compileSdk 36

## Build

Standard build (no native VPN engine):

```powershell
.\gradlew.bat assembleDebug
```

With in-app OpenVPN3 engine:

```powershell
.\gradlew.bat assembleDebug -PenableNativeOpenVpn=true
```

With both OpenVPN3 (primary) and ics-openvpn / OpenVPN2 (fallback):

```powershell
.\gradlew.bat assembleDebug -PenableNativeOpenVpn=true -PenableNativeOpenVpn2=true
```

To prepare Android native dependencies (vcpkg + MbedTLS + LZ4 etc.), run once before the first native build:

```powershell
.\scripts\bootstrap-openvpn-native-deps.ps1
```

Prerequisite on Windows: install Visual Studio Build Tools with C++ workload
(`Microsoft.VisualStudio.Component.VC.Tools.x86.x64`) so vcpkg can build host tools.

For production validation, follow the runtime checklist in `NATIVE_RUNTIME_CHECKLIST.md`.

## Open Source & License Notices

This project embeds open-source components under various licenses. Full notices are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Key components:
- **OpenVPN3 Core** — AGPL-3.0 — OpenVPN Technologies, Inc.
- **ics-openvpn** — AGPL-2.0 — Arne Schwabe
- **MbedTLS** — Apache 2.0 — ARM
- **OpenSSL** — Apache 2.0 — OpenSSL Project
- **LZ4** — BSD 2-Clause — Yann Collet
- **LZO / miniLZO** — GPL-2.0 — Markus F.X.J. Oberhumer
- **ASIO** — Boost Software License 1.0 — Christopher Kohlhoff
- **{fmt}** — MIT — Victor Zverovich
- **Retrofit2 / OkHttp3** — Apache 2.0 — Square, Inc.
- **Kotlin Coroutines** — Apache 2.0 — JetBrains
- **Jetpack Compose / AndroidX** — Apache 2.0 — Google

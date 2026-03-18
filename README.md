# UmaVPN Checker

UmaVPN Checker is a single-screen Android app that fetches VPN server candidates from api.umavpn.top, applies filters, and lets you import profiles directly into OpenVPN Client.

## Features

- One-screen flow only (no tabs, no splash)
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
- Detail content:
  - Speed badge
  - Ping badge
  - ASN badge with ASN ID hyperlink to ipinfo.io
- Add to OpenVPN Client with variant selection:
  - OPVN Current
  - OPVN Beta
  - OPVN Legacy

## API Mapping

List endpoint pattern:

`https://api.umavpn.top/api/server?sites=uma&sites=dmm&take=5&orderBy=timestamp&country=JP`

Detail endpoint pattern:

`https://api.umavpn.top/api/server/{ip}`

OpenVPN import URI pattern:

`openvpn://import-profile/https://api.umavpn.top/api/server/{ip}/config?variant={current|beta|legacy}`

## Tech Stack

- Kotlin 2.1.0
- Android Gradle Plugin 8.8.0
- Jetpack Compose Material 3
- ViewModel + StateFlow
- Retrofit + OkHttp + Gson
- minSdk 29, targetSdk 36, compileSdk 36

## Build

From project root:

```powershell
.\gradlew.bat assembleDebug
```

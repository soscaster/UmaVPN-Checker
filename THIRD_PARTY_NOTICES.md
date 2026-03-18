# Third-Party Notices

All VPN data is sourced from **NasuVPN Checker** (umavpn.top).

This project includes open-source components. Their licenses and attribution notices are listed below.

---

## 1 — Native VPN Engines

### OpenVPN3 Core

- **Component**: OpenVPN3 Core Library
- **Submodule path**: `app/src/main/cpp/vendor/openvpn3`
- **Upstream**: https://github.com/OpenVPN/openvpn3.git
- **Copyright**: OpenVPN Technologies, Inc. and contributors
- **License**: AGPL-3.0
- **License text**: `app/src/main/cpp/vendor/openvpn3/LICENSE.md`
- **Usage**: Primary in-app VPN tunnel engine (`umavpn_openvpn.so`) via JNI bridge (`OpenVpnJni.cpp`, `OpenVpnNativeBridge.kt`).

### ics-openvpn (OpenVPN for Android)

- **Component**: ics-openvpn (OpenVPN2-based Android client)
- **Submodule path**: `app/src/main/cpp/vendor/ics-openvpn`
- **Upstream**: https://github.com/schwabe/ics-openvpn.git
- **Author**: Arne Schwabe and contributors
- **License**: AGPL-2.0
- **License text**: `app/src/main/cpp/vendor/ics-openvpn/doc/LICENSE.txt`
- **Usage**: Fallback in-app VPN tunnel engine (`umavpn_openvpn2.so`) via JNI bridge (`OpenVpn2Jni.cpp`, `OpenVpn2NativeBridge.kt`). Only the C sources under `main/src/main/cpp/` are compiled; the Java/Kotlin layers of ics-openvpn are not used.
- **Build gate**: Requires `-PenableNativeOpenVpn2=true` Gradle property and the submodule to be initialized.

### LZO / miniLZO

- **Component**: LZO real-time data compression library (miniLZO subset)
- **Path**: `app/src/main/cpp/vendor/ics-openvpn/main/src/main/cpp/lzo/`
- **Author**: Markus F.X.J. Oberhumer
- **License**: GPL-2.0
- **License text**: `app/src/main/cpp/vendor/ics-openvpn/main/src/main/cpp/lzo/COPYING`

### LZ4

- **Component**: LZ4 — Extremely Fast Compression algorithm
- **Path**: `app/src/main/cpp/vendor/ics-openvpn/main/src/main/cpp/lz4/`
- **Author**: Yann Collet
- **License**: BSD 2-Clause (library), GPL-2.0 (programs/tools — not compiled)
- **License text**: `app/src/main/cpp/vendor/ics-openvpn/main/src/main/cpp/lz4/LICENSE`
- **Note**: Only the `lib/` subdirectory (BSD 2-Clause) is compiled into the app.

---

## 2 — Cryptography

### MbedTLS

- **Component**: MbedTLS cryptographic library
- **Path**: `app/src/main/cpp/vendor/ics-openvpn/main/src/main/cpp/mbedtls/`
- **Author**: ARM Limited and contributors
- **License**: Apache 2.0
- **License text**: `app/src/main/cpp/vendor/ics-openvpn/main/src/main/cpp/mbedtls/LICENSE`

### OpenSSL

- **Component**: OpenSSL cryptography and TLS library
- **Path**: `app/src/main/cpp/vendor/ics-openvpn/main/src/main/cpp/openssl/`
- **Author**: OpenSSL Project and contributors
- **License**: Apache 2.0
- **License text**: `app/src/main/cpp/vendor/ics-openvpn/main/src/main/cpp/openssl/NOTICE`

---

## 3 — Utilities

### ASIO

- **Component**: ASIO — Asynchronous I/O library (header-only, standalone)
- **Author**: Christopher Kohlhoff
- **License**: Boost Software License 1.0
- **Source**: Distributed via vcpkg; also present in ics-openvpn sources.

### {fmt}

- **Component**: {fmt} — Fast and safe formatting library
- **Author**: Victor Zverovich and contributors
- **License**: MIT
- **Source**: Distributed via vcpkg.

---

## 4 — Android / Kotlin Libraries

All libraries in this section are licensed under the **Apache License 2.0**.

| Library | Version | Author |
|---------|---------|--------|
| Retrofit2 | 2.11.0 | Square, Inc. |
| OkHttp3 | 4.12.0 | Square, Inc. |
| OkHttp3 Logging Interceptor | 4.12.0 | Square, Inc. |
| Kotlin Coroutines for Android | 1.10.1 | JetBrains |
| Jetpack Compose BOM | 2025.02.00 | Google |
| Compose Material3 | (BOM) | Google |
| Compose Material Icons Extended | (BOM) | Google |
| Compose UI | (BOM) | Google |
| AndroidX Core KTX | 1.16.0 | Google |
| AndroidX Activity Compose | 1.10.1 | Google |
| AndroidX Lifecycle (runtime, compose, viewmodel) | 2.9.0 | Google |
| AndroidX Navigation Compose | 2.8.9 | Google |
| AndroidX DataStore Preferences | 1.1.1 | Google |
| Material Design Components | 1.12.0 | Google |

---

## 5 — AGPL Compliance

The native VPN engines (OpenVPN3 and ics-openvpn) are licensed under the GNU Affero General Public License (AGPL). In accordance with AGPL obligations:

- The complete corresponding source code for this application, including the vendored submodules, is made available to users who receive the compiled application.
- The full text of the AGPL-3.0 license is available at: https://www.gnu.org/licenses/agpl-3.0.html
- The full text of the AGPL-2.0 license is available at: https://www.gnu.org/licenses/old-licenses/gpl-2.0.html

To initialize the submodules from source:
```
git submodule update --init --recursive
```

# Third-Party Notices

This project embeds two native VPN engines via JNI, both requiring AGPL compliance.

---

## OpenVPN3 Core

- **Component**: OpenVPN3 Core Library
- **Submodule path**: `app/src/main/cpp/vendor/openvpn3`
- **Upstream**: https://github.com/OpenVPN/openvpn3.git
- **Copyright**: OpenVPN Technologies, Inc. and contributors
- **License**: AGPL-3.0
- **Usage**: Primary in-app VPN tunnel engine (`umavpn_openvpn.so`) via JNI bridge (`OpenVpnJni.cpp`, `OpenVpnNativeBridge.kt`).

---

## ics-openvpn (OpenVPN for Android)

- **Component**: ics-openvpn (OpenVPN2-based Android client)
- **Submodule path**: `app/src/main/cpp/vendor/ics-openvpn`
- **Upstream**: https://github.com/schwabe/ics-openvpn.git
- **Author**: Arne Schwabe and contributors
- **License**: AGPL-2.0
- **Usage**: Fallback in-app VPN tunnel engine (`umavpn_openvpn2.so`) via JNI bridge (`OpenVpn2Jni.cpp`, `OpenVpn2NativeBridge.kt`). Only the C sources under `main/src/main/cpp/` are compiled; the Java/Kotlin layers of ics-openvpn are not used.
- **Build gate**: Requires `-PenableNativeOpenVpn2=true` Gradle property and the submodule to be initialized.

---

## AGPL Compliance

Both libraries are licensed under the GNU Affero General Public License (AGPL). In accordance with AGPL obligations:

- The complete corresponding source code for this application, including the vendored submodules, is made available to users who receive the compiled application.
- The full text of the AGPL-3.0 license is available at: https://www.gnu.org/licenses/agpl-3.0.html
- The full text of the AGPL-2.0 license is available at: https://www.gnu.org/licenses/old-licenses/gpl-2.0.html

To initialize the submodules from source:
```
git submodule update --init --recursive
```

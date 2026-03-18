# UmaVPN Native OpenVPN Runtime Checklist

## Scope
This checklist verifies production behavior when running native OpenVPN integration (`-PenableNativeOpenVpn=true`) on real devices.

## Preconditions
- Device: Android 10+ (API 29+), battery optimization disabled for app during tests.
- Build variant installed from native-enabled build.
- At least one known-good VPN profile (IP + variant) in app.
- At least two test apps installed for Allowed Apps verification (for example browser A and browser B).
- Test networks available:
  - Stable Wi-Fi
  - Mobile data
  - Optional secondary Wi-Fi AP

## Build and Install
1. Build native mode:
   - `./gradlew :app:assembleDebug -PenableNativeOpenVpn=true`
2. Install:
   - `./gradlew :app:installDebug -PenableNativeOpenVpn=true`
3. Launch app and confirm no startup crash.

## Baseline Functional Tests
1. Connect success path
   - Select a server and connect.
   - Expect state: `Connecting` -> `Connected`.
   - Verify foreground notification appears.
2. Disconnect success path
   - Disconnect from app.
   - Expect state: `Disconnected`.
   - Verify notification removed.
3. Reconnect same profile
   - Connect, disconnect, connect again.
   - Ensure no stale state and no crash.

## TunBuilder/Route/DNS Behavior
1. Inspect native events
   - Confirm app receives tun builder diagnostics (`TUN_BUILDER_SUMMARY`) after connect attempt.
   - Confirm no fatal callback errors in normal profile.
2. DNS behavior
   - While connected, resolve public domains and internal domains if provided by profile.
   - Ensure DNS queries succeed for expected domains.
3. Route behavior
   - Validate full-tunnel or split-tunnel behavior matches profile pushes.
   - Confirm excluded routes remain reachable outside tunnel where expected.

## Allowed Apps Policy
1. Allow-list only one app
   - In settings, select only app A.
   - Connect VPN and test traffic:
     - App A should use tunnel.
     - App B should bypass tunnel.
2. Change allow-list during disconnected state
   - Update selections.
   - Reconnect and verify new policy applies.
3. Empty allow-list behavior
   - Clear selection and reconnect.
   - Confirm expected default behavior from app policy.

## Network Transition and Stability
1. Wi-Fi -> mobile data handoff
   - While connected, disable Wi-Fi and continue traffic.
   - Expect auto-recovery without app crash.
2. Mobile data -> Wi-Fi handoff
   - Re-enable Wi-Fi.
   - Ensure session remains usable or reconnects gracefully.
3. Temporary network loss
   - Enable airplane mode briefly then disable.
   - Verify state changes are surfaced and app can recover.

## Process and Lifecycle Robustness
1. App background/foreground
   - Put app in background for 10+ minutes while connected.
   - Bring app foreground and verify accurate state.
2. Force-stop app behavior
   - Force-stop app while connected.
   - Relaunch app and validate safe recovery path (disconnected or reconnect flow).
3. Device reboot behavior
   - Reboot device.
   - Ensure service and app state are sane post-boot.

## Error Paths
1. Invalid config
   - Trigger connect with invalid/empty profile.
   - Expect clear error and no service crash.
2. Native library unavailable
   - Build without native flag and validate fallback error messaging remains clear.
3. Socket protection failure simulation
   - Confirm failure surfaces as explicit error and session does not hang indefinitely.

## Performance and Resource Checks
1. Long session soak
   - Keep connection active for 1-2 hours with periodic traffic.
   - Monitor memory growth and ANR risk.
2. Reconnect loop stress
   - Perform 20 rapid connect/disconnect cycles.
   - Confirm no crash, deadlock, or permanent broken state.

## Security Validation
1. Leak checks
   - Verify no unintended traffic leaks for tunnel-routed apps when connected.
2. Permission checks
   - Confirm VPN permission flow is required and respected.
3. Log hygiene
   - Ensure sensitive credentials are not emitted in logs.

## Pass Criteria
- No crashes or ANRs across all sections.
- Connect/disconnect/reconnect reliability >= 99% in repeated manual cycles.
- Allowed Apps behavior matches configured policy.
- DNS and route behavior align with profile pushes.
- All fatal native events are actionable and user-visible.

## Reporting Template
- Device model / Android version:
- Build fingerprint / app version:
- Profile used:
- Test case ID:
- Result: PASS or FAIL
- Evidence: log excerpt, screenshot, reproduction steps
- Notes:

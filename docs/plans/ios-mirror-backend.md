# iOS Mirror Backend

Status: implemented and verified on iPhone 12 mini, created 2026-06-04.

## Scope

Add an optional iOS real-device backend that uses macOS iPhone Mirroring through
Mirroir MCP for visual capture and input, while using `devicectl` for app
installation, launch, URL opening, and uninstall. Mirroir MCP is the only
external automation boundary that Maestro calls. CUA integration, when enabled,
lives inside the Entertech Mirroir MCP fork as a backend implementation detail.
This backend is selected with:

```bash
MAESTRO_IOS_REAL_BACKEND=mirror
```

The existing XCTest backend remains the default for iOS real devices.

## Impacted Modules

- `maestro-cli/`: real iOS device backend selection and XCTest setup bypass for
  the mirror backend.
- `maestro-ios/`: mirror-backed `IOSDevice` implementation, Mirroir MCP stdio
  client, and OCR-to-hierarchy adapter.
- `maestro-ios-driver/`: no interface change; the existing `IOSDevice` contract
  is reused.
- `Entertech/mirroir-mcp`: optional optimized backend layer. The fork pins
  upstream `trycua/cua` and exposes `native`, `cua-capture`, and
  `cua-input-experimental` modes through Mirroir MCP.

No App to embedded protocol, `schema`, `rpc`, or shared audio compatibility
considerations are involved.

## Repository Split

- `Entertech/Maestro` owns real iOS device lifecycle, Maestro CLI/MCP session
  wiring, and the `IOSDevice` adapter that speaks MCP stdio to Mirroir.
- `Entertech/mirroir-mcp` owns iPhone Mirroring window discovery, screenshots,
  OCR description, HID input, and CUA hybrid backend selection.
- `Entertech/cua` is not forked for this phase. Maestro depends on CUA only
  indirectly through the Mirroir fork, and the Mirroir fork pins upstream
  `trycua/cua` by SwiftPM revision. Fork CUA only if real-device evidence shows
  its lower-level event delivery needs a source patch.

## Backend Split

Mirroir MCP provides the adb-like visual/input side:

- screenshot
- OCR screen description
- tap
- long press
- swipe
- text input
- special keys supported by iPhone Mirroring
- Home
- screen recording

`devicectl` provides host/device management where public Apple tooling exists:

- app install from zipped `.app`
- app uninstall
- app launch by bundle identifier
- app termination by resolving the running PID from bundle metadata and
  `devicectl device info processes`
- app data clearing through `devicectl device copy to --domain-type
  appDataContainer --remove-existing-content true`
- Safari URL opening through `--payload-url`
- orientation setting through `devicectl device orientation set` on devices that
  support CoreDevice orientation control

## Runtime Contract

Environment variables:

- `MAESTRO_IOS_REAL_BACKEND=mirror`: select this backend for real iOS devices.
- `MAESTRO_IOS_MIRROR_DEVICE_ID`: optional CoreDevice UUID, name, or other
  `devicectl --device` value for the mirrored iPhone. Use this when multiple
  iPhones are paired; MCP `list_devices` filters to this device, and a flow can
  use the Mirroir target id `iphone` while `devicectl` operations still target
  the selected real device.
- `MAESTRO_IOS_MIRROR_MCP_COMMAND`: optional command used to start Mirroir MCP.
  Defaults to `mirroir-mcp` when available, otherwise `npx -y mirroir-mcp@0.33.3`.
  For the Entertech CUA-enabled fork, point this at the local fork binary during
  validation:
  `/Volumes/CSVolume/Documents/entertech/mirroir-mcp/.build/debug/mirroir-mcp`.
- `MAESTRO_IOS_MIRROR_MCP_TIMEOUT_MS`: optional MCP request timeout.

Mirroir itself is fail-closed. Mutating tools such as `tap`, `swipe`, and
`type_text` must be allowed in `~/.mirroir-mcp/permissions.json` or an equivalent
project-local permission file.

Mirroir fork backend variables:

- `MIRROIR_BACKEND_MODE=native`: current Mirroir behavior and default safety
  path. Capture and input both use existing Mirroir native implementations.
- `MIRROIR_BACKEND_MODE=cua-capture`: CUA/ScreenCaptureKit handles iPhone
  Mirroring window capture and window state, while input still uses Mirroir HID.
  This is the preferred reduced-focus hybrid mode.
- `MIRROIR_BACKEND_MODE=cua-input-experimental`: CUA also attempts pixel/text/key
  input. Each CUA input action must pass post-action screenshot verification or
  fail explicitly. It is not a default production input path.
- `MIRROIR_CUA_INPUT_VERIFIED=true`: metadata flag used by Mirroir MCP
  `check_health` and `list_targets` after a real-device CUA input run has been
  proven for the current host/device path.

`check_health` and `list_targets` expose `capture_backend`, `input_backend`, and
`cua_input_verified` style metadata so Maestro and MCP clients can see which
Mirroir backend is active. Maestro does not branch on CUA details; it only
invokes the configured Mirroir MCP command.

## iOS Implementation Logic

The backend is deliberately split at the existing Maestro `IOSDevice` boundary
instead of adding another high-level driver. `IOSDriver` still receives an
`IOSDevice`, and the new implementation is selected only for real iOS devices
when `MAESTRO_IOS_REAL_BACKEND=mirror`.

Runtime selection:

- `MaestroSessionManager` checks `MAESTRO_IOS_REAL_BACKEND`. For real iOS
  sessions with value `mirror` or `mirroir`, it constructs
  `IOSDriver(MirrorIOSDevice(deviceId), CliInsights)` and skips the XCTest
  runner build/install/setup path.
- Device discovery is relaxed for this mode. If a device id is provided, or
  `MAESTRO_IOS_MIRROR_DEVICE_ID` is set, CLI session selection accepts it as a
  real iOS target instead of requiring XCTest/CoreSimulator discovery to return
  it.
- `TestCommand` uses the same selector to avoid the legacy
  `DeviceService.listConnectedDevices()` rejection path. Without an explicit
  `--device`, it uses `MAESTRO_IOS_MIRROR_DEVICE_ID` when set, otherwise the
  Mirroir target id `iphone`.

MCP device/tool integration:

- `ListDevicesTool` merges Mirroir availability with `devicectl list devices`.
  When `MAESTRO_IOS_MIRROR_DEVICE_ID` is set, it returns the matching paired
  CoreDevice device, while still noting that the active iPhone Mirroring target
  is `iphone`.
- If no paired CoreDevice match is available, `ListDevicesTool` falls back to
  Mirroir targets so MCP clients can still inspect the mirror surface.
- `InspectScreenTool` now falls back to `MaestroSession.platform` when
  `session.device` is not populated, so mirror-created sessions still format as
  iOS.

Host-side iOS device implementation:

- `MirrorIOSDevice` implements `device.IOSDevice`.
- `open()` checks Mirroir health before running. It fails closed with guidance
  if iPhone Mirroring is paused, disconnected, or missing required macOS
  permissions.
- `deviceInfo()` combines Mirroir target/window size with screenshot pixel
  dimensions and `devicectl device info displays` when available. The verified
  iPhone 12 mini mirror surface is `294x649` points with a `588x1298` capture.
- `viewHierarchy()` calls Mirroir `describe_screen` and converts OCR points into
  a synthetic `ViewHierarchy`. The parser intentionally creates small frames
  around OCR points; it is a selector aid, not a native accessibility tree.
- `tap`, `longPress`, `scroll`, `input`, `pressKey`, and `pressButton(home)` map
  directly to Mirroir MCP tools. Point gestures pass `cursor_mode=direct` where
  Mirroir supports it.
- `takeScreenshot()` decodes the image content from Mirroir `screenshot`.
- `startScreenRecording()` delegates to Mirroir `start_recording` /
  `stop_recording`, then streams the returned `.mov` file to Maestro.
- `install()` accepts Maestro's zipped `.app` stream, extracts it to a safe temp
  directory, finds the contained `.app`, and installs it with `devicectl`.
- `uninstall()`, `launch()`, `stop()`, `openLink()`, `clearAppState()`, and
  `setOrientation()` are handled by `DevicectlIOSClient`.
- `clearAppState()` terminates the app, then uses `devicectl device copy to`
  with `--domain-type appDataContainer --remove-existing-content true` and an
  empty temp directory. This gives a practical real-device equivalent of clearing
  app data without uninstalling the app.

Support classes:

- `MirroirMcpClient` is a small persistent stdio JSON-RPC client. It initializes
  MCP protocol `2024-11-05`, calls `tools/call`, handles text/image content, and
  times out failed requests instead of hanging Maestro.
- `DevicectlIOSClient` wraps `xcrun devicectl`, writes JSON output through temp
  files for commands that need structured data, and keeps plain command failures
  visible to the caller.
- `MirrorScreenParser` parses Mirroir target sizes, OCR lines from
  `describe_screen`, and recording paths from Mirroir output.

## Focus Behavior

With upstream native Mirroir, the implemented backend delegates screenshot, OCR
hierarchy, and input to Mirroir MCP. Mirroir operates the real iPhone Mirroring
app window, and its `screenshot`, `check_health`, `tap`, and related calls can
bring that window to the foreground. Maestro does not explicitly activate iPhone
Mirroring, but the focus change is still observable because it is inherited from
the Mirroir tool path.

The Entertech Mirroir fork adds `cua-capture` to move screenshots and window
state reads to CUA/ScreenCaptureKit. This path is intended to avoid changing the
Mac frontmost application for read-only capture/describe operations, while
leaving iPhone input on Mirroir HID.

CUA has a different input model: `cua-driver` targets a specific
`pid/window_id` and can post window-local pixel clicks/drags to that process via
macOS Accessibility/CGEvent APIs. That is enough for ordinary macOS windows and
some iPhone Mirroring chrome, but current verification showed it is not enough
to drive the mirrored iOS framebuffer itself. The reliable iOS input path remains
Mirroir MCP, and that path can still bring iPhone Mirroring to the foreground.

## Capability Boundaries

This backend does not expose a native XCTest accessibility tree. `viewHierarchy`
is synthesized from Mirroir OCR output, so text-based selectors can work when
OCR sees the target, but it is not equivalent to XCTest snapshots.

Unsupported or partial real-device operations:

- `clearKeychain`
- `setLocation`
- `setPermissions` is a no-op for launch compatibility; runtime permission
  prompts must be handled visually through the mirror session.
- `addMedia`

Unsupported operations fail explicitly instead of silently pretending to work.

## Validation State

Validated on 2026-06-04:

- Repository was updated to `origin/main` at `48b126917b0241069f748019da5b2ccde4cd3044`.
- iPhone 12 mini was paired through CoreDevice:
  `1D533177-5153-5A0C-9102-8D0A8ADDAEFB`, model `iPhone13,1`.
- `xcrun devicectl device info lockState --device
  1D533177-5153-5A0C-9102-8D0A8ADDAEFB` acquired a tunnel connection and
  returned lock-state data instead of the previous RemotePairing error.
- Existing XCTest real-device setup failed before runner launch because local
  signing/provisioning was not available for the tested teams.
- `xcrun devicectl device process launch --device
  1D533177-5153-5A0C-9102-8D0A8ADDAEFB com.apple.Preferences` launched Settings.
- `mirroir-mcp@0.33.3 doctor --json` passed all five checks after iPhone
  Mirroring was resumed: macOS version, iPhone Mirroring running, mirroring
  connected at `294x649` portrait, Screen Recording permitted, Accessibility
  permitted.
- Direct Mirroir MCP smoke passed: `list_targets`, `screenshot`,
  `describe_screen`, and `tap` returned successful results against target
  `iphone`.
- Maestro CLI hierarchy passed:
  `MAESTRO_IOS_REAL_BACKEND=mirror ./maestro ... --platform ios --device
  1D533177-5153-5A0C-9102-8D0A8ADDAEFB hierarchy` returned a synthesized
  hierarchy with root bounds `[0,0][294,649]`.
- Maestro MCP smoke passed with `MAESTRO_IOS_MIRROR_DEVICE_ID` set to the 12
  mini CoreDevice UUID:
  - `list_devices` returned only `Borealin’s iPhone 12 mini`.
  - `inspect_screen` returned compact iOS hierarchy JSON.
  - `take_screenshot` returned JPEG image content.
  - inline `launchApp` + `tapOn(point)` flow returned `Flow executed successfully`.
  - inline `openLink: https://example.com` + `tapOn(point)` flow returned
    `Flow executed successfully`.
- Maestro CLI `test` passed on `/tmp/ios-mirror-smoke.yaml`:
  `Launch app "com.apple.Preferences" without stopping app... COMPLETED` and
  `Tap on point (50%,50%)... COMPLETED`.
- A developer-signed temporary app was built for the 12 mini with bundle id
  `dev.mobile.maestro-mirror-smoke`:
  `xcodebuild build -project maestro-ios-xctest-runner/maestro-driver-ios.xcodeproj
  -scheme maestro-driver-ios -configuration Debug -destination
  id=1D533177-5153-5A0C-9102-8D0A8ADDAEFB -derivedDataPath
  /tmp/maestro-ios-mirror-smoke-derived DEVELOPMENT_TEAM=B6Y9D6S4KK
  CODE_SIGN_STYLE=Automatic CODE_SIGN_IDENTITY="Apple Development"
  PRODUCT_BUNDLE_IDENTIFIER=dev.mobile.maestro-mirror-smoke` returned
  `** BUILD SUCCEEDED **`.
- `xcrun devicectl device install app --device
  1D533177-5153-5A0C-9102-8D0A8ADDAEFB
  /tmp/maestro-ios-mirror-smoke-derived/Build/Products/Debug-iphoneos/maestro-driver-ios.app`
  installed `dev.mobile.maestro-mirror-smoke`.
- Maestro CLI `test` passed against the installed temporary app:
  `Launch app "dev.mobile.maestro-mirror-smoke" without stopping app...
  COMPLETED` and `Tap on point (50%,50%)... COMPLETED`.
- Maestro CLI `clearState` passed against the installed temporary app, then
  relaunched the app successfully.
- `xcrun devicectl device uninstall app --device
  1D533177-5153-5A0C-9102-8D0A8ADDAEFB dev.mobile.maestro-mirror-smoke`
  returned `App uninstalled.`, and a follow-up `devicectl device info apps
  --bundle-id dev.mobile.maestro-mirror-smoke` returned `matching_apps 0`.
- Build verification passed:
  `./gradlew :maestro-ios:test :maestro-cli:compileKotlin :maestro-cli:installDist`.
- CLI test verification passed:
  `./gradlew :maestro-cli:test`.
- CUA reduced-focus investigation on the same iPhone 12 mini:
  `cua-driver list_windows` found the live iPhone Mirroring window at
  `pid=87920`, `window_id=10996`, bounds `294x649`, and
  `cua-driver get_window_state` captured a `588x1298` PNG without changing the
  macOS frontmost app.
- A temporary text-field app (`dev.mobile.maestro-mirror-text-smoke`) was built
  and installed to validate CUA input. A CUA-backed `tap + inputText` flow
  reported command success and preserved macOS focus
  (`frontmost_before:Google Chrome`, `frontmost_after:Google Chrome`), but the
  follow-up CUA screenshot still showed the placeholder `Mirror text smoke` and
  did not contain the typed `mirrorcua42` text. This proves process-targeted CUA
  events are not a reliable replacement for Mirroir's iOS input tools.
- Entertech Mirroir fork validation added after the initial Maestro backend:
  `swift build`, targeted backend unit tests, and `swift test --skip
  IntegrationTests` cover backend selection, CUA capture fallback, experimental
  input post-action verification, and MCP health/list target metadata.
- Maestro can use the fork by setting
  `MAESTRO_IOS_MIRROR_MCP_COMMAND=/Volumes/CSVolume/Documents/entertech/mirroir-mcp/.build/debug/mirroir-mcp`
  and selecting `MIRROIR_BACKEND_MODE=cua-capture`. The Maestro side remains
  unchanged because the MCP tool protocol is reused.
- With the local fork, Mirroir MCP `list_targets`, `check_health`, and
  `screenshot` passed in `cua-capture` mode. `list_targets` reported
  `capture=cua-capture input=native-hid cua_input_verified=false` for the active
  `iphone` target, and `screenshot` returned PNG data while the Mac frontmost
  application stayed `LookInside` before and after the call.
- With the same local fork command, Maestro CLI `hierarchy` passed on the iPhone
  12 mini and returned root bounds `[0,0][294,649]`.
- Maestro CLI `test` passed on a Settings smoke flow:
  `Launch app "com.apple.Preferences"... COMPLETED` and
  `Tap on point (50%,50%)... COMPLETED`.
- A developer-signed temporary app with bundle id
  `dev.mobile.maestro-mirror-smoke` was rebuilt, installed through
  `devicectl`, then driven through Maestro in `cua-capture` mode:
  `launchApp`, `tapOn(point)`, `clearState`, and relaunch all completed. A
  follow-up `devicectl device uninstall app` removed the app and
  `devicectl device info apps --bundle-id dev.mobile.maestro-mirror-smoke`
  returned no installed app rows.
- `inputText` in `cua-capture` mode reached the Settings search field through
  Mirroir native HID input; the screenshot showed `Mirrorfork42`. Maestro's OCR
  `assertVisible` did not reliably match that bottom search field, so this
  remains screenshot-verified input evidence rather than a stable selector
  assertion.

Remaining validation for production hardening:

- Add broader E2E coverage for Mirroir text entry and system prompt handling.

## Rejected Alternatives

- Replace the existing XCTest backend: rejected because XCTest remains the
  richer accessibility backend when signing and runner transport are working.
- Use only `devicectl`: rejected because public `devicectl` does not provide
  arbitrary touch input, screenshots, or hierarchy inspection like adb.

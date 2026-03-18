param(
    [switch]$EnableNativeOpenVpn,
    [switch]$EnableNativeOpenVpn2
)

. "$PSScriptRoot\common.ps1"

Set-AndroidCliEnvironment
$gradleArgs = @('assembleDebug')
if ($EnableNativeOpenVpn) {
    $gradleArgs += '-PenableNativeOpenVpn=true'
}
if ($EnableNativeOpenVpn2) {
    $gradleArgs += '-PenableNativeOpenVpn2=true'
}

Invoke-Gradle -Arguments $gradleArgs
Assert-AdbDeviceReady

$apkPath = Join-Path (Get-ProjectRoot) 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apkPath)) {
    throw "APK not found: $apkPath"
}

& adb install -r $apkPath
if ($LASTEXITCODE -ne 0) {
    throw 'adb install failed.'
}

$component = 'com.umavpn.checker/com.umavpn.checker.MainActivity'
& adb shell am start -n $component
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to launch app activity via adb.'
}

Write-Host 'Build + install + launch completed.' -ForegroundColor Green

. "$PSScriptRoot\common.ps1"

Set-AndroidCliEnvironment
Invoke-Gradle -Arguments @('assembleDebug')
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

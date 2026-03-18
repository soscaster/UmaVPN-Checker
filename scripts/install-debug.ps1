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

Write-Host 'Debug APK installed successfully.' -ForegroundColor Green

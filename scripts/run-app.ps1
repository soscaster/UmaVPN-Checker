. "$PSScriptRoot\common.ps1"

Set-AndroidCliEnvironment
Assert-AdbDeviceReady

$component = 'com.umavpn.checker/com.umavpn.checker.MainActivity'
& adb shell am start -n $component
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to launch app activity via adb.'
}

Write-Host 'App launched.' -ForegroundColor Green

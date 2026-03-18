. "$PSScriptRoot\common.ps1"

Set-AndroidCliEnvironment

Write-Host 'Starting logcat filter: com.umavpn.checker' -ForegroundColor Cyan
& adb logcat | Select-String -Pattern 'com\.umavpn\.checker' -SimpleMatch:$false

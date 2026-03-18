. "$PSScriptRoot\common.ps1"

Set-AndroidCliEnvironment
Invoke-Gradle -Arguments @('assembleDebug')
Write-Host 'Debug APK built successfully.' -ForegroundColor Green

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
Write-Host 'Debug APK built successfully.' -ForegroundColor Green

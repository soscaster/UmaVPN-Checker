. "$PSScriptRoot\common.ps1"

Set-AndroidCliEnvironment
Assert-AdbDeviceReady

$scrcpy = Get-Command scrcpy -ErrorAction SilentlyContinue
if (-not $scrcpy) {
    $wingetPackageDir = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages\Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe'
    if (Test-Path $wingetPackageDir) {
        $scrcpyExe = Get-ChildItem $wingetPackageDir -Recurse -Filter 'scrcpy.exe' -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($scrcpyExe) {
            $scrcpy = [PSCustomObject]@{ Source = $scrcpyExe.FullName }
        }
    }
}

if (-not $scrcpy) {
    throw 'scrcpy not found in PATH. Install it first (example: winget install Genymobile.scrcpy) and reopen terminal.'
}

& $scrcpy.Source
if ($LASTEXITCODE -ne 0) {
    throw 'scrcpy exited with non-zero code.'
}

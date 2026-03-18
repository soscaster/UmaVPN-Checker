$ErrorActionPreference = 'Stop'

function Set-AndroidCliEnvironment {
    param(
        [string]$JavaHome,
        [string]$AndroidSdkRoot
    )

    if (-not $JavaHome) {
        $JavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
    }
    if (-not $JavaHome -or -not (Test-Path $JavaHome)) {
        $zulu = Get-ChildItem 'C:\Program Files\Zulu' -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            Select-Object -First 1
        if ($zulu) {
            $JavaHome = $zulu.FullName
        }
    }

    if (-not $AndroidSdkRoot) {
        $AndroidSdkRoot = [Environment]::GetEnvironmentVariable('ANDROID_SDK_ROOT', 'User')
    }
    if (-not $AndroidSdkRoot -or -not (Test-Path $AndroidSdkRoot)) {
        $AndroidSdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }

    if (-not (Test-Path $JavaHome)) {
        throw "JAVA_HOME is not configured or invalid: $JavaHome"
    }
    if (-not (Test-Path $AndroidSdkRoot)) {
        throw "ANDROID_SDK_ROOT is not configured or invalid: $AndroidSdkRoot"
    }

    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_SDK_ROOT = $AndroidSdkRoot

    $entries = @(
        "$JavaHome\bin",
        "$AndroidSdkRoot\platform-tools",
        "$AndroidSdkRoot\cmdline-tools\latest\bin"
    )

    foreach ($entry in $entries) {
        if ($env:PATH -notlike "*$entry*") {
            $env:PATH = "$entry;$env:PATH"
        }
    }
}

function Get-ProjectRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $root = Get-ProjectRoot
    Push-Location $root
    try {
        & "$root\gradlew.bat" @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle command failed: gradlew $($Arguments -join ' ')"
        }
    }
    finally {
        Pop-Location
    }
}

function Assert-AdbDeviceReady {
    & adb start-server | Out-Null
    $output = & adb devices
    if ($LASTEXITCODE -ne 0) {
        throw 'adb devices failed. Check Android platform-tools installation.'
    }

    $lines = $output -split "`r?`n" | Where-Object { $_ -match "\S+\s+(device|unauthorized|offline)$" }
    if (-not $lines) {
        throw 'No Android device detected. Connect device and enable USB debugging.'
    }

    $unauthorized = $lines | Where-Object { $_ -match "\sunauthorized$" }
    if ($unauthorized) {
        throw 'Device is unauthorized. Unlock phone and accept the USB debugging RSA prompt.'
    }

    $offline = $lines | Where-Object { $_ -match "\soffline$" }
    if ($offline) {
        throw 'Device is offline. Reconnect cable and run adb kill-server; adb start-server.'
    }

    $ready = $lines | Where-Object { $_ -match "\sdevice$" }
    if (-not $ready) {
        throw 'No authorized adb device found.'
    }
}

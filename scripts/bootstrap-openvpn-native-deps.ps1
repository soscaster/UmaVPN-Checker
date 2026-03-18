param(
    [string]$VcpkgRoot = "$PSScriptRoot\..\tools\vcpkg",
    [string]$AndroidSdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$AndroidNdkRoot = "$env:LOCALAPPDATA\Android\Sdk\ndk\27.0.12077973"
)

$ErrorActionPreference = 'Stop'

Write-Host "Bootstrapping vcpkg at: $VcpkgRoot" -ForegroundColor Cyan

if (-not (Test-Path $AndroidSdkRoot)) {
    throw "Android SDK not found: $AndroidSdkRoot"
}
if (-not (Test-Path $AndroidNdkRoot)) {
    throw "Android NDK not found: $AndroidNdkRoot"
}

$env:ANDROID_SDK_ROOT = $AndroidSdkRoot
$env:ANDROID_HOME = $AndroidSdkRoot
$env:ANDROID_NDK_HOME = $AndroidNdkRoot
$env:ANDROID_NDK_ROOT = $AndroidNdkRoot
$env:ANDROID_NDK = $AndroidNdkRoot

Write-Host "Using ANDROID_SDK_ROOT=$AndroidSdkRoot" -ForegroundColor DarkCyan
Write-Host "Using ANDROID_NDK_HOME=$AndroidNdkRoot" -ForegroundColor DarkCyan

if (-not (Test-Path $VcpkgRoot)) {
    git clone https://github.com/microsoft/vcpkg.git $VcpkgRoot
}

Push-Location $VcpkgRoot
try {
    .\bootstrap-vcpkg.bat
    if ($LASTEXITCODE -ne 0) {
        throw "bootstrap-vcpkg failed with exit code $LASTEXITCODE"
    }

    $triplets = @(
        'arm64-android',
        'arm-neon-android',
        'x64-android'
    )

    $packages = @(
        'asio',
        'fmt',
        'lz4',
        'xxhash',
        'mbedtls'
    )

    foreach ($triplet in $triplets) {
        foreach ($pkg in $packages) {
            Write-Host "Installing ${pkg}:$triplet" -ForegroundColor Yellow
            .\vcpkg install "${pkg}:$triplet"
            if ($LASTEXITCODE -ne 0) {
                throw "vcpkg install failed for ${pkg}:$triplet (exit code $LASTEXITCODE). Ensure C++ host toolchain is installed."
            }
        }
    }

    Write-Host "Native dependencies bootstrap finished." -ForegroundColor Green
}
finally {
    Pop-Location
}

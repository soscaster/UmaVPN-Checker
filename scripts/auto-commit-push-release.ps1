[CmdletBinding()]
param(
    [string]$CommitMessage = "",
    [switch]$EnableNativeOpenVpn,
    [switch]$EnableNativeOpenVpn2
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $repoRoot
$projectName = 'UmaVPN'

function Sync-DirtySubmodules {
    param(
        [string]$ParentCommitMessage
    )

    $gitmodulesPath = Join-Path $repoRoot '.gitmodules'
    if (-not (Test-Path $gitmodulesPath)) {
        return
    }

    $submodulePathLines = @()
    try {
        $submodulePathLines = @(git config --file .gitmodules --get-regexp '^submodule\..*\.path$' 2>$null)
    }
    catch {
        $submodulePathLines = @()
    }

    if (-not $submodulePathLines -or $submodulePathLines.Count -eq 0) {
        return
    }

    $configuredPaths = @()
    foreach ($line in $submodulePathLines) {
        $parts = $line -split '\s+', 2
        if ($parts.Count -eq 2 -and $parts[1].Trim()) {
            $configuredPaths += $parts[1].Trim()
        }
    }

    if (-not $configuredPaths -or $configuredPaths.Count -eq 0) {
        return
    }

    # Warn if repository index still has gitlink paths that are not mapped in .gitmodules.
    $gitlinkPaths = @()
    try {
        $gitlinkPaths = @(git ls-files --stage | Where-Object { $_ -match '^160000\s' } | ForEach-Object { ($_ -split '\s+', 4)[3] })
    }
    catch {
        $gitlinkPaths = @()
    }
    foreach ($gitlinkPath in $gitlinkPaths) {
        if ($configuredPaths -notcontains $gitlinkPath) {
            Write-Warning "Git index contains unmapped submodule path '$gitlinkPath' (missing in .gitmodules). Skipping submodule sync for it."
        }
    }

    foreach ($subPath in $configuredPaths) {
        $subRepoPath = Join-Path $repoRoot $subPath
        if (-not (Test-Path $subRepoPath)) {
            Write-Warning "Configured submodule path '$subPath' does not exist on disk. Skipping."
            continue
        }

        Push-Location $subRepoPath
        try {
            $isSubRepo = (& git rev-parse --is-inside-work-tree 2>$null)
            if ($LASTEXITCODE -ne 0 -or $isSubRepo -ne 'true') {
                Write-Warning "Path '$subPath' is not a git working tree. Skipping."
                continue
            }

            $subChanges = git status --porcelain
            if (-not $subChanges) {
                continue
            }

            $subBranch = (git branch --show-current).Trim()
            if (-not $subBranch) {
                git checkout -B main | Out-Null
                $subBranch = 'main'
            }

            git add -A
            $subCommitMessage = if ($ParentCommitMessage) {
                "$ParentCommitMessage [submodule:$subPath]"
            } else {
                "chore(submodule): sync $subPath $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
            }

            if ($subCommitMessage.Length -gt 72) {
                $subCommitMessage = "chore(submodule): sync $subPath"
            }

            git commit -m "$subCommitMessage"

            $remoteOrigin = git remote get-url origin 2>$null
            if ($LASTEXITCODE -eq 0 -and $remoteOrigin) {
                $remoteHasBranch = git ls-remote --heads origin $subBranch
                if (-not $remoteHasBranch) {
                    git push -u origin $subBranch
                } else {
                    git push origin $subBranch
                }
            } else {
                Write-Warning "Submodule '$subPath' has no origin remote; skipped push."
            }
        }
        finally {
            Pop-Location
        }
    }

    Set-Location $repoRoot
}

function Get-DefaultCommitMessage {
    return "chore: automated update $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
}

function Get-AutoCommitMessage {
    param(
        [string[]]$ChangedFiles
    )

    if (-not $ChangedFiles -or $ChangedFiles.Count -eq 0) {
        return Get-DefaultCommitMessage
    }

    $total = $ChangedFiles.Count
    $joined = ($ChangedFiles -join "`n").ToLowerInvariant()

    $type = 'chore'
    $scope = 'repo'

    if ($joined -match '(^|\n)(readme\.md|docs/)') {
        $type = 'docs'
        $scope = 'readme'
    }

    if ($joined -match '(^|\n)(app/build\.gradle\.kts|build\.gradle\.kts|settings\.gradle\.kts|gradle/)') {
        $type = 'build'
        $scope = 'gradle'
    }

    if ($joined -match '(^|\n)(scripts/|\.vscode/|\.github/)') {
        if ($type -eq 'chore') {
            $scope = 'ci'
        }
    }

    if ($joined -match '(^|\n)app/src/main/java/') {
        $type = 'feat'
        if ($joined -match '/ui/') { $scope = 'ui' }
        elseif ($joined -match '/map/') { $scope = 'map' }
        elseif ($joined -match '/camera/') { $scope = 'camera' }
        elseif ($joined -match '/service/') { $scope = 'service' }
        elseif ($joined -match '/settings/') { $scope = 'settings' }
        elseif ($joined -match '/logging/') { $scope = 'logging' }
        else { $scope = 'app' }
    }

    if ($joined -match '(^|\n)(app/src/test/|app/src/androidtest/|test/)') {
        $type = 'test'
        if ($scope -eq 'repo') { $scope = 'tests' }
    }

    $top = $ChangedFiles | Select-Object -First 2
    $summary = if ($top.Count -gt 0) { ($top -join ', ') } else { "$total files" }
    $subject = "${type}(${scope}): update $summary"

    if ($total -gt 2) {
        $subject += " (+$($total - 2) files)"
    }

    if ($subject.Length -gt 72) {
        $subject = "${type}(${scope}): update $total files"
    }

    return $subject
}

function Get-AppVersionName {
    $gradleFile = Join-Path $repoRoot 'app/build.gradle.kts'
    if (-not (Test-Path $gradleFile)) {
        throw "Cannot find app/build.gradle.kts to resolve versionName."
    }

    $content = Get-Content -Path $gradleFile -Raw
    $match = [regex]::Match($content, 'versionName\s*=\s*"([^"]+)"')
    if (-not $match.Success) {
        throw "Unable to parse versionName from app/build.gradle.kts"
    }

    return $match.Groups[1].Value.Trim()
}

function Get-NextReleaseTag {
    param(
        [string]$VersionName
    )

    $existingTags = @()
    $releaseJson = gh release list --limit 200 --json tagName 2>$null
    if ($LASTEXITCODE -eq 0 -and $releaseJson) {
        $parsed = $releaseJson | ConvertFrom-Json
        if ($parsed) {
            $existingTags = @($parsed | ForEach-Object { $_.tagName })
        }
    }

    $escapedVersion = [regex]::Escape($VersionName)
    $pattern = "^v$escapedVersion\+(\d+)$"
    $maxBuild = 0

    foreach ($tagName in $existingTags) {
        $tagMatch = [regex]::Match($tagName, $pattern)
        if ($tagMatch.Success) {
            $num = [int]$tagMatch.Groups[1].Value
            if ($num -gt $maxBuild) {
                $maxBuild = $num
            }
        }
    }

    $nextBuild = $maxBuild + 1
    return "v$VersionName+$nextBuild"
}

# Init repo if needed
$inside = (& git rev-parse --is-inside-work-tree 2>$null)
if ($LASTEXITCODE -ne 0 -or $inside -ne 'true') {
    git init -b main | Out-Null
}

# Ensure we are on main
$currentBranch = (git branch --show-current).Trim()
if (-not $currentBranch) {
    git checkout -b main | Out-Null
}
elseif ($currentBranch -ne 'main') {
    git checkout -B main | Out-Null
}

# Build APK first
$buildScript = Join-Path $PSScriptRoot 'build-debug.ps1'
$buildArgs = @(
    '-NoProfile',
    '-ExecutionPolicy', 'Bypass',
    '-File', $buildScript
)
if ($EnableNativeOpenVpn) {
    $buildArgs += '-EnableNativeOpenVpn'
}
if ($EnableNativeOpenVpn2) {
    $buildArgs += '-EnableNativeOpenVpn2'
}

& powershell @buildArgs
if ($LASTEXITCODE -ne 0) {
    throw "Build failed with exit code $LASTEXITCODE"
}

# Ensure submodule changes are committed/pushed first, then root commit captures new pointers.
Sync-DirtySubmodules -ParentCommitMessage $CommitMessage

$apkPath = Join-Path $repoRoot 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path $apkPath)) {
    throw "APK not found at $apkPath"
}

# Stage and commit changes
$hasChanges = (git status --porcelain)
if (-not $hasChanges) {
    Write-Output 'No changes to commit. Continuing with push/release steps.'
}
else {
    git add -A
    $commitMessage = $CommitMessage.Trim()
    if (-not $commitMessage) {
        $changedFiles = @(git diff --cached --name-only)
        $commitMessage = Get-AutoCommitMessage -ChangedFiles $changedFiles
    }
    $commitMessage = ($commitMessage -split "`r?`n")[0].Trim()
    $commitMessage = $commitMessage -replace 'Co-authored-by:.*', ''
    if (-not $commitMessage) { $commitMessage = Get-DefaultCommitMessage }
    if ($commitMessage.Length -gt 72) { $commitMessage = $commitMessage.Substring(0, 72).TrimEnd() }

    # Single-line message ensures no co-author trailers.
    git commit -m "$commitMessage"
}

# Ensure remote origin exists
$origin = git remote get-url origin 2>$null
if ($LASTEXITCODE -ne 0 -or -not $origin) {
    $repoName = Split-Path -Leaf $repoRoot
    gh repo create $repoName --source . --remote origin --private --description "UmaVPN Checker Android app" --confirm
}

# Push main
$mainExistsRemote = git ls-remote --heads origin main
if (-not $mainExistsRemote) {
    git push -u origin main
}
else {
    git push origin main
}

# Create a new auto-incremented release tag, e.g. v0.1.0+1, v0.1.0+2...
$versionName = Get-AppVersionName
$tag = Get-NextReleaseTag -VersionName $versionName
$releaseTitle = "$projectName $tag"
$releaseNotes = "Automated build from main on $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')."

gh release create $tag $apkPath --title $releaseTitle --notes $releaseNotes --target main

Write-Output 'AUTO_COMMIT_PUSH_RELEASE_DONE'

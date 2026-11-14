# Dot-source this file from PowerShell in the project:
#   . .\dev-shell.ps1

if (-not $env:MLNG_ROOT) {
    $env:MLNG_ROOT = $PSScriptRoot
}
if (-not $env:MLNG_APP_PORT) {
    $env:MLNG_APP_PORT = "8795"
}
if (-not $env:MLNG_VITE_PORT) {
    $env:MLNG_VITE_PORT = "5173"
}
if (-not $env:MLNG_JAR) {
    $env:MLNG_JAR = Join-Path $env:MLNG_ROOT "build\quarkus-app\quarkus-run.jar"
}

function Get-MlngProcessIds {
    $ids = [System.Collections.Generic.HashSet[int]]::new()

    foreach ($port in @([int]$env:MLNG_APP_PORT, [int]$env:MLNG_VITE_PORT)) {
        Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | ForEach-Object {
            if ($_.OwningProcess -and $_.OwningProcess -ne $PID) {
                [void]$ids.Add([int]$_.OwningProcess)
            }
        }
    }

    Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        $_.ProcessId -ne $PID -and
        $_.CommandLine -and
        $_.CommandLine.IndexOf($env:MLNG_ROOT, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        ($_.CommandLine -match 'quarkus-run\.jar|quarkusDev|npm(\.cmd)? run dev|node_modules[\\/]\.bin[\\/]vite|vite --host')
    } | ForEach-Object {
        [void]$ids.Add([int]$_.ProcessId)
    }

    $ids | Sort-Object
}

function Get-MlngLockPath {
    $databasePath = if ($env:MUSIC_LIBRARY_DB_PATH) { $env:MUSIC_LIBRARY_DB_PATH } else { "data\music-library-ng.sqlite" }
    $localConfig = Join-Path $env:MLNG_ROOT "config\application.properties"
    if (-not $env:MUSIC_LIBRARY_DB_PATH -and (Test-Path -LiteralPath $localConfig)) {
        $configuredPath = Get-Content -LiteralPath $localConfig | Where-Object { $_ -match '^\s*music-library\.db\.path\s*=' } | Select-Object -Last 1
        if ($configuredPath) {
            $databasePath = ($configuredPath -split '=', 2)[1].Trim()
        }
    }

    if (-not [System.IO.Path]::IsPathRooted($databasePath)) {
        $databasePath = Join-Path $env:MLNG_ROOT $databasePath
    }
    [System.IO.Path]::GetFullPath("$databasePath.lock")
}

function Get-MlngLockOwner {
    $lockPath = Get-MlngLockPath
    if (-not (Test-Path -LiteralPath $lockPath)) {
        return
    }

    $ownerPath = Join-Path $lockPath "owner"
    if (-not (Test-Path -LiteralPath $ownerPath -PathType Leaf)) {
        throw "Application lock has an unknown owner; verify it manually before removing it: $lockPath"
    }
    $owner = Get-Content -LiteralPath $ownerPath -Raw -ErrorAction Stop | ConvertFrom-StringData
    $ownerProcessId = 0
    if ($owner.os -notlike 'Windows*' -or -not [int]::TryParse($owner.pid, [ref]$ownerProcessId) -or $ownerProcessId -le 0 -or $ownerProcessId -eq $PID) {
        throw "Application lock belongs to WSL or an unknown process; stop that app and verify the lock manually: $lockPath"
    }
    $ownerProcess = Get-Process -Id $ownerProcessId -ErrorAction SilentlyContinue
    if ($ownerProcess -and $owner.startedAtMillis -ne ([DateTimeOffset]$ownerProcess.StartTime).ToUnixTimeMilliseconds().ToString()) {
        throw "Application lock does not match the running process identity; verify it manually: $lockPath"
    }

    [pscustomobject]@{ Path = $lockPath; ProcessId = $ownerProcessId }
}

function Remove-MlngStaleLock {
    $owner = Get-MlngLockOwner
    if (-not $owner) {
        return
    }
    if (Get-Process -Id $owner.ProcessId -ErrorAction SilentlyContinue) {
        throw "Application lock owner is still running: $($owner.ProcessId)"
    }

    $rootPath = [System.IO.Path]::GetFullPath($env:MLNG_ROOT).TrimEnd([System.IO.Path]::DirectorySeparatorChar)
    $lockPath = $owner.Path
    if (-not $lockPath.StartsWith("$rootPath$([System.IO.Path]::DirectorySeparatorChar)", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Stale lock is outside the project; verify and remove it manually: $lockPath"
    }

    Write-Host "Removing stale application lock: $lockPath"
    Remove-Item -LiteralPath (Join-Path $lockPath "owner") -Force -ErrorAction Stop
    try {
        [System.IO.Directory]::Delete($lockPath)
    } catch {
        throw "Unable to remove application lock: $lockPath. $($_.Exception.Message)"
    }
}

function kp {
    $owner = Get-MlngLockOwner
    $processIds = @(Get-MlngProcessIds; if ($owner) { $owner.ProcessId }) | Sort-Object -Unique
    $processes = @(if ($processIds) { Get-Process -Id $processIds -ErrorAction SilentlyContinue })
    if ($processes.Count -gt 0) {
        Write-Host "Stopping Music Library NG app processes: $($processes.Id -join ', ')"
        Stop-Process -InputObject $processes -ErrorAction Stop
        Wait-Process -InputObject $processes -Timeout 5 -ErrorAction Stop
    } else {
        Write-Host "No Music Library NG app processes found."
    }

    if (@(Get-MlngProcessIds).Count -gt 0) {
        throw "Music Library NG app processes are still running."
    }
    Remove-MlngStaleLock
}

function bapp {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    Push-Location $env:MLNG_ROOT
    try {
        & .\gradlew.bat build @Arguments
    } finally {
        Pop-Location
    }
}

function runjar {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    if (-not (Test-Path -LiteralPath $env:MLNG_JAR)) {
        Write-Error "Packaged jar not found: $env:MLNG_JAR. Run bapp first."
        return
    }

    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java.exe" }
    $javaOptions = @()
    if ($env:MLNG_MUSIC_ROOT) {
        $javaOptions += "-Dmusic-library.music-root=$env:MLNG_MUSIC_ROOT"
    }
    if ($env:MLNG_JAVA_OPTS) {
        $javaOptions += $env:MLNG_JAVA_OPTS -split '\s+'
    }

    Push-Location $env:MLNG_ROOT
    try {
        & $java @javaOptions -jar $env:MLNG_JAR @Arguments
    } finally {
        Pop-Location
    }
}

function brun {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    kp
    bapp @Arguments
    if ($LASTEXITCODE -eq 0) {
        runjar
    }
}

function freshrun {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    kp
    $expectedDataPath = [System.IO.Path]::GetFullPath((Join-Path $env:MLNG_ROOT "data"))
    if (Test-Path -LiteralPath $expectedDataPath) {
        $remainingLock = Get-ChildItem -LiteralPath $expectedDataPath -Directory -Filter '*.lock' -Recurse -ErrorAction Stop | Select-Object -First 1
        if ($remainingLock) {
            throw "Refusing to delete data while an application lock remains: $($remainingLock.FullName)"
        }
        Write-Host "Deleting runtime data: $expectedDataPath"
        Remove-Item -LiteralPath $expectedDataPath -Recurse -Force -ErrorAction Stop
    }
    bapp @Arguments
    if ($LASTEXITCODE -eq 0) {
        runjar
    }
}

if ($MyInvocation.InvocationName -eq '.') {
    Write-Host @"
Music Library NG dev commands:
  kp        stop app/dev processes from previous runs
  bapp      build the app
  runjar    run the existing packaged jar
  brun      stop, build, and run the packaged jar
  freshrun  stop, delete the data directory, build, and run the packaged jar
"@
} else {
    Write-Host "This file defines PowerShell functions. Dot-source it instead:"
    Write-Host "  . .\dev-shell.ps1"
}

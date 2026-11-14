$ErrorActionPreference = 'Stop'

$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("mlng-dev-shell-test-" + [guid]::NewGuid())
$savedRoot = $env:MLNG_ROOT
$savedDatabasePath = $env:MUSIC_LIBRARY_DB_PATH
$testProcess = $null

function Assert-Rejected {
    param([scriptblock]$Action, [string]$ExpectedMessage)

    try {
        & $Action
    } catch {
        if ($_.Exception.Message -notlike "*$ExpectedMessage*") {
            throw
        }
        return
    }
    throw "Expected rejection: $ExpectedMessage"
}

try {
    $dataPath = Join-Path $fixtureRoot 'data'
    $lockPath = Join-Path $dataPath 'music-library-ng.sqlite.lock'
    New-Item -ItemType Directory -Path $lockPath | Out-Null
    $ownerPath = Join-Path $lockPath 'owner'
    $sentinelPath = Join-Path $dataPath 'keep.txt'
    [System.IO.File]::WriteAllText($sentinelPath, 'Runtime data must survive a failed stop.')
    $env:MLNG_ROOT = $fixtureRoot
    $env:MUSIC_LIBRARY_DB_PATH = 'data\music-library-ng.sqlite'
    . (Join-Path $PSScriptRoot '..\dev-shell.ps1')

    # Keep real project processes out of this fixture; lock ownership supplies the test process.
    function Get-MlngProcessIds { }
    function bapp { throw 'The test must not build the app.' }
    function runjar { throw 'The test must not launch the app.' }

    [System.IO.File]::WriteAllText($ownerPath, "pid=123`nos=Linux`n")
    Assert-Rejected { freshrun } 'WSL or an unknown process'
    if (-not (Test-Path -LiteralPath $sentinelPath) -or (Get-Content -LiteralPath $ownerPath -Raw) -notmatch 'os=Linux') {
        throw 'A foreign lock or runtime data was removed.'
    }

    $shellPath = (Get-Process -Id $PID).Path
    $testProcess = Start-Process -FilePath $shellPath -ArgumentList @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Seconds 60') -WindowStyle Hidden -PassThru
    [System.IO.File]::WriteAllText($ownerPath, "pid=$($testProcess.Id)`nos=Windows 11`nstartedAtMillis=0`n")
    Assert-Rejected { kp } 'does not match the running process identity'
    if ($testProcess.HasExited -or -not (Test-Path -LiteralPath $ownerPath)) {
        throw 'An unrelated process with a reused PID was stopped or its lock removed.'
    }

    $startedAtMillis = ([DateTimeOffset]$testProcess.StartTime).ToUnixTimeMilliseconds()
    [System.IO.File]::WriteAllText($ownerPath, "pid=$($testProcess.Id)`nos=Windows 11`nstartedAtMillis=$startedAtMillis`n")
    function Stop-Process { throw 'Simulated access denied.' }
    Assert-Rejected { freshrun } 'Simulated access denied'
    if (-not (Test-Path -LiteralPath $sentinelPath) -or -not (Test-Path -LiteralPath $ownerPath) -or $testProcess.HasExited) {
        throw 'A failed stop did not preserve the running owner and its data.'
    }
    Remove-Item Function:\Stop-Process

    kp
    if (-not $testProcess.HasExited -or (Test-Path -LiteralPath $lockPath) -or -not (Test-Path -LiteralPath $sentinelPath)) {
        throw 'Stopping the lock owner did not safely release its lock.'
    }
    kp

    New-Item -ItemType Directory -Path (Join-Path $dataPath 'alternate.sqlite.lock') | Out-Null
    Assert-Rejected { freshrun } 'Refusing to delete data while an application lock remains'
    if (-not (Test-Path -LiteralPath $sentinelPath)) {
        throw 'A lock for an alternate database did not preserve runtime data.'
    }
    Write-Host 'PASS: foreign locks, reused PIDs, failed stops and alternate DB locks preserve data; known Windows owners stop safely.'
} finally {
    if ($testProcess -and -not $testProcess.HasExited) {
        Microsoft.PowerShell.Management\Stop-Process -InputObject $testProcess -ErrorAction SilentlyContinue
    }
    $env:MLNG_ROOT = $savedRoot
    $env:MUSIC_LIBRARY_DB_PATH = $savedDatabasePath
    if ((Test-Path -LiteralPath $fixtureRoot) -and [System.IO.Path]::GetFullPath($fixtureRoot).StartsWith([System.IO.Path]::GetTempPath(), [System.StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}

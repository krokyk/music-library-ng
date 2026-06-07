[CmdletBinding()]
param(
    [switch]$SkipFrontend,
    [string]$GraalVmHome = $env:GRAALVM_HOME,
    [string]$VsInstallPath = $env:VSINSTALLDIR,
    [switch]$NoVsDevShell
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$PropertiesPath = Join-Path $ProjectRoot "src\main\resources\application.properties"

function Read-ApplicationProperties {
    param([string]$Path)

    $properties = @{}
    Get-Content -Path $Path | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) {
            return
        }

        $separator = $line.IndexOf("=")
        if ($separator -lt 1) {
            return
        }

        $key = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim()
        $properties[$key] = $value
    }
    return $properties
}

function Expected-NativeExeName {
    param([hashtable]$Properties)

    $outputName = $Properties["quarkus.package.output-name"]
    if (-not $outputName) {
        $outputName = "music-library-ng"
    }

    $addRunnerSuffix = $Properties["quarkus.package.jar.add-runner-suffix"]
    if ($addRunnerSuffix -and $addRunnerSuffix.Equals("false", [System.StringComparison]::OrdinalIgnoreCase)) {
        return "$outputName.exe"
    }

    return "$outputName-runner.exe"
}

function Enter-VisualStudioAmd64Shell {
    param(
        [string]$InstallPath,
        [switch]$Skip
    )

    if ($Skip) {
        return
    }

    $targetArch = $env:VSCMD_ARG_TGT_ARCH
    if ($targetArch -and ($targetArch.Equals("amd64", [System.StringComparison]::OrdinalIgnoreCase) -or $targetArch.Equals("x64", [System.StringComparison]::OrdinalIgnoreCase))) {
        Write-Host "Visual Studio developer environment: already amd64"
        return
    }

    $cl = Get-Command "cl.exe" -ErrorAction SilentlyContinue
    if ($cl) {
        Write-Host "Visual Studio compiler found: $($cl.Source)"
        return
    }

    if (-not $InstallPath) {
        $programFilesX86 = ${env:ProgramFiles(x86)}
        if ($programFilesX86) {
            $vsWhere = Join-Path $programFilesX86 "Microsoft Visual Studio\Installer\vswhere.exe"
            if (Test-Path $vsWhere) {
                $InstallPath = & $vsWhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
            }
        }
    }

    if (-not $InstallPath) {
        throw "Visual Studio Build Tools with C++ were not found. Run from a 64-bit developer shell, pass -VsInstallPath, or install Visual Studio Build Tools."
    }

    $devShellModule = Join-Path $InstallPath "Common7\Tools\Microsoft.VisualStudio.DevShell.dll"
    if (-not (Test-Path $devShellModule)) {
        throw "Visual Studio developer shell module was not found at $devShellModule."
    }

    Write-Host "Loading Visual Studio amd64 developer environment: $InstallPath"
    Import-Module $devShellModule
    Enter-VsDevShell -VsInstallPath $InstallPath -SkipAutomaticLocation -DevCmdArguments "-arch=amd64 -host_arch=amd64"
}

Set-Location $ProjectRoot

Enter-VisualStudioAmd64Shell -InstallPath $VsInstallPath -Skip:$NoVsDevShell

if ($GraalVmHome) {
    $env:JAVA_HOME = $GraalVmHome
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}

if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME is not set. Set JAVA_HOME or GRAALVM_HOME to a GraalVM JDK 21 installation."
}

$gradle = Join-Path $ProjectRoot "gradlew.bat"
if (-not (Test-Path $gradle)) {
    $gradle = Join-Path $ProjectRoot "gradlew"
}

$properties = Read-ApplicationProperties -Path $PropertiesPath
$expectedExeName = Expected-NativeExeName -Properties $properties
$expectedExePath = Join-Path (Join-Path $ProjectRoot "build") $expectedExeName

Write-Host "Project root: $ProjectRoot"
Write-Host "JAVA_HOME: $env:JAVA_HOME"
Write-Host "Expected native executable: $expectedExePath"
Write-Host ""
java -version
Write-Host ""

$gradleArgs = @(
    "build",
    "-Dquarkus.native.enabled=true",
    "-Dquarkus.package.jar.enabled=false"
)

if ($SkipFrontend) {
    $gradleArgs += @("-x", "frontendInstall", "-x", "frontendBuild")
}

& $gradle @gradleArgs
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

if (Test-Path $expectedExePath) {
    Write-Host ""
    Write-Host "Native executable created: $expectedExePath"
    exit 0
}

$fallback = Get-ChildItem -Path (Join-Path $ProjectRoot "build") -Filter "*.exe" -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($fallback) {
    Write-Host ""
    throw "Expected $expectedExeName, but found $($fallback.FullName). Check package output settings."
}

throw "Native build finished, but no .exe was found under build\."

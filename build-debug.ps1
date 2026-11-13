<#
.SYNOPSIS
    Build NewTV2 (tv-build) leanback arm64-v8a debug APK
.DESCRIPTION
    Runs only :app:assembleLeanbackDebug. Output:
    app/build/outputs/apk/leanback/debug/app-leanback-arm64-v8a-debug.apk
    (ABI splits also produce armeabi-v7a; this script only cares about arm64-v8a.)
    Optionally copies the APK to the Desktop after build.
.PARAMETER SkipDesktop
    Do not copy the APK to the Desktop.
.PARAMETER SkipChecks
    Skip prerequisite checks (local.properties / keystore / sdk / cmake).
.EXAMPLE
    .\build-debug.ps1            # build and copy to Desktop
    .\build-debug.ps1 -SkipDesktop
#>

param(
    [switch]$SkipDesktop,
    [switch]$SkipChecks
)

# NOTE: Do NOT set $ErrorActionPreference = 'Stop' globally.
# On Windows PowerShell 5.1, native commands (java/gradle) write to stderr,
# and with 'Stop' that becomes a terminating NativeCommandError and aborts the
# script before the build even starts. We rely on explicit $LASTEXITCODE checks
# and 'throw' for prerequisites instead.

# ---- Project root = folder containing this script (keep it in tv-build) ----
$ProjectDir = $PSScriptRoot
$ApkName    = 'app-leanback-arm64-v8a-debug.apk'
$ApkPath    = Join-Path $ProjectDir "app\build\outputs\apk\leanback\debug\$ApkName"

# ---- Force JDK 21 (PATH may also have JDK 25; AGP 9.4.0 needs JDK 21) ----
$Jdk21 = 'D:\devtools\jdk-21.0.12.1+1'
if (Test-Path $Jdk21) {
    $env:JAVA_HOME = $Jdk21
} elseif (-not $env:JAVA_HOME) {
    Write-Warning "JDK 21 not found at $Jdk21 and JAVA_HOME is not set; using java from PATH (may be incompatible with AGP 9.4.0)."
}
$javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }

# java -version writes to stderr; 2>&1 merges it into the success stream.
# With the global $ErrorActionPreference = 'Stop' removed, this no longer
# trips a NativeCommandError.
$javaVer = (& $javaBin -version 2>&1 | Select-String 'version' | ForEach-Object { $_.ToString().Trim() }) -join ' '
Write-Host "JAVA_HOME = $env:JAVA_HOME"
Write-Host "Java     = $javaVer"

# ---- Prerequisite checks ----
if (-not $SkipChecks) {
    if (-not (Test-Path (Join-Path $ProjectDir 'gradlew.bat'))) {
        throw "gradlew.bat not found. Make sure this script lives inside the tv-build directory."
    }

    $lp = Join-Path $ProjectDir 'local.properties'
    if (-not (Test-Path $lp)) {
        throw "local.properties missing (needs sdk.dir / cmake.dir / storeFile / keyAlias / storePassword)."
    }
    $lines     = Get-Content $lp
    $storeFile = ($lines | Where-Object { $_ -match '^storeFile=' }) -replace '^storeFile=' , ''
    $sdkDir    = ($lines | Where-Object { $_ -match '^sdk.dir=' })    -replace '^sdk.dir=' , ''
    $cmakeDir  = ($lines | Where-Object { $_ -match '^cmake.dir=' })  -replace '^cmake.dir=' , ''

    if (-not $storeFile) { throw "local.properties is missing storeFile." }
    if (-not (Test-Path $storeFile)) { throw "Keystore file does not exist: $storeFile" }
    if ($sdkDir -and -not (Test-Path $sdkDir))    { Write-Warning "sdk.dir does not exist: $sdkDir" }
    if ($cmakeDir -and -not (Test-Path $cmakeDir)) { Write-Warning "cmake.dir does not exist: $cmakeDir" }
}

# ---- Build ----
Write-Host "`n=== Building :app:assembleLeanbackDebug (debug, arm64-v8a) ===`n" -ForegroundColor Cyan
$gradleArgs = @(
    ':app:assembleLeanbackDebug',
    '--no-configuration-cache',
    '--console=plain'
)
Push-Location $ProjectDir
try {
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

# ---- Verify artifact ----
if (-not (Test-Path $ApkPath)) {
    throw "Build finished but expected artifact not found: $ApkPath"
}
$sizeMB = [math]::Round((Get-Item $ApkPath).Length / 1MB, 1)
Write-Host "`nBuild succeeded!" -ForegroundColor Green
Write-Host "Artifact: $ApkPath"
Write-Host "Size:     $sizeMB MB"

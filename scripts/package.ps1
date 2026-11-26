# Builds the customer delivery zip: dist\settlement-matcher-<version>.zip
#
#   powershell -ExecutionPolicy Bypass -File scripts\package.ps1 [-JdkHome <JDK 21 path>] [-SkipTests]
#
# Layout inside the zip (folder settlement-matcher-<version>\):
#   <launcher>.bat, settlement-matcher.jar, application.yml, <guide>.txt, runtime\ (jlink JRE 21)
#
# This script is ASCII-only on purpose (Windows PowerShell 5.1 reads BOM-less scripts in the ANSI code page).
# Files with Korean names/content live in packaging\files\ and are copied as-is.

[CmdletBinding()]
param(
    [string]$JdkHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Eclipse Adoptium\jdk-21.0.13.10002-hotspot' }),
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# Modules for the bundled runtime.
#  - from jdeps over BOOT-INF/classes and BOOT-INF/lib/*.jar (see README):
#      java.base java.compiler java.desktop java.instrument java.logging java.management java.naming
#      java.net.http java.prefs java.scripting java.security.jgss java.sql java.xml java.xml.crypto
#      jdk.jfr jdk.unsupported
#  - loaded only at run time, so jdeps cannot see them:
#      jdk.charsets   extra charsets such as EUC-KR. MS949 (used by TableReader) is already in java.base
#                     on Windows builds (checked: a java.base-only runtime reads MS949 but not EUC-KR);
#                     kept so a future EUC-KR/ISO-2022-KR file does not fail only on customer PCs.
#      jdk.localedata Korean locale data (limited to en,ko by --include-locales)
#      jdk.crypto.ec  EC crypto provider, kept so any TLS use does not fail with odd errors
$modules = @(
    'java.base', 'java.compiler', 'java.desktop', 'java.instrument', 'java.logging', 'java.management',
    'java.naming', 'java.net.http', 'java.prefs', 'java.scripting', 'java.security.jgss', 'java.sql',
    'java.xml', 'java.xml.crypto', 'jdk.jfr', 'jdk.unsupported',
    'jdk.charsets', 'jdk.localedata', 'jdk.crypto.ec'
) -join ','

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

# --- 0. checks
$jlink = Join-Path $JdkHome 'bin\jlink.exe'
$jmods = Join-Path $JdkHome 'jmods'
if (-not (Test-Path $jlink)) { throw "jlink not found: $jlink  (pass -JdkHome <JDK 21 folder>)" }
if (-not (Test-Path $jmods)) { throw "jmods not found: $jmods  (a full JDK 21 with jmods is required, not a JRE)" }
$javaVersion = (Get-Content (Join-Path $JdkHome 'release') | Where-Object { $_ -like 'JAVA_VERSION=*' }) -replace 'JAVA_VERSION=|"', ''
if (-not $javaVersion.StartsWith('21')) { throw "JDK 21 is required, found $javaVersion at $JdkHome" }

[xml]$pom = Get-Content (Join-Path $root 'pom.xml') -Encoding UTF8
$version = $pom.project.version -replace '-SNAPSHOT$', ''
$name = "settlement-matcher-$version"
$dist = Join-Path $root 'dist'
$stage = Join-Path $dist $name
$zip = Join-Path $dist "$name.zip"

# --- 1. build the jar
Step "Building jar with JDK $javaVersion"
$env:JAVA_HOME = $JdkHome
$mvnArgs = @('-q', 'package')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
Push-Location $root
try {
    # Maven/JVM write warnings to stderr; in PowerShell 5.1 that must not count as a failure. Judge by exit code only.
    $ErrorActionPreference = 'Continue'
    & (Join-Path $root 'mvnw.cmd') @mvnArgs
    $mvnExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($mvnExit -ne 0) { throw "Maven build failed (exit $mvnExit)" }
} finally { Pop-Location }
$jar = Get-ChildItem (Join-Path $root 'target') -Filter 'settlement-matcher-*.jar' |
    Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
if (-not $jar) { throw 'Built jar not found in target\' }

# --- 2. fresh staging folder
Step "Staging $stage"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force -Confirm:$false }
if (Test-Path $zip) { Remove-Item $zip -Force -Confirm:$false }
New-Item -ItemType Directory -Force $stage | Out-Null

# --- 3. runtime (jlink)
Step 'Creating runtime with jlink'
& $jlink --module-path $jmods --add-modules $modules --include-locales=en,ko `
    --strip-debug --no-man-pages --no-header-files --compress=zip-6 `
    --output (Join-Path $stage 'runtime')
if ($LASTEXITCODE -ne 0) { throw "jlink failed (exit $LASTEXITCODE)" }

# --- 4. jar, launcher, guide, external config
Copy-Item $jar.FullName (Join-Path $stage 'settlement-matcher.jar')
Get-ChildItem (Join-Path $root 'packaging\files') -File | ForEach-Object { Copy-Item $_.FullName $stage }

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$baseYml = [IO.File]::ReadAllText((Join-Path $root 'src\main\resources\application.yml'), $utf8NoBom)
$appendYml = [IO.File]::ReadAllText((Join-Path $root 'packaging\application.append.yml'), $utf8NoBom)
$yml = ($baseYml.TrimEnd() + "`n" + $appendYml) -replace "`r`n", "`n" -replace "`n", "`r`n"
[IO.File]::WriteAllText((Join-Path $stage 'application.yml'), $yml, $utf8NoBom)

# --- 5. zip (UTF-8 entry names so Korean file names survive Explorer extraction)
Step "Zipping $zip"
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($stage, $zip, [IO.Compression.CompressionLevel]::Optimal, $true)

$size = '{0:N1} MB' -f ((Get-Item $zip).Length / 1MB)
$runtimeSize = '{0:N1} MB' -f ((Get-ChildItem (Join-Path $stage 'runtime') -Recurse -File | Measure-Object Length -Sum).Sum / 1MB)
Step "Done: $zip ($size, runtime $runtimeSize unpacked)"

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $AppArgs
)

$projectRoot = Split-Path -Parent $PSScriptRoot
$mavenWrapper = Join-Path $projectRoot "mvnw.cmd"
$jarPath = Join-Path $projectRoot "app/target/quarkus-app/quarkus-run.jar"

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error "JDK 25 is required. Install JDK 25 and ensure java is on PATH."
    exit 1
}

$versionOutput = & java -XshowSettings:properties -version 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "Unable to run java. Check your JDK installation."
    exit 1
}
$versionText = $versionOutput -join [Environment]::NewLine
if ($versionText -notmatch 'java\.specification\.version\s*=\s*25(?:\D|$)') {
    Write-Error "JDK 25 is required. Install JDK 25 and ensure it is first on PATH."
    exit 1
}

if ($null -eq $AppArgs -or $AppArgs.Count -eq 0) {
    $AppArgs = @("chat")
}

$runtimeDir = $projectRoot
if (-not (Test-Path (Join-Path $runtimeDir ".env")) -and (Test-Path (Join-Path $projectRoot "app/.env"))) {
    $runtimeDir = Join-Path $projectRoot "app"
    Write-Host "[Amsha Agent] Reusing app/.env from an existing Quarkus dev setup."
}

Push-Location $projectRoot
$exitCode = 0
try {
    Write-Host "[Amsha Agent] Building the packaged application. The first build can take a few minutes."
    & $mavenWrapper -B -pl app -am package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        $exitCode = $LASTEXITCODE
    } elseif (-not (Test-Path $jarPath)) {
        Write-Error "Build completed but the application JAR was not found: $jarPath"
        $exitCode = 1
    } else {
        Set-Location $runtimeDir
        Write-Host "[Amsha Agent] Starting the packaged application from $runtimeDir."
        & java --enable-native-access=ALL-UNNAMED -jar $jarPath @AppArgs
        $exitCode = $LASTEXITCODE
    }
} finally {
    Pop-Location
}

exit $exitCode

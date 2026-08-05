$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
try {
    & node scripts/validate-parity.mjs
    if ($LASTEXITCODE -ne 0) { throw 'Parity validation failed.' }
    & .\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug bundleRelease
    if ($LASTEXITCODE -ne 0) { throw 'Gradle verification failed.' }
    & .\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath | Out-File -Encoding utf8 qa\artifacts\dependency-report.txt
    & .\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath | Out-File -Encoding utf8 qa\artifacts\sbom-style-dependency-tree.txt
} finally {
    Pop-Location
}


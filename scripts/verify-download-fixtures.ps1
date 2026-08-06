param(
    [switch]$Device
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

Push-Location $repo
try {
    & .\gradlew.bat :core:downloads:testDebugUnitTest `
        --tests '*DownloadFixtureIntegrationTest' `
        --tests '*OkHttpStreamingTransferTest' `
        --tests '*SafeZipExtractorTest' `
        --tests '*TransferEnginePolicyTest' `
        --tests '*VaultStorageTest'
    if ($LASTEXITCODE -ne 0) { throw 'Deterministic download fixture tests failed.' }

    & .\gradlew.bat :core:downloads:lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Download fixture lint failed.' }

    if ($Device) {
        $adb = if ($env:ANDROID_HOME) {
            Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
        } else {
            Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
        }
        if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found at $adb" }
        $deviceLines = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' })
        $authorized = @($deviceLines | Where-Object { $_ -match '\sdevice$' })
        if ($authorized.Count -ne 1 -or $deviceLines.Count -ne 1) {
            throw "Exactly one authorized device is required. Current adb states: $($deviceLines -join ', ')"
        }
        & .\gradlew.bat :core:downloads:connectedDebugAndroidTest `
            '-Pandroid.testInstrumentationRunnerArguments.class=com.vault999.android.downloads.DownloadDeviceFixtureTest'
        if ($LASTEXITCODE -ne 0) { throw 'On-device download fixture tests failed.' }
    }
} finally {
    Pop-Location
}

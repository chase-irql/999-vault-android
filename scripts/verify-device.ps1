$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found at $adb" }

$deviceLines = & $adb devices -l | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
$authorized = @($deviceLines | Where-Object { $_ -match '^\S+\s+device(?:\s|$)' })
if ($authorized.Count -ne 1 -or $deviceLines.Count -ne 1) {
    throw "Exactly one authorized device is required. Current adb states: $($deviceLines -join ', ')"
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$artifactRoot = Join-Path $repo "qa\artifacts\$stamp\device"
New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null

function Invoke-CheckedInstrumentation {
    param(
        [Parameter(Mandatory)] [string] $ClassName,
        [Parameter(Mandatory)] [string] $FailureMessage
    )
    $output = @(& $adb shell am instrument -w -r -e class $ClassName 'com.vault999.android.benchmark/androidx.test.runner.AndroidJUnitRunner' 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    $text = $output -join "`n"
    if ($exitCode -ne 0 -or $text -match 'FAILURES!!!' -or $text -notmatch 'OK \(\d+ tests?\)') {
        throw $FailureMessage
    }
}

Push-Location $repo
try {
    & .\scripts\verify-download-fixtures.ps1 -Device
    if ($LASTEXITCODE -ne 0) { throw 'Download fixture verification failed.' }
    & .\gradlew.bat connectedDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'Connected tests failed.' }
    & .\gradlew.bat :core:database:connectedDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'Database migration tests failed.' }
    # AGP 9.1.1 is newer than the current Baseline Profile plugin's tested range, so its
    # target-install orchestration is unreliable. Assemble and install the two profileable
    # APKs explicitly, then invoke the same AndroidJUnitRunner classes directly.
    & .\gradlew.bat :app:assembleNonMinifiedRelease :benchmark:assembleBenchmarkRelease
    if ($LASTEXITCODE -ne 0) { throw 'Profileable app/benchmark assembly failed.' }
    & $adb install -r 'app\build\outputs\apk\nonMinifiedRelease\app-nonMinifiedRelease.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Profileable app install failed.' }
    & $adb install -r 'benchmark\build\outputs\apk\benchmarkRelease\benchmark-benchmarkRelease.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark APK install failed.' }
    Invoke-CheckedInstrumentation -ClassName 'com.vault999.android.benchmark.BaselineProfileGenerator' -FailureMessage 'Baseline Profile generation failed.'
    Invoke-CheckedInstrumentation -ClassName 'com.vault999.android.benchmark.StartupBenchmark' -FailureMessage 'Macrobenchmark failed.'
    $benchmarkArtifacts = Join-Path $artifactRoot 'benchmark'
    New-Item -ItemType Directory -Force -Path $benchmarkArtifacts | Out-Null
    & $adb pull '/storage/emulated/0/Android/media/com.vault999.android.benchmark/.' $benchmarkArtifacts | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark artifact pull failed.' }
    & $adb install -r 'app\build\outputs\apk\debug\app-debug.apk'
    if ($LASTEXITCODE -ne 0) { throw 'adb install -r failed.' }

    $properties = [ordered]@{
        manufacturer = (& $adb shell getprop ro.product.manufacturer).Trim()
        model = (& $adb shell getprop ro.product.model).Trim()
        android = (& $adb shell getprop ro.build.version.release).Trim()
        api = (& $adb shell getprop ro.build.version.sdk).Trim()
        abi = (& $adb shell getprop ro.product.cpu.abi).Trim()
        size = ((& $adb shell wm size) -join ' ').Trim()
        density = ((& $adb shell wm density) -join ' ').Trim()
        locale = (& $adb shell getprop persist.sys.locale).Trim()
        dataStorage = ((& $adb shell df -h /data) -join "`n").Trim()
    }
    $properties | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $artifactRoot 'device.json')
    & $adb logcat -c
    & $adb shell am force-stop com.vault999.android.debug
    & $adb shell monkey -p com.vault999.android.debug -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 3
    $remoteScreenshot = "/data/local/tmp/vault999-$stamp-cold-launch.png"
    & $adb shell screencap -p $remoteScreenshot
    & $adb pull $remoteScreenshot (Join-Path $artifactRoot 'cold-launch.png') | Out-Null
    & $adb shell rm $remoteScreenshot
    & $adb logcat -d -v threadtime Vault999:D AndroidRuntime:E '*:S' | Set-Content -Encoding utf8 (Join-Path $artifactRoot 'cold-launch-logcat.txt')
} finally {
    Pop-Location
}

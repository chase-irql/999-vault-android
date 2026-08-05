$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found at $adb" }

$deviceLines = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
$authorized = @($deviceLines | Where-Object { $_ -match '\sdevice$' })
if ($authorized.Count -ne 1 -or $deviceLines.Count -ne 1) {
    throw "Exactly one authorized device is required. Current adb states: $($deviceLines -join ', ')"
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$artifactRoot = Join-Path $repo "qa\artifacts\$stamp\device"
New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null

Push-Location $repo
try {
    & .\gradlew.bat connectedDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'Connected tests failed.' }
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
    & $adb exec-out screencap -p > (Join-Path $artifactRoot 'cold-launch.png')
    & $adb logcat -d -v threadtime Vault999:D AndroidRuntime:E '*:S' | Set-Content -Encoding utf8 (Join-Path $artifactRoot 'cold-launch-logcat.txt')
} finally {
    Pop-Location
}


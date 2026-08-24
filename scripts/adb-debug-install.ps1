[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot ".." )).Path

function Find-Adb {
    $candidates = @()
    if ($env:ANDROID_SDK_ROOT) { $candidates += Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" }
    if ($env:ANDROID_HOME) { $candidates += Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }
    $candidates += "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    $candidates = @($candidates | Where-Object { Test-Path -LiteralPath $_ })
    if ($candidates.Count -gt 0) { return [string]$candidates[0] }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "adb.exe를 찾지 못했습니다. Android SDK platform-tools를 설치하거나 ANDROID_SDK_ROOT를 설정하세요."
}

$adb = Find-Adb
& $adb start-server | Out-Null
$deviceLines = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "^\S+\s+(device|unauthorized|offline)\s*$" })
$devices = $deviceLines | ForEach-Object {
    $parts = ($_ -split "\s+")
    [pscustomobject]@{ Serial = $parts[0]; State = $parts[1] }
}

if ($Serial) {
    $device = $devices | Where-Object Serial -eq $Serial | Select-Object -First 1
} elseif (@($devices).Count -eq 1) {
    $device = $devices[0]
} else {
    $device = $null
}

if (-not $device) {
    if (-not $devices) {
        throw "연결된 Android 기기가 없습니다. USB 디버깅을 켠 뒤 USB로 연결하고 이 스크립트를 다시 실행하세요."
    }
    throw ("기기를 하나로 선택할 수 없습니다: " + (($devices | ForEach-Object { "$($_.Serial) [$($_.State)]" }) -join ", ") + ". -Serial 옵션을 사용하세요.")
}
if ($device.State -ne "device") {
    throw "기기 $($device.Serial)의 상태가 $($device.State)입니다. 폰에서 USB 디버깅 허용 대화상자를 승인하세요."
}

$apk = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not $SkipBuild) {
    $existing = subst R: 2>$null
    if ($LASTEXITCODE -eq 0 -and $existing) { throw "R: 드라이브가 이미 사용 중입니다." }
    $buildExit = 1
    subst R: $repoRoot
    try {
        Push-Location R:\
        $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
        & .\gradlew.bat :app:assembleDebug --no-daemon
        $buildExit = $LASTEXITCODE
    } finally {
        Pop-Location
        subst R: /d
    }
    if ($buildExit -ne 0) { exit $buildExit }
}

if (-not (Test-Path -LiteralPath $apk)) {
    throw "debug APK를 찾지 못했습니다: $apk"
}

& $adb -s $device.Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw "APK 설치에 실패했습니다." }
& $adb -s $device.Serial shell am start -n "com.hwanghj09.sonju/.MainActivity"
if ($LASTEXITCODE -ne 0) { throw "Sonju 실행에 실패했습니다." }
Write-Host "설치·실행 완료: $($device.Serial)"
Write-Host "다음 단계: 폰에서 Sonju 온보딩과 접근성 서비스 활성화를 직접 승인하세요."

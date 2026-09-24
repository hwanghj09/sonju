[CmdletBinding()]
param([switch]$Baseline, [ValidateRange(1, 5)][int]$Repeat = 1)

# Install the debug app and androidTest APK with adb install -r first. No paid API is used.
# Keep the phone near the PC speaker. Phone playback can be suppressed as echo by its recognizer.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$output = Join-Path $repo 'build\speech-accuracy'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$readyPath = '/sdcard/Android/data/com.hwanghj09.sonju/files/speech-accuracy/native-ready'
$resultPath = '/sdcard/Android/data/com.hwanghj09.sonju/files/speech-accuracy/native-microphone.json'
$label = if ($Baseline) { 'baseline' } else { 'on-device' }
for ($index = 1; $index -le $Repeat; $index++) {
    & $adb shell rm -f $readyPath $resultPath
    $log = Join-Path $output "$label-$index.txt"
    $arguments = @('shell', 'am', 'instrument', '-w', '-r', '-e', 'nativeSpeechProbe', 'true',
        '-e', 'externalPlayback', 'true', '-e', 'class', 'com.hwanghj09.sonju.NativeSpeechDeviceTest')
    if (-not $Baseline) { $arguments += @('-e', 'commandConfig', 'true') }
    $arguments += 'com.hwanghj09.sonju.test/androidx.test.runner.AndroidJUnitRunner'
    $process = Start-Process -FilePath $adb -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $log -RedirectStandardError (Join-Path $output "$label-$index.stderr.txt")
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $ready = $false
    while ($timer.Elapsed.TotalSeconds -lt 15 -and -not $process.HasExited) {
        & $adb shell test -f $readyPath
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 150
    }
    if ($ready) {
        $player = New-Object System.Media.SoundPlayer (Join-Path $repo 'app\src\androidTest\assets\korean-command.wav')
        try { $player.PlaySync() } finally { $player.Dispose() }
    }
    if (-not $process.WaitForExit(25000)) { throw 'Speech test did not finish within its time limit.' }
    $result = Join-Path $output "$label-$index.json"
    & $adb pull $resultPath $result | Out-Null
    if (Test-Path -LiteralPath $result) { Get-Content -LiteralPath $result }
    if (-not $ready -or -not (Select-String -LiteralPath $log -SimpleMatch 'OK (1 test)' -Quiet)) {
        throw "Speech test failed. See $log"
    }
}

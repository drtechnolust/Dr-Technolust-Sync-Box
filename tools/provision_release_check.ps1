# Provisions only the isolated signed QA package over an ADB-forwarded loopback socket.
param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$BoxHost,
    [Parameter(Mandatory=$true)][string]$DeviceId,
    [Parameter(Mandatory=$true)][string]$LocalKeyFile
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskReport = Join-Path $taskRoot 'app\build\reports\release-qa'
New-Item -ItemType Directory -Force -Path $taskReport | Out-Null
$taskToken = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$taskPayload = @{ token=$taskToken; host=$BoxHost; deviceId=$DeviceId; localKey=(Get-Content -LiteralPath $LocalKeyFile -Raw).Trim() } | ConvertTo-Json -Compress
$taskAdb = (Get-Command adb).Source
$taskPort = (& $taskAdb -s $Serial forward tcp:0 tcp:39017).Trim()
if ($taskPort -notmatch '^\d+$') { throw 'ADB forwarding failed' }
try {
    $taskProcess = Start-Process -FilePath $taskAdb -ArgumentList @('-s',$Serial,'shell','am','instrument','-w','-e','class','com.drtech.ledsync.ReleaseProvisionTest','-e','provisionToken',$taskToken,'com.drtech.ledsync.releasecheck.test/androidx.test.runner.AndroidJUnitRunner') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskReport 'provision-test.txt') -RedirectStandardError (Join-Path $taskReport 'provision-error.txt')
    $taskSuccess = $false
    for ($taskAttempt=0; $taskAttempt -lt 30; $taskAttempt++) {
        Start-Sleep -Milliseconds 1000
        $taskSocket = New-Object System.Net.Sockets.TcpClient
        try {
            $taskSocket.Connect('127.0.0.1', [int]$taskPort)
            $taskStream = $taskSocket.GetStream()
            $taskStream.ReadTimeout = 3000
            $taskBytes = [Text.Encoding]::UTF8.GetBytes($taskPayload + "`n")
            $taskStream.Write($taskBytes, 0, $taskBytes.Length)
            $taskReader = New-Object System.IO.StreamReader($taskStream)
            if ($taskReader.ReadLine() -eq 'OK') { $taskSuccess=$true; break }
        } catch { } finally { $taskSocket.Dispose() }
    }
    if (!$taskSuccess) { throw 'QA provisioning did not complete; inspect the non-secret test report' }
    if (!$taskProcess.WaitForExit(15000)) { throw 'QA instrumentation did not finish' }
    $taskResult = Get-Content (Join-Path $taskReport 'provision-test.txt') -Raw
    if ($taskResult -notmatch 'OK \(1 test\)') { throw 'QA provisioning test failed' }
    Write-Host 'Isolated QA app configured. No key was printed or placed in a shared device file.'
} finally {
    & $taskAdb -s $Serial forward --remove "tcp:$taskPort"
    $taskPayload = $null
}

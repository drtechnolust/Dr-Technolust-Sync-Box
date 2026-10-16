# Read-only APK gate. Prints no device credentials or signing passwords.
param(
    [string]$Apk = (Join-Path (Split-Path -Parent $PSScriptRoot) 'app\build\outputs\apk\release\app-release.apk'),
    [string]$Version = '1.0.4',
    [string]$SdkRoot = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' })
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskTools = Get-ChildItem (Join-Path $SdkRoot 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$taskTools) { throw 'Android build-tools not found' }
$taskSigner = & (Join-Path $taskTools.FullName 'apksigner.bat') verify --verbose --print-certs $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
if ($taskSigner -match 'CN=Android Debug') { throw 'Debug certificate is forbidden for public releases' }
if (!($taskSigner -match 'Verified using v2 scheme.*true')) { throw 'APK v2 signature missing' }
$taskBadging = & (Join-Path $taskTools.FullName 'aapt.exe') dump badging $Apk
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect APK manifest' }
if (!($taskBadging -match "package: name='com.drtech.ledsync'")) { throw 'Wrong package, possibly the isolated QA build' }
if (!($taskBadging -match "versionName='$([regex]::Escape($Version))'")) { throw 'Unexpected APK version' }
if ($taskBadging -match 'application-debuggable') { throw 'A public APK must not be debuggable' }
if (!($taskBadging -match 'leanback-launchable-activity')) { throw 'TV launcher entry missing' }
$taskSecrets = @()
$taskKeyFile = Join-Path $taskRoot 'dumps\local-key.private.txt'
if (Test-Path $taskKeyFile) { $taskSecrets += (Get-Content $taskKeyFile -Raw).Trim() }
$taskDeviceIdFile = Join-Path $taskRoot 'dumps\device-id.private.txt'
if (Test-Path $taskDeviceIdFile) { $taskSecrets += (Get-Content $taskDeviceIdFile -Raw).Trim() }
$taskProperties = Join-Path $taskRoot 'keystore.properties'
if (Test-Path $taskProperties) {
    $taskSecrets += @(Get-Content $taskProperties | Where-Object { $_ -match '^(storePassword|keyPassword)=' } | ForEach-Object { ($_ -split '=',2)[1] })
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $Apk))
try {
    foreach ($taskEntry in $taskZip.Entries) {
        if ($taskEntry.FullName -match '(^|/)(local-device|keystore|.*private\.txt)|(^|/)Release(Provision|Smoke|Platform|Recovery)Test') { throw 'Private/test artifact in public APK' }
        $taskStream = $taskEntry.Open()
        $taskMemory = New-Object System.IO.MemoryStream
        try {
            $taskStream.CopyTo($taskMemory)
            $taskText = [Text.Encoding]::UTF8.GetString($taskMemory.ToArray())
            if ($taskText -match '10\.10\.1\.\d+|THING_SMART_APPKEY|THING_SMART_SECRET|com/thingclips/|Lcom/drtech/ledsync/(Release\w+|AutomationDevice|LiveControl|CecControl)Test;') { throw 'Owner-specific, test or vendor SDK material found in public APK' }
            foreach ($taskSecret in $taskSecrets) {
                if ($taskSecret -and $taskText.Contains($taskSecret)) { throw 'A known private credential appears in the public APK' }
            }
        } finally { $taskStream.Dispose(); $taskMemory.Dispose() }
    }
} finally { $taskZip.Dispose() }
$taskHash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "PASS: signed, non-debuggable TV APK $Version, correct package, credential scan clean."
Write-Host "SHA-256: $taskHash"
$taskSigner | Where-Object { $_ -match 'certificate DN:|certificate SHA-256 digest:' } | Write-Host

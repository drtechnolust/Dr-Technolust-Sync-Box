# Builds a clean, publishable source folder (and zip) from this working tree.
# Only an allow-list of files is copied; the result is then scanned for secrets,
# device-specific values and vendor material, and the script fails if any are found.
#   & .\tools\make_release.ps1
param([string]$Version = '1.0.4')
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Use a numeric semantic version.' }
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "release\DrTechnolustSyncBox-$Version"
# Preserve previous releases. Each run uses a new destination if necessary.
if (Test-Path $out) { $out += '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') }
New-Item -ItemType Directory -Force $out | Out-Null

function Copy-Tree($rel, [string[]]$excludeDirs = @('build', '.gradle', '.idea', '.venv', 'libs')) {
    $src = Join-Path $root $rel
    $dst = Join-Path $out $rel
    New-Item -ItemType Directory -Force $dst | Out-Null
    Get-ChildItem $src -Recurse -File | Where-Object {
        $p = $_.FullName.Substring($src.Length)
        -not ($excludeDirs | Where-Object { $p -match "\\$_\\" })
    } | ForEach-Object {
        $target = Join-Path $dst $_.FullName.Substring($src.Length)
        New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null
        Copy-Item $_.FullName $target
    }
}

Copy-Tree 'app'
Copy-Tree 'tuya-lan'
Copy-Tree 'docs'
Copy-Tree 'gradle'
Copy-Tree 'branding'
Copy-Tree '.github'
if (Test-Path (Join-Path $root 'media')) { Copy-Tree 'media' }
foreach ($f in 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', 'README.md', 'LICENSE', 'SECURITY.md', 'DP_MAP.md') {
    Copy-Item (Join-Path $root $f) (Join-Path $out $f)
}
New-Item -ItemType Directory -Force (Join-Path $out 'tools') | Out-Null
foreach ($f in 'get_local_key.py', 'init_signing.ps1', 'make_release.ps1', 'verify_release.ps1', 'provision_release_check.ps1', 'build_brand_assets.ps1') {
    Copy-Item (Join-Path $root "tools\$f") (Join-Path $out "tools\$f")
}
@"
.gradle/
build/
local.properties
keystore.properties
*.jks
*.keystore
*.iml
.idea/
.venv/
dumps/
*.private.txt
*.p12
release/
.env
.env.*
devices.json
tinytuya.json
tuya-raw.json
local-device*.json
*.sealed
*.pem
*.key
*.pfx
"@ | Set-Content (Join-Path $out '.gitignore') -Encoding UTF8

# ---- scan -------------------------------------------------------------------
$problems = @()
$keyFile = Join-Path $root 'dumps\local-key.private.txt'
$secretKey = if (Test-Path $keyFile) { (Get-Content $keyFile -Raw).Trim() } else { $null }
$signingProperties = Join-Path $root 'keystore.properties'
$signingSecrets = if (Test-Path $signingProperties) {
    @(Get-Content $signingProperties | Where-Object { $_ -match '^(storePassword|keyPassword)=' } | ForEach-Object { ($_ -split '=',2)[1] })
} else { @() }
$deviceIdFile = Join-Path $root 'dumps\device-id.private.txt'
$privateDeviceId = if (Test-Path $deviceIdFile) { (Get-Content $deviceIdFile -Raw).Trim() } else { $null }
$patterns = @(
    '10\.10\.1\.\d+',                  # this owner's LAN addresses
    'thingclips', 'THING_SMART', 'security-algorithm', 'FANCYLEDS_APP'
)
$forbiddenExt = '\.(aar|apk|dex|hprof|jks|keystore|p12|private\.txt|bin)$'
Get-ChildItem $out -Recurse -File | ForEach-Object {
    if ($_.Name -match $forbiddenExt) { $problems += "forbidden file type: $($_.FullName.Substring($out.Length))"; return }
    if ($_.Extension -in '.png', '.jpg', '.webp', '.xml' -and $_.Extension -ne '.xml') { return }
    $text = Get-Content $_.FullName -Raw -ErrorAction SilentlyContinue
    if (-not $text) { return }
    # These two scanner sources contain the detection expressions themselves.
    if ($_.Name -notin 'make_release.ps1', 'verify_release.ps1') {
        foreach ($p in $patterns) { if ($text -match $p) { $problems += "forbidden pattern in $($_.FullName.Substring($out.Length))" } }
    }
    if ($secretKey -and $text.Contains($secretKey)) { $problems += "the local key appears in $($_.FullName.Substring($out.Length))" }
    if ($privateDeviceId -and $text.Contains($privateDeviceId)) { $problems += "a private device ID appears in $($_.FullName.Substring($out.Length))" }
    foreach ($signingSecret in $signingSecrets) {
        if ($signingSecret -and $text.Contains($signingSecret)) { $problems += "a signing credential appears in $($_.FullName.Substring($out.Length))" }
    }
}
if ($problems.Count -gt 0) {
    $problems | ForEach-Object { Write-Host "BLOCKED: $_" -ForegroundColor Red }
    throw "Release scan failed with $($problems.Count) problem(s); nothing was zipped."
}

$zip = "$out-source.zip"
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($out, $zip)
$count = (Get-ChildItem $out -Recurse -File | Measure-Object).Count
Write-Host "Release folder ready: $out ($count files)" -ForegroundColor Green
Write-Host "Zip: $zip" -ForegroundColor Green

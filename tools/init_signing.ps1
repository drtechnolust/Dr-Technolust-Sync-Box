# Creates a permanent, owner-controlled signing identity. Never prints passwords.
param([string]$PrivateDirectory = (Join-Path $env:USERPROFILE '.android\drtech-ledsync-signing'))
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskProperties = Join-Path $taskRoot 'keystore.properties'
if (Test-Path -LiteralPath $taskProperties) {
    Write-Host 'Signing configuration already exists; nothing changed.'
    exit 0
}
$taskKeyFile = Join-Path $PrivateDirectory 'drtech-led-sync-release.p12'
if (Test-Path -LiteralPath $taskKeyFile) { throw 'A signing key already exists. Restore its private properties; never replace an existing release key.' }
New-Item -ItemType Directory -Force -Path $PrivateDirectory | Out-Null
$taskSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$taskAcl = New-Object System.Security.AccessControl.DirectorySecurity
$taskAcl.SetAccessRuleProtection($true, $false)
$taskAcl.SetOwner($taskSid)
$taskAcl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($taskSid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
$taskSystemSid = New-Object System.Security.Principal.SecurityIdentifier('S-1-5-18')
$taskAcl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($taskSystemSid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
Set-Acl -LiteralPath $PrivateDirectory -AclObject $taskAcl
$taskRandom = New-Object byte[] 36
$taskRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$taskRng.GetBytes($taskRandom)
$taskRng.Dispose()
$taskPassword = [Convert]::ToBase64String($taskRandom)
$taskKeytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { (Get-Command keytool).Source }
try {
    $env:DRTECH_SIGNING_PASSWORD = $taskPassword
    & $taskKeytool -genkeypair -noprompt -keystore $taskKeyFile -storetype PKCS12 -alias drtechledsync -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=Dr. Technolust, O=Dr. Technolust' -storepass:env DRTECH_SIGNING_PASSWORD -keypass:env DRTECH_SIGNING_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
    $taskConfig = "storeFile=$($taskKeyFile.Replace('\','/'))`nstorePassword=$taskPassword`nkeyAlias=drtechledsync`nkeyPassword=$taskPassword`n"
    $taskUtf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $PrivateDirectory 'keystore.properties.private.txt'), $taskConfig, $taskUtf8)
    [System.IO.File]::WriteAllText($taskProperties, $taskConfig, $taskUtf8)
    $taskFileAcl = New-Object System.Security.AccessControl.FileSecurity
    $taskFileAcl.SetAccessRuleProtection($true, $false)
    $taskFileAcl.SetOwner($taskSid)
    $taskFileAcl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($taskSid, 'FullControl', 'Allow')))
    $taskFileAcl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($taskSystemSid, 'FullControl', 'Allow')))
    Set-Acl -LiteralPath $taskProperties -AclObject $taskFileAcl
    Write-Host "Permanent signing identity created. PRIVATE recovery directory: $PrivateDirectory"
    Write-Host 'Back up that entire private directory to secure offline storage. Never publish it or keystore.properties.'
} finally {
    Remove-Item Env:\DRTECH_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    $taskPassword = $null
    $taskConfig = $null
    [Array]::Clear($taskRandom, 0, $taskRandom.Length)
}

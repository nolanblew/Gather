param(
    [string]$SigningDirectory = "$env:USERPROFILE\.codex\secrets\gather-android",
    [string]$JavaHome = 'F:\Program Files (x86)\Microsoft Visual Studio\Shared\Android\openjdk\jdk-21.0.8'
)
$ErrorActionPreference = 'Stop'
$propertiesPath = Join-Path $SigningDirectory 'release.properties'
$keystorePath = Join-Path $SigningDirectory 'release.jks'
if ((Test-Path -LiteralPath $propertiesPath) -and (Test-Path -LiteralPath $keystorePath)) {
    Write-Output "Release signing is already configured at $SigningDirectory"
    exit 0
}
if ((Test-Path -LiteralPath $propertiesPath) -or (Test-Path -LiteralPath $keystorePath)) {
    throw 'Partial signing configuration exists. Inspect it before generating a new identity.'
}
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool)) { throw 'Set JavaHome to an installed JDK.' }
New-Item -ItemType Directory -Force -Path $SigningDirectory | Out-Null
$account = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
& "$env:SystemRoot\System32\icacls.exe" $SigningDirectory /inheritance:r /grant:r "${account}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Unable to restrict signing directory permissions.' }
$passwordBytes = New-Object byte[] 36
[System.Security.Cryptography.RandomNumberGenerator]::Fill($passwordBytes)
$env:GATHER_SIGNING_PASSWORD = [Convert]::ToBase64String($passwordBytes)
try {
    & $keytool -genkeypair -keystore $keystorePath -storetype JKS -alias gather-release -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Gather Android, OU=Mobile, O=Gather' -storepass:env GATHER_SIGNING_PASSWORD -keypass:env GATHER_SIGNING_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Release key generation failed.' }
    $portablePath = $keystorePath.Replace('\', '/')
    $properties = "storeFile=$portablePath`nstorePassword=$env:GATHER_SIGNING_PASSWORD`nkeyAlias=gather-release`nkeyPassword=$env:GATHER_SIGNING_PASSWORD`n"
    [System.IO.File]::WriteAllText($propertiesPath, $properties, [System.Text.UTF8Encoding]::new($false))
    Write-Output "Release signing configured at $SigningDirectory. Preserve this directory for future updates."
} finally {
    Remove-Item Env:GATHER_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}

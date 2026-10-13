param([switch]$Unsigned)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
python (Join-Path $taskRoot 'prepare_build.py')
if ($LASTEXITCODE -ne 0) { throw 'Project preparation failed' }
$taskApp = Join-Path $taskRoot 'upstream\winlator-app-main'
New-Item -ItemType Directory -Force -Path (Join-Path $taskRoot 'dist') | Out-Null
Push-Location -LiteralPath $taskApp
try {
    & '.\gradlew.bat' ':app:assembleRelease' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }
} finally { Pop-Location }
$taskInput = Join-Path $taskApp 'app\build\outputs\apk\release\app-release-unsigned.apk'
$taskOutput = Join-Path $taskRoot 'dist\StudySteady-v0.2.0.apk'
if ($Unsigned) {
    Copy-Item -LiteralPath $taskInput -Destination ($taskOutput -replace '.apk$', '-unsigned.apk') -Force
    return
}
if (-not $env:STUDY_KEYSTORE -or -not $env:STUDY_KEY_ALIAS -or -not $env:STUDY_STORE_PASS -or -not $env:STUDY_KEY_PASS) {
    throw 'Set STUDY_KEYSTORE, STUDY_KEY_ALIAS, STUDY_STORE_PASS and STUDY_KEY_PASS. Keys must stay outside the repository.'
}
$taskSigner = Join-Path $env:ANDROID_SDK_ROOT 'build-tools\36.0.0\apksigner.bat'
& $taskSigner sign --ks $env:STUDY_KEYSTORE --ks-key-alias $env:STUDY_KEY_ALIAS --ks-pass env:STUDY_STORE_PASS --key-pass env:STUDY_KEY_PASS --out $taskOutput $taskInput
if ($LASTEXITCODE -ne 0) { throw 'Signing failed' }
& $taskSigner verify --verbose --print-certs $taskOutput
if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed' }
$taskHash = (Get-FileHash -LiteralPath $taskOutput -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $taskRoot 'dist\SHA256SUMS.txt') -Value "$taskHash  StudySteady-v0.2.0.apk" -Encoding ascii


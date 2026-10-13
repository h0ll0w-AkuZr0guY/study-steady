$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
python (Join-Path $taskRoot 'prepare_build.py')
if ($LASTEXITCODE -ne 0) { throw 'Android project preparation failed' }
$taskApp = Join-Path $taskRoot 'upstream\winlator-app-main'
Push-Location -LiteralPath $taskApp
try {
    & '.\gradlew.bat' ':app:assembleDebug' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
    $taskApk = Join-Path $taskApp 'app\build\outputs\apk\debug\app-debug.apk'
    Copy-Item -LiteralPath $taskApk -Destination (Join-Path $taskRoot 'dist\StudySteadyMobile.apk') -Force
    Get-FileHash -LiteralPath (Join-Path $taskRoot 'dist\StudySteadyMobile.apk') -Algorithm SHA256
} finally { Pop-Location }

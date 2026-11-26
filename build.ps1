$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$studioJdk = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
if (Test-Path "$studioJdk/bin/java.exe") { $env:JAVA_HOME = $studioJdk }
& ./gradlew.bat :app:assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
New-Item -ItemType Directory -Force build | Out-Null
Copy-Item app/build/outputs/apk/debug/app-debug.apk build/Fonoteka.apk -Force

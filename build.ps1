$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
Set-Location $PSScriptRoot
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$jdk = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
if (-not (Test-Path (Join-Path $jdk 'bin/javac.exe'))) { $jdk = $env:JAVA_HOME }
if (-not $jdk) { throw 'Set JAVA_HOME to a JDK 17 or newer.' }
$env:JAVA_HOME = $jdk
$tools = Join-Path $sdk 'build-tools/36.1.0'
$platform = Join-Path $sdk 'platforms/android-36.1/android.jar'
New-Item -Force -ItemType Directory build/classes,build/dex | Out-Null
Copy-Item $platform build/android.jar -Force
$platform = Join-Path $PSScriptRoot "build/android.jar"
$sources = @(Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName)
& "$jdk/bin/javac.exe" --release 8 -encoding UTF-8 -classpath $platform -d build/classes @sources
& "$jdk/bin/jar.exe" cf build/classes.jar -C build/classes .
& "$tools/d8.bat" --min-api 27 --lib $platform --output build/dex build/classes.jar
& "$tools/aapt2.exe" compile --dir app/src/main/res -o build/resources.zip
& "$tools/aapt2.exe" link build/resources.zip --min-sdk-version 27 --target-sdk-version 34 -I $platform --manifest AndroidManifest.xml -o build/base.apk
Copy-Item build/base.apk build/unsigned.apk -Force
& "$jdk/bin/jar.exe" uf build/unsigned.apk -C build/dex classes.dex
& "$tools/zipalign.exe" -f 4 build/unsigned.apk build/aligned.apk
if (-not (Test-Path build/debug.keystore)) {
    & "$jdk/bin/keytool.exe" -genkeypair -keystore build/debug.keystore -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug' -keyalg RSA -keysize 2048 -validity 10000
}
& "$tools/apksigner.bat" sign --ks build/debug.keystore --ks-pass pass:android --key-pass pass:android --out build/Fonoteka.apk build/aligned.apk
& "$tools/apksigner.bat" verify build/Fonoteka.apk



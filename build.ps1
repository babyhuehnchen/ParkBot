param()
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not $env:JAVA_HOME) {
    if (Test-Path '.tools/java') { $env:JAVA_HOME = (Get-ChildItem '.tools/java' -Directory | Select-Object -First 1).FullName }
    elseif (Test-Path 'C:/Program Files/Android/Android Studio/jbr') { $env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr' }
}
if (-not $env:JAVA_HOME) { throw 'Install JDK 17 or set JAVA_HOME.' }
if (-not $env:ANDROID_HOME) {
    if (Test-Path '.tools/android-sdk') { $env:ANDROID_HOME = Join-Path $PSScriptRoot '.tools/android-sdk' }
    elseif (Test-Path "$env:LOCALAPPDATA/Android/Sdk") { $env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk" }
}
if (-not $env:ANDROID_HOME) { throw 'Install Android SDK Platform 35 and Build-Tools 35.0.0, then set ANDROID_HOME.' }
New-Item -ItemType Directory -Force '.tools/tmp' | Out-Null
$socketDir = (Join-Path $PSScriptRoot '.tools/tmp').Replace('\', '/')
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS `"-Djdk.net.unixdomain.tmpdir=$socketDir`" `"-Djava.io.tmpdir=$socketDir`""
$env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.tools/gradle-cache'
& "$PSScriptRoot/gradlew.bat" :core:check :app:assembleDebug :app:lintDebug --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'Build or validation failed.' }
Write-Output "APK: $PSScriptRoot/app/build/outputs/apk/debug/app-debug.apk"

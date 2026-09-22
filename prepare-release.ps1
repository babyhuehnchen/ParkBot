#Requires -Version 5.1
param()
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$gradle = Get-Content 'app/build.gradle.kts' -Raw
$versionMatch = [regex]::Match($gradle, 'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"')
if (-not $versionMatch.Success) { throw 'Could not read versionName.' }
$version = $versionMatch.Groups[1].Value
$notes = "releases/v$version.md"
if (-not (Test-Path -LiteralPath $notes)) { throw "Missing release notes: $notes" }

& "$PSScriptRoot/build.ps1"
$apk = Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk'
$signer = Join-Path $env:ANDROID_HOME 'build-tools/35.0.0/apksigner.bat'
& $signer verify $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }

# Export only source and project documentation; never recurse over the whole workspace.
$rootFiles = @(
    '.gitignore', '.gitattributes', 'README.md', 'CHANGELOG.md', 'RELEASE.md',
    'VALIDATION.md', 'DEVICE-TESTS.md', 'LICENSE', 'build.ps1', 'prepare-release.ps1',
    'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
    'gradlew', 'gradlew.bat', 'app/build.gradle.kts', 'core/build.gradle.kts'
)
$files = @($rootFiles | ForEach-Object { Get-Item -LiteralPath $_ })
foreach ($folder in @('.github', 'gradle/wrapper', 'app/src', 'core/src', 'releases')) {
    $files += Get-ChildItem -LiteralPath $folder -Recurse -File -Force
}
$forbidden = '(?i)(\.(jks|keystore|p12|pfx|pem|key|apk|aab|zip|log)$|(^|[\\/])(\.env[^\\/]*|local\.properties|key\.properties|keystore\.properties|signing\.properties)$)'
foreach ($file in $files) {
    if ($file.FullName -match $forbidden) { throw "Refusing to export private/generated file: $($file.Name)" }
    if ($file.Extension -ne '.jar') {
        $contents = [IO.File]::ReadAllText($file.FullName)
        # Stop rather than print matching secret contents.
        $privateKey = '-----BEGIN ' + '(?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----'
        $token = '(?:github_pat' + '_[A-Za-z0-9_]{30,}|ghp' + '_[A-Za-z0-9]{30,})'
        if ($contents -match $privateKey -or $contents -match $token) {
            throw "Potential secret in $($file.Name); export stopped."
        }
    }
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
New-Item -ItemType Directory -Force 'dist' | Out-Null
$zipPath = Join-Path $PSScriptRoot "dist/ParkBot-$version-source.zip"
$stream = [IO.File]::Open($zipPath, [IO.FileMode]::Create)
try {
    $zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        foreach ($file in $files | Sort-Object FullName -Unique) {
            $relative = $file.FullName.Substring($PSScriptRoot.Length + 1).Replace('\', '/')
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $relative) | Out-Null
        }
    } finally { $zip.Dispose() }
} finally { $stream.Dispose() }

$apkName = "ParkBot-$version-debug.apk"
Copy-Item -LiteralPath $apk -Destination (Join-Path $PSScriptRoot "dist/$apkName")
Copy-Item -LiteralPath $notes -Destination 'dist/RELEASE-NOTES.md'
$checksums = foreach ($name in @($apkName, "ParkBot-$version-source.zip")) {
    $hash = (Get-FileHash -LiteralPath "dist/$name" -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $name"
}
$checksums | Set-Content 'dist/SHA256SUMS.txt' -Encoding ascii
Write-Output "Prepared dist/ for version $version. APK is debug-signed. Nothing was published."


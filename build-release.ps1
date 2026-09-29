<#
.SYNOPSIS
Builds and verifies a local release signed with the same certificate as GitHub CI.
.PARAMETER SkipBuild
Verify the existing release APK instead of invoking Gradle.
#>
[CmdletBinding()]
param([switch]$SkipBuild)
. (Join-Path $PSScriptRoot 'tools\release-common.ps1')
$apk = Join-Path $PSScriptRoot 'androidApp\build\outputs\apk\release\androidApp-release.apk'
$signing = Read-ReleaseSigning
$scratch = $null
$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME','NOTEBOOKPLUSH_KEYSTORE_FILE','NOTEBOOKPLUSH_KEYSTORE_PASSWORD','NOTEBOOKPLUSH_KEY_PASSWORD','NOTEBOOKPLUSH_KEY_ALIAS')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    if (!$SkipBuild) {
        $env:JAVA_HOME = Get-ReleaseJava
        $active = Invoke-ReleaseCommand (Join-Path $env:JAVA_HOME 'bin\jps.exe') @('-l')
        if ($active -match 'GradleWrapperMain|gradle/wrapper/gradle-wrapper.jar') { throw 'Another Gradle build is running. Wait for it to finish.' }
        $scratch = New-ReleaseScratch
        $keyBytes = [Convert]::FromBase64String($signing.keystore)
        $keyPath = Join-Path $scratch 'release.jks'
        [IO.File]::WriteAllBytes($keyPath, $keyBytes)
        [Array]::Clear($keyBytes, 0, $keyBytes.Length)
        $env:NOTEBOOKPLUSH_KEYSTORE_FILE = $keyPath
        $env:NOTEBOOKPLUSH_KEYSTORE_PASSWORD = $signing.storePassword
        $env:NOTEBOOKPLUSH_KEY_PASSWORD = $signing.keyPassword
        $env:NOTEBOOKPLUSH_KEY_ALIAS = $signing.alias
        Push-Location $PSScriptRoot
        try {
            # A signing password must not be serialized into the workspace configuration cache.
            & (Join-Path $PSScriptRoot 'gradlew.bat') :androidApp:assembleRelease --no-configuration-cache
            if ($LASTEXITCODE -ne 0) { throw 'The local release build failed.' }
        } finally { Pop-Location }
    }
    if (!(Test-Path -LiteralPath $apk)) { throw 'The signed release APK was not found. Run without -SkipBuild first.' }
    $actual = Get-ReleaseSigner $apk
    if ($actual -ne $signing.signerSha256) { throw 'The APK signing certificate does not match the published CI release.' }
    $metadata = Get-Content -LiteralPath (Join-Path (Split-Path -Parent $apk) 'output-metadata.json') -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne 'com.notebookplush' -or @($metadata.elements).Count -ne 1 -or $metadata.elements[0].outputFile -ne (Split-Path -Leaf $apk)) { throw 'Unexpected release APK metadata.' }
    Write-Output "Signed release APK: $apk"
    Write-Output "Version: $($metadata.elements[0].versionName)"
    Write-Output "CI signing certificate verified: $actual"
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    $signing = $null
    Remove-ReleaseScratch $scratch
}

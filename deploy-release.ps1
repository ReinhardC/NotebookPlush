<#
.SYNOPSIS
Builds the signed release and updates selected Android devices without uninstalling apps.
.EXAMPLE
.\deploy-release.ps1 -Model SM-X906B
.EXAMPLE
.\deploy-release.ps1 -ListModels
.EXAMPLE
.\deploy-release.ps1 -Model SM-X906B -SkipBuild -DryRun
#>
[CmdletBinding()]
param([string[]]$Model = @('SM_X906B'), [switch]$ListModels, [switch]$SkipBuild, [switch]$DryRun)
. (Join-Path $PSScriptRoot 'tools\release-common.ps1')
$adb = Join-Path (Get-ReleaseSdk) 'platform-tools\adb.exe'
$devicesText = Invoke-ReleaseCommand $adb @('devices', '-l')
$devices = @($devicesText -split '\r?\n' | ForEach-Object {
    if ($_ -match '^(\S+)\s+device\b.*\bmodel:(\S+)') {
        [pscustomobject]@{Serial=$Matches[1]; Model=$Matches[2]; Hardware=$null}
    }
})
if ($ListModels) {
    Write-Output 'Default target: SM_X906B. Use -Model to choose other models.'
    Write-Output $devicesText
    return
}
# USB and wireless connections to one physical device count as one target.
foreach ($device in $devices) {
    $device.Hardware = Invoke-ReleaseCommand $adb @('-s', $device.Serial, 'shell', 'getprop', 'ro.serialno')
    if (!$device.Hardware) { $device.Hardware = $device.Serial }
}
$devices = @($devices | Group-Object Hardware | ForEach-Object { $_.Group | Sort-Object { $_.Serial -like '*._adb-tls-connect._tcp' } | Select-Object -First 1 })
$targets = @()
foreach ($wanted in ($Model | ForEach-Object { $_ -replace '-', '_' } | Select-Object -Unique)) {
    $matchesForModel = @($devices | Where-Object { ($_.Model -replace '-', '_') -ieq ($wanted -replace '-', '_') })
    if ($matchesForModel.Count -ne 1) { throw "Expected one online device for $wanted; found $($matchesForModel.Count). Use -ListModels." }
    $targets += $matchesForModel[0]
}
if (!$targets.Count) { throw 'No device model was selected.' }
& (Join-Path $PSScriptRoot 'build-release.ps1') -SkipBuild:($SkipBuild -or $DryRun)
$signing = Read-ReleaseSigning
$apk = Join-Path $PSScriptRoot 'androidApp\build\outputs\apk\release\androidApp-release.apk'
$metadata = Get-Content -LiteralPath (Join-Path (Split-Path -Parent $apk) 'output-metadata.json') -Raw | ConvertFrom-Json
$version = [long]$metadata.elements[0].versionCode
$scratch = New-ReleaseScratch
try {
    # Check all selected devices before installing on any of them.
    foreach ($target in $targets) {
        Write-Output "Target: $($target.Model) [$($target.Serial)]"
        $paths = Invoke-ReleaseCommand $adb @('-s', $target.Serial, 'shell', 'pm', 'path', 'com.notebookplush')
        if ($paths -match '(?m)^package:(.+/base\.apk)\s*$') {
            $deviceApk = Join-Path $scratch 'installed.apk'
            [void](Invoke-ReleaseCommand $adb @('-s', $target.Serial, 'pull', $Matches[1].Trim(), $deviceApk))
            if ((Get-ReleaseSigner $deviceApk) -ne $signing.signerSha256) {
                throw "Installed NotebookPlush on $($target.Model) uses a different signing key. No app was uninstalled or changed."
            }
            $package = Invoke-ReleaseCommand $adb @('-s', $target.Serial, 'shell', 'dumpsys', 'package', 'com.notebookplush')
            if ($package -notmatch '\bversionCode=(\d+)') { throw 'Could not read the installed version.' }
            if ($version -le [long]$Matches[1]) { throw 'The local APK is not newer than the installed app. Rebuild without -SkipBuild.' }
        }
    }
    if ($DryRun) { Write-Output 'Dry run complete; nothing installed.'; return }
    foreach ($target in $targets) {
        $result = Invoke-ReleaseCommand $adb @('-s', $target.Serial, 'install', '-r', $apk)
        if ($result -notmatch '(?m)^Success\s*$') { throw "Installation did not succeed on $($target.Model)." }
        $package = Invoke-ReleaseCommand $adb @('-s', $target.Serial, 'shell', 'dumpsys', 'package', 'com.notebookplush')
        if ($package -notmatch "\bversionCode=$version\b") { throw 'Installed version verification failed.' }
        Write-Output "Updated $($target.Model) to version code $version."
    }
} finally { $signing = $null; Remove-ReleaseScratch $scratch }

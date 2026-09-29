Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$releaseProjectRoot = Split-Path -Parent $PSScriptRoot
$releaseSigningRoot = Join-Path $env:LOCALAPPDATA 'NotebookPlush\signing'
$releaseVaultPath = Join-Path $releaseSigningRoot 'release-key.dpapi'
$releaseEntropy = [Text.Encoding]::UTF8.GetBytes('NotebookPlush.ReleaseSigning.v1')
Add-Type -AssemblyName System.Security

function Invoke-ReleaseCommand {
    param([string]$File, [string[]]$Arguments, [string]$InputText)
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $File
    $info.Arguments = ($Arguments | ForEach-Object {
        '"' + ($_ -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
    }) -join ' '
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = [Text.Encoding]::UTF8
    $info.StandardErrorEncoding = [Text.Encoding]::UTF8
    $process = [Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if ($InputText) { $process.StandardInput.Write($InputText) }
    $process.StandardInput.Close()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "$(Split-Path -Leaf $File) failed: $($stderr.Result.Trim())" }
    return $stdout.Result.TrimEnd()
}

function Set-ReleaseGitHubToken {
    if ($env:GH_TOKEN -or $env:GITHUB_TOKEN) { return }
    try {
        $env:GH_TOKEN = Invoke-ReleaseCommand 'gh' @('auth', 'token')
        return
    } catch { }
    $credentials = Invoke-ReleaseCommand 'git' @('-c', 'credential.interactive=false', 'credential', 'fill') "protocol=https`nhost=github.com`n`n"
    if ($credentials -notmatch '(?m)^password=([^\r\n]+)') { throw 'Sign in to GitHub with gh auth login or Git Credential Manager first.' }
    $env:GH_TOKEN = $Matches[1]
    $credentials = $null
}

function New-ReleaseScratch {
    [void][IO.Directory]::CreateDirectory($releaseSigningRoot)
    $acl = [IO.Directory]::GetAccessControl($releaseSigningRoot, [Security.AccessControl.AccessControlSections]::Access)
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($existing in $acl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier])) {
        [void]$acl.RemoveAccessRuleSpecific($existing)
    }
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    foreach ($identity in @($sid, (New-Object Security.Principal.SecurityIdentifier 'S-1-5-18'))) {
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    [IO.Directory]::SetAccessControl($releaseSigningRoot, $acl)
    $path = Join-Path $releaseSigningRoot ('tmp-' + [Guid]::NewGuid().ToString('N'))
    [void][IO.Directory]::CreateDirectory($path)
    return $path
}

function Remove-ReleaseScratch {
    param([string]$Path)
    if (!$Path) { return }
    $full = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetFullPath($releaseSigningRoot).TrimEnd('\') + '\'
    if (!$full.StartsWith($root, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $full) -notlike 'tmp-*') {
        throw 'Refusing to remove a path outside the release scratch directory.'
    }
    if (Test-Path -LiteralPath $full) { Remove-Item -LiteralPath $full -Recurse -Force }
}

function Read-ReleaseSigning {
    if (!(Test-Path -LiteralPath $releaseVaultPath)) { throw 'Run .\setup-release-key.ps1 once to import the CI signing key.' }
    $plain = $null
    try {
        $plain = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($releaseVaultPath), $releaseEntropy, 'CurrentUser')
        return ([Text.Encoding]::UTF8.GetString($plain) | ConvertFrom-Json)
    } catch { throw 'The signing store could not be decrypted by this Windows account. Run setup-release-key.ps1 on this PC.' }
    finally { if ($plain) { [Array]::Clear($plain, 0, $plain.Length) } }
}

function Get-ReleaseJava {
    $candidates = @($env:JAVA_HOME)
    $jdkRoot = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path -LiteralPath $jdkRoot) {
        $candidates += @(Get-ChildItem -LiteralPath $jdkRoot -Directory | Where-Object Name -Match '17|21' | Sort-Object Name -Descending | Select-Object -ExpandProperty FullName)
    }
    foreach ($candidate in $candidates | Where-Object { $_ } | Select-Object -Unique) {
        $java = Join-Path $candidate 'bin\java.exe'
        if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath (Join-Path $candidate 'bin\jlink.exe'))) { continue }
        $info = New-Object Diagnostics.ProcessStartInfo
        $info.FileName = $java; $info.Arguments = '-version'; $info.UseShellExecute = $false
        $info.CreateNoWindow = $true; $info.RedirectStandardError = $true
        $process = [Diagnostics.Process]::Start($info)
        $version = $process.StandardError.ReadToEnd(); $process.WaitForExit()
        if ($version -match 'version "(17|21)\.') { return $candidate }
    }
    throw 'Install JDK 17 or 21, or point JAVA_HOME to one.'
}

function Get-ReleaseSdk {
    $local = Join-Path $releaseProjectRoot 'local.properties'
    if (Test-Path -LiteralPath $local) {
        $line = Get-Content -LiteralPath $local | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
        if ($line) { return ($line.Substring(8) -replace '\\:', ':' -replace '\\\\', '\') }
    }
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    return (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
}

function Get-ReleaseApkSigner {
    $tools = Join-Path (Get-ReleaseSdk) 'build-tools'
    $signers = @(Get-ChildItem -LiteralPath $tools -Directory | Sort-Object { [version]($_.Name -replace '[^\d.].*$', '') } -Descending |
        ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path -LiteralPath $_ })
    if (!$signers.Count) { throw 'Android SDK apksigner was not found.' }
    return $signers[0]
}

function Get-ReleaseSigner {
    param([string]$Apk)
    $previousJava = $env:JAVA_HOME
    try {
        $env:JAVA_HOME = Get-ReleaseJava
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $output = (& (Get-ReleaseApkSigner) verify --print-certs $Apk 2>&1 | Out-String)
            if ($LASTEXITCODE -ne 0) { throw 'apksigner could not verify the APK.' }
        } finally { $ErrorActionPreference = $previousPreference }
        $digests = @([regex]::Matches($output, 'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})') | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } | Sort-Object -Unique)
        if (!$digests.Count) { throw 'No signing certificate was reported for the APK.' }
        return ($digests -join ',')
    } finally { $env:JAVA_HOME = $previousJava }
}

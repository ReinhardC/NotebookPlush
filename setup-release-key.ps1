<#
.SYNOPSIS
Imports the CI signing key into a Windows-account-protected local store.
.DESCRIPTION
Requires GitHub CLI and repository write access. Signing material travels only in a CMS
encrypted artifact addressed to a temporary, non-exportable Windows certificate.
#>
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'tools\release-common.ps1')
if (Test-Path -LiteralPath $releaseVaultPath) { Write-Output 'The local release signing store is already configured.'; return }
$previousToken = $env:GH_TOKEN
$scratch = $null; $recipient = $null; $branchCreated = $false; $runId = $null; $runCompleted = $false
$plainBytes = $null; $bundleText = $null
$branch = 'local-signing-import-' + [Guid]::NewGuid().ToString('N')
$repo = 'ReinhardC/NotebookPlush'
try {
    Set-ReleaseGitHubToken
    $scratch = New-ReleaseScratch
    $recipient = New-SelfSignedCertificate -Type DocumentEncryptionCert -Subject "CN=NotebookPlush $branch" `
        -CertStoreLocation Cert:\CurrentUser\My -KeyLength 3072 -KeyExportPolicy NonExportable -NotAfter (Get-Date).AddDays(1)
    $pem = "-----BEGIN CERTIFICATE-----`n" + [Convert]::ToBase64String($recipient.RawData, 'InsertLineBreaks') + "`n-----END CERTIFICATE-----`n"
    $recipientEncoded = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pem))
    $head = Invoke-ReleaseCommand 'gh' @('api', "repos/$repo/git/ref/heads/main", '--jq', '.object.sha')
    $workflowSha = Invoke-ReleaseCommand 'gh' @('api', "repos/$repo/contents/.github/workflows/build.yml", '--jq', '.sha')
    $payload = @{ref="refs/heads/$branch";sha=$head} | ConvertTo-Json -Compress
    [void](Invoke-ReleaseCommand 'gh' @('api', "repos/$repo/git/refs", '--method', 'POST', '--input', '-', '--silent') $payload)
    $branchCreated = $true
    $template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'tools\export-release-key.yml')).Replace('RECIPIENT_PLACEHOLDER', $recipientEncoded)
    $payload = @{branch=$branch;sha=$workflowSha;message='Prepare encrypted local signing-key import';content=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($template))} | ConvertTo-Json -Compress
    [void](Invoke-ReleaseCommand 'gh' @('api', "repos/$repo/contents/.github/workflows/build.yml", '--method', 'PUT', '--input', '-', '--silent') $payload)
    $dispatch = Invoke-ReleaseCommand 'gh' @('workflow', 'run', 'build.yml', '--repo', $repo, '--ref', $branch)
    if ($dispatch -match '/actions/runs/(\d+)') { $runId = $Matches[1] }
    Write-Output 'Waiting for GitHub to encrypt the signing key for this Windows account...'
    $deadline = (Get-Date).AddMinutes(10)
    while ((Get-Date) -lt $deadline) {
        $runsJson = Invoke-ReleaseCommand 'gh' @('run', 'list', '--repo', $repo, '--branch', $branch, '--event', 'workflow_dispatch', '--limit', '1', '--json', 'databaseId,status,conclusion')
        $runs = @()
        foreach ($item in (ConvertFrom-Json -InputObject $runsJson)) { $runs += $item }
        if ($runs.Count) {
            $runId = $runs[0].databaseId
            if ($runs[0].status -eq 'completed') {
                $runCompleted = $true
                if ($runs[0].conclusion -ne 'success') { throw "Encrypted key export failed. See https://github.com/$repo/actions/runs/$runId" }
                break
            }
        }
        Start-Sleep -Seconds 5
    }
    if (!$runCompleted) { throw 'The encrypted key export timed out.' }
    [void](Invoke-ReleaseCommand 'gh' @('run', 'download', "$runId", '--repo', $repo, '--name', 'encrypted-release-key', '--dir', $scratch))
    try {
        $bundleText = Unprotect-CmsMessage -Path (Join-Path $scratch 'encrypted-key.cms')
        $bundle = $bundleText | ConvertFrom-Json
        if ($bundle.schema -ne 1 -or !$bundle.keystore -or !$bundle.storePassword -or !$bundle.alias) { throw 'Invalid signing material' }
        [void][Convert]::FromBase64String($bundle.keystore)
    } catch { throw 'The encrypted signing material could not be validated on this Windows account.' }
    # Pin the actual published APK certificate, rather than trusting a certificate display name.
    [void](Invoke-ReleaseCommand 'gh' @('release', 'download', '--repo', $repo, '--pattern', 'androidApp-release.apk', '--dir', $scratch))
    $bundle | Add-Member -NotePropertyName signerSha256 -NotePropertyValue (Get-ReleaseSigner (Join-Path $scratch 'androidApp-release.apk'))
    $plainBytes = [Text.Encoding]::UTF8.GetBytes(($bundle | ConvertTo-Json -Compress))
    $protected = [Security.Cryptography.ProtectedData]::Protect($plainBytes, $releaseEntropy, 'CurrentUser')
    $protectedPath = Join-Path $scratch 'release-key.dpapi'
    [IO.File]::WriteAllBytes($protectedPath, $protected)
    Move-Item -LiteralPath $protectedPath -Destination $releaseVaultPath
    Write-Output "Local release signing configured in $releaseVaultPath"
    Write-Output "CI signing certificate SHA-256: $($bundle.signerSha256)"
} finally {
    if ($runId) {
        try {
            if (!$runCompleted) { [void](Invoke-ReleaseCommand 'gh' @('run', 'cancel', "$runId", '--repo', $repo)) }
            $artifacts = Invoke-ReleaseCommand 'gh' @('api', "repos/$repo/actions/runs/$runId/artifacts") | ConvertFrom-Json
            foreach ($artifact in $artifacts.artifacts) {
                [void](Invoke-ReleaseCommand 'gh' @('api', "repos/$repo/actions/artifacts/$($artifact.id)", '--method', 'DELETE', '--silent'))
            }
        } catch { Write-Warning 'Could not clean up the encrypted GitHub artifact; it expires after one day.' }
    }
    if ($branchCreated) {
        try { [void](Invoke-ReleaseCommand 'gh' @('api', "repos/$repo/git/refs/heads/$branch", '--method', 'DELETE', '--silent')) }
        catch { Write-Warning "Remove the temporary $branch branch from GitHub when possible." }
    }
    $env:GH_TOKEN = $previousToken
    if ($plainBytes) { [Array]::Clear($plainBytes, 0, $plainBytes.Length) }
    $bundleText = $null; $bundle = $null
    try { if ($recipient) { Remove-Item -LiteralPath $recipient.PSPath -DeleteKey } }
    finally { Remove-ReleaseScratch $scratch }
}

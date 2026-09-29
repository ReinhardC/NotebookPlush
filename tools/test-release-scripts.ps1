# Windows/OpenSSL interoperability and native argument parsing; fixture data only.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-common.ps1')
foreach ($script in @('setup-release-key.ps1','build-release.ps1','deploy-release.ps1','tools\release-common.ps1')) {
    $tokens = $null; $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile((Join-Path $releaseProjectRoot $script), [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw "PowerShell parse errors in $script" }
}
$scratch = New-ReleaseScratch
$recipient = $null
try {
    $recipient = New-SelfSignedCertificate -Type DocumentEncryptionCert -Subject 'CN=NotebookPlush fixture' `
        -CertStoreLocation Cert:\CurrentUser\My -KeyLength 3072 -KeyExportPolicy NonExportable
    $pem = "-----BEGIN CERTIFICATE-----`n" + [Convert]::ToBase64String($recipient.RawData, 'InsertLineBreaks') + "`n-----END CERTIFICATE-----`n"
    $certificatePath = Join-Path $scratch 'fixture recipient.pem'
    $inputPath = Join-Path $scratch 'fixture input.json'
    $encryptedPath = Join-Path $scratch 'encrypted fixture.cms'
    [IO.File]::WriteAllText($certificatePath, $pem, [Text.Encoding]::ASCII)
    $fixture = '{"fixture":"quotes, spaces and escaped Unicode: \uD83E\uDDF8"}'
    [IO.File]::WriteAllText($inputPath, $fixture, [Text.Encoding]::UTF8)
    $openssl = Join-Path $env:ProgramFiles 'Git\usr\bin\openssl.exe'
    if (!(Test-Path -LiteralPath $openssl)) { throw 'This interoperability check requires Git for Windows OpenSSL.' }
    [void](Invoke-ReleaseCommand $openssl @('cms','-encrypt','-aes-256-cbc','-binary','-in',$inputPath,'-out',$encryptedPath,'-outform','PEM',$certificatePath))
    $actual = Unprotect-CmsMessage -Path $encryptedPath
    if ($actual.TrimStart([char]0xFEFF) -ne $fixture) { throw 'OpenSSL-to-Windows CMS round trip failed.' }
    $java = Get-ReleaseJava
    if (!(Test-Path -LiteralPath (Join-Path $java 'bin\jlink.exe'))) { throw 'JDK selection failed.' }
    foreach ($json in @('[]', '[{"databaseId":123,"status":"completed","conclusion":"success"}]')) {
        $runs = @()
        foreach ($item in (ConvertFrom-Json -InputObject $json)) { $runs += $item }
        if ($json -eq '[]' -and $runs.Count -ne 0) { throw 'Empty GitHub run list handling failed.' }
        if ($json -ne '[]' -and ($runs.Count -ne 1 -or $runs[0].databaseId -ne 123)) { throw 'GitHub run list handling failed.' }
    }
    $fixtureBytes = [Text.Encoding]::UTF8.GetBytes($fixture)
    $protected = [Security.Cryptography.ProtectedData]::Protect($fixtureBytes, $releaseEntropy, 'CurrentUser')
    $restored = [Security.Cryptography.ProtectedData]::Unprotect($protected, $releaseEntropy, 'CurrentUser')
    if ([Text.Encoding]::UTF8.GetString($restored) -ne $fixture) { throw 'Windows signing store round trip failed.' }
    Write-Output 'Release scripts parsed; encrypted CMS transfer, paths with spaces, and JDK selection passed.'
} finally {
    if ($recipient) { Remove-Item -LiteralPath $recipient.PSPath -DeleteKey }
    Remove-ReleaseScratch $scratch
}

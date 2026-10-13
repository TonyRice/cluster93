# Installs the c93 CLI on Windows (D-049):
#   irm https://github.com/TonyRice/cluster93/releases/latest/download/install.ps1 | iex
#
# Environment: C93_VERSION (a tag, default latest), C93_INSTALL_DIR
# (default %LOCALAPPDATA%\Programs\cluster93), C93_RELEASES (owner/repo), C93_INSTALL_URL,
# C93_RELEASE_PUBLIC_KEY (a PEM file to check signatures with instead of the key below), and
# C93_INSECURE_SKIP_SIGNATURE=1 (trust HTTPS alone).
#
# Every release's SHA256SUMS is signed with the project's release key (D-069); this checks the signature with .NET's
# own ECDSA before trusting any checksum in it.
# Everything happens inside Install-Cluster93, called on the last line, so a cut-off download runs nothing.

$Cluster93ReleaseKey = @'
-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEx6p8qv79K2ltKawkKzbs1xYZ1QyR
hD6NugZBZL+S2K5MijsmPijVZJI3y+kVcyioFmPTDdzKdSnWJj3916QN/Q==
-----END PUBLIC KEY-----
'@

# The ECDSA P-256 signature `openssl dgst -sign` writes is DER, SEQUENCE { INTEGER r, INTEGER s }; .NET wants r and s
# as 32 bytes each.
function ConvertFrom-DerSignature([byte[]]$Der) {
    if ($Der.Length -lt 8 -or $Der[0] -ne 0x30) { throw 'SHA256SUMS.sig is not an ECDSA signature' }
    $i = 2
    if ($Der[1] -band 0x80) { $i += $Der[1] -band 0x7f }
    $raw = New-Object byte[] 64
    foreach ($half in 0, 1) {
        if ($i + 2 -gt $Der.Length -or $Der[$i] -ne 0x02) { throw 'SHA256SUMS.sig is not an ECDSA signature' }
        $length = $Der[$i + 1]
        $start = $i + 2
        $i = $start + $length
        if ($i -gt $Der.Length) { throw 'SHA256SUMS.sig is not an ECDSA signature' }
        while ($length -gt 32 -and $Der[$start] -eq 0) { $start++; $length-- }
        if ($length -gt 32) { throw 'SHA256SUMS.sig is not a P-256 signature' }
        [Array]::Copy($Der, $start, $raw, $half * 32 + 32 - $length, $length)
    }
    return , $raw
}

# Throws unless $Signature is the release key's signature over $Sums (both files).
function Confirm-Cluster93Signature([string]$Sums, [string]$Signature) {
    $pem = if ($env:C93_RELEASE_PUBLIC_KEY) { Get-Content -Raw $env:C93_RELEASE_PUBLIC_KEY } else { $Cluster93ReleaseKey }
    $spki = [Convert]::FromBase64String((($pem -split "`r?`n") | Where-Object { $_ -and $_ -notmatch '-----' }) -join '')
    # A P-256 SubjectPublicKeyInfo is 91 bytes: this fixed 26-byte header, 0x04, then X and Y.
    $header = '3059301306072a8648ce3d020106082a8648ce3d030107034200'
    if ($spki.Length -ne 91 -or (-join ($spki[0..26] | ForEach-Object { $_.ToString('x2') })) -ne ($header + '04')) {
        throw 'The release key is not an uncompressed P-256 public key'
    }
    $point = New-Object System.Security.Cryptography.ECPoint
    $point.X = [byte[]]$spki[27..58]
    $point.Y = [byte[]]$spki[59..90]
    $parameters = New-Object System.Security.Cryptography.ECParameters
    $parameters.Curve = [System.Security.Cryptography.ECCurve+NamedCurves]::nistP256
    $parameters.Q = $point
    $ecdsa = [System.Security.Cryptography.ECDsa]::Create($parameters)
    $signed = $ecdsa.VerifyData([System.IO.File]::ReadAllBytes($Sums), (ConvertFrom-DerSignature ([System.IO.File]::ReadAllBytes($Signature))),
        [System.Security.Cryptography.HashAlgorithmName]::SHA256)
    if (-not $signed) { throw "SHA256SUMS isn't signed by the c93 release key. Nothing was installed." }
}

function Install-Cluster93 {
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'

    $repo = if ($env:C93_RELEASES) { $env:C93_RELEASES } else { 'TonyRice/cluster93' }
    if ($env:C93_INSTALL_URL) { $base = $env:C93_INSTALL_URL.TrimEnd('/') }
    elseif ($env:C93_VERSION) { $base = "https://github.com/$repo/releases/download/$($env:C93_VERSION)" }
    else { $base = "https://github.com/$repo/releases/latest/download" }

    $arch = $env:PROCESSOR_ARCHITECTURE
    if ($arch -ne 'AMD64') { throw "No c93 build for $arch Windows yet; builds exist for x64." }
    $asset = 'cluster93-windows-x64.exe'

    $dir = if ($env:C93_INSTALL_DIR) { $env:C93_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'Programs\cluster93' }
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("cluster93-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        Write-Host "Downloading $asset from $base"
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        # Prebuilt native binaries are Linux-only for now (see docs/guides/cli.md).
        try { Invoke-WebRequest -UseBasicParsing -Uri "$base/$asset" -OutFile (Join-Path $tmp $asset) }
        catch { throw "Prebuilt c93 binaries are Linux-only for now. Build the CLI on Windows from source: https://github.com/$repo/blob/main/docs/guides/cli.md#build-from-source" }
        Invoke-WebRequest -UseBasicParsing -Uri "$base/SHA256SUMS" -OutFile (Join-Path $tmp 'SHA256SUMS')
        if ($env:C93_INSECURE_SKIP_SIGNATURE -eq '1') {
            Write-Warning "Not checking the release's signature (C93_INSECURE_SKIP_SIGNATURE=1)"
        }
        else {
            try { Invoke-WebRequest -UseBasicParsing -Uri "$base/SHA256SUMS.sig" -OutFile (Join-Path $tmp 'SHA256SUMS.sig') }
            catch { throw "Download failed: $base/SHA256SUMS.sig. Releases are signed; an unsigned one isn't installed." }
            Confirm-Cluster93Signature (Join-Path $tmp 'SHA256SUMS') (Join-Path $tmp 'SHA256SUMS.sig')
        }

        $line = Get-Content (Join-Path $tmp 'SHA256SUMS') | Where-Object { ($_ -split '\s+')[1] -replace '^\*', '' -eq $asset } | Select-Object -First 1
        if (-not $line) { throw "SHA256SUMS has no entry for $asset" }
        $expected = ($line -split '\s+')[0].ToLower()
        $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $tmp $asset)).Hash.ToLower()
        if ($expected -ne $actual) { throw "Checksum mismatch for ${asset}: expected $expected, got $actual. Nothing was installed." }

        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $target = Join-Path $dir 'c93.exe'
        # A running c93.exe can't be overwritten, but it can be renamed out of the way.
        if (Test-Path $target) { Move-Item -Force $target "$target.old" }
        Move-Item -Force (Join-Path $tmp $asset) $target
        Remove-Item -Force "$target.old" -ErrorAction SilentlyContinue

        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        if (-not (($userPath -split ';') -contains $dir)) {
            [Environment]::SetEnvironmentVariable('Path', ($userPath.TrimEnd(';') + ';' + $dir).TrimStart(';'), 'User')
            Write-Host "Added $dir to your PATH; open a new terminal to use it."
        }
        Write-Host "Installed $(& $target --version) to $target"
        Write-Host "Get started: c93 login https://your-cluster"
    }
    finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}

Install-Cluster93

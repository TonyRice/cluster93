# Installs the automately CLI on Windows (D-049):
#   irm https://github.com/adamnlynn/automately-v2/releases/latest/download/install.ps1 | iex
#
# Environment: AUTOMATELY_VERSION (a tag, default latest), AUTOMATELY_INSTALL_DIR
# (default %LOCALAPPDATA%\Programs\automately), AUTOMATELY_RELEASES (owner/repo), AUTOMATELY_INSTALL_URL.
# Everything happens inside Install-Automately, called on the last line, so a cut-off download runs nothing.

function Install-Automately {
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'

    $repo = if ($env:AUTOMATELY_RELEASES) { $env:AUTOMATELY_RELEASES } else { 'adamnlynn/automately-v2' }
    if ($env:AUTOMATELY_INSTALL_URL) { $base = $env:AUTOMATELY_INSTALL_URL.TrimEnd('/') }
    elseif ($env:AUTOMATELY_VERSION) { $base = "https://github.com/$repo/releases/download/$($env:AUTOMATELY_VERSION)" }
    else { $base = "https://github.com/$repo/releases/latest/download" }

    $arch = $env:PROCESSOR_ARCHITECTURE
    if ($arch -ne 'AMD64') { throw "No automately build for $arch Windows yet; builds exist for x64." }
    $asset = 'automately-windows-x64.exe'

    $dir = if ($env:AUTOMATELY_INSTALL_DIR) { $env:AUTOMATELY_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'Programs\automately' }
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("automately-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        Write-Host "Downloading $asset from $base"
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri "$base/$asset" -OutFile (Join-Path $tmp $asset)
        Invoke-WebRequest -UseBasicParsing -Uri "$base/SHA256SUMS" -OutFile (Join-Path $tmp 'SHA256SUMS')

        $line = Get-Content (Join-Path $tmp 'SHA256SUMS') | Where-Object { ($_ -split '\s+')[1] -replace '^\*', '' -eq $asset } | Select-Object -First 1
        if (-not $line) { throw "SHA256SUMS has no entry for $asset" }
        $expected = ($line -split '\s+')[0].ToLower()
        $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $tmp $asset)).Hash.ToLower()
        if ($expected -ne $actual) { throw "Checksum mismatch for ${asset}: expected $expected, got $actual. Nothing was installed." }

        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $target = Join-Path $dir 'automately.exe'
        # A running automately.exe can't be overwritten, but it can be renamed out of the way.
        if (Test-Path $target) { Move-Item -Force $target "$target.old" }
        Move-Item -Force (Join-Path $tmp $asset) $target
        Remove-Item -Force "$target.old" -ErrorAction SilentlyContinue

        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        if (-not (($userPath -split ';') -contains $dir)) {
            [Environment]::SetEnvironmentVariable('Path', ($userPath.TrimEnd(';') + ';' + $dir).TrimStart(';'), 'User')
            Write-Host "Added $dir to your PATH; open a new terminal to use it."
        }
        Write-Host "Installed $(& $target --version) to $target"
        Write-Host "Get started: automately login https://your-cluster"
    }
    finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}

Install-Automately

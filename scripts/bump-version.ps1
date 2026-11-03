[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('major', 'minor', 'patch')]
    [string]$Part,

    [switch]$Commit,
    [switch]$Tag
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$versionFile = Join-Path $root 'VERSION'
$pomFile = Join-Path $root 'pom.xml'

$current = (Get-Content -LiteralPath $versionFile -Raw).Trim()
if ($current -notmatch '^(\d+)\.(\d+)\.(\d+)$') {
    throw "Invalid VERSION format: $current"
}

$major = [int]$Matches[1]
$minor = [int]$Matches[2]
$patch = [int]$Matches[3]

switch ($Part) {
    'major' { $major++; $minor = 0; $patch = 0 }
    'minor' { $minor++; $patch = 0 }
    'patch' { $patch++ }
}

$newVersion = "$major.$minor.$patch"
$utf8 = New-Object System.Text.UTF8Encoding($false)

[System.IO.File]::WriteAllText($versionFile, "$newVersion`n", $utf8)

$pom = [System.IO.File]::ReadAllText($pomFile)
$pattern = '(<artifactId>bilibili-deepseek-bot</artifactId>\s*<version>)[^<]+(</version>)'
if (-not [regex]::IsMatch($pom, $pattern)) {
    throw 'Could not find the project version in pom.xml'
}
$newPom = [regex]::Replace($pom, $pattern, "`${1}$newVersion`${2}", 1)
[System.IO.File]::WriteAllText($pomFile, $newPom, $utf8)

Write-Host "Version updated: $current -> $newVersion"

if ($Tag -and -not $Commit) {
    throw 'Use -Tag together with -Commit'
}

if ($Commit) {
    & git -C $root add VERSION pom.xml
    if ($LASTEXITCODE -ne 0) { throw 'git add failed' }
    if (Test-Path -LiteralPath (Join-Path $root 'CHANGELOG.md')) {
        & git -C $root add CHANGELOG.md
        if ($LASTEXITCODE -ne 0) { throw 'git add CHANGELOG.md failed' }
    }

    & git -C $root commit -m "chore(release): v$newVersion"
    if ($LASTEXITCODE -ne 0) { throw 'git commit failed' }

    if ($Tag) {
        & git -C $root tag -a "v$newVersion" -m "v$newVersion"
        if ($LASTEXITCODE -ne 0) { throw 'git tag failed' }
        Write-Host "Created tag: v$newVersion"
    }
}

Write-Host 'Next: update CHANGELOG.md, run mvn clean package, then push commits/tags.'

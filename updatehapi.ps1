#Requires -Version 5.1
<#
.SYNOPSIS
    Refreshes the patched copies of org.hl7.fhir.core classes bundled in matchbox-engine.

.DESCRIPTION
    matchbox-engine keeps local, lightly-patched copies of a handful of classes from
    hapifhir/org.hl7.fhir.core under:

        matchbox-engine/src/main/java/org/hl7/fhir/

    These shadow the upstream classes on the classpath. Whenever <fhir.core.version> in
    pom.xml is bumped, those copies must be re-synced with the matching upstream tag so
    the matchbox patches can be re-applied / verified on top of current upstream code.

    This script downloads every such file from GitHub (raw) at the given tag and
    overwrites the local copy. It never deletes or creates files: only paths that
    already exist locally are touched. A file that cannot be fetched (e.g. moved or
    removed upstream) is reported and left untouched.

    After running, review the diff and re-apply the matchbox changes:

        git diff -- matchbox-engine/src/main/java/org/hl7/fhir

.PARAMETER HapiVersion
    The org.hl7.fhir.core git tag to pull files from (e.g. 6.10.0).
    Defaults to the <fhir.core.version> value found in pom.xml.

.PARAMETER Repo
    GitHub "owner/name" holding the upstream sources. Default: hapifhir/org.hl7.fhir.core

.PARAMETER DryRun
    Show what would change without writing any file.

.EXAMPLE
    ./updatehapi.ps1
    Sync every local copy to the tag declared in pom.xml.

.EXAMPLE
    ./updatehapi.ps1 -HapiVersion 6.10.0 -DryRun
#>

[CmdletBinding()]
param(
    [string] $HapiVersion,
    [string] $Repo = 'hapifhir/org.hl7.fhir.core',
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$repoRoot  = $PSScriptRoot
$engineSrc = Join-Path $repoRoot 'matchbox-engine/src/main/java'
$targetDir = Join-Path $engineSrc 'org/hl7/fhir'

if (-not (Test-Path -LiteralPath $targetDir)) {
    throw "Cannot find '$targetDir'. Run this script from the matchbox repository root."
}

Write-Host "Syncing org.hl7.fhir.core sources from $Repo@$HapiVersion" -ForegroundColor Cyan
if ($DryRun) { Write-Host "(dry run - no files will be written)" -ForegroundColor Yellow }

$files = Get-ChildItem -LiteralPath $targetDir -Recurse -File -Filter *.java | Sort-Object FullName

$updated   = New-Object System.Collections.Generic.List[string]
$unchanged = New-Object System.Collections.Generic.List[string]
$failed    = New-Object System.Collections.Generic.List[string]

foreach ($file in $files) {
    # Package path relative to .../src/main/java, normalised to forward slashes.
    $packagePath = $file.FullName.Substring($engineSrc.Length).TrimStart('\', '/').Replace('\', '/')

    # org/hl7/fhir/<module>/...  ->  upstream maven module  org.hl7.fhir.<module>
    $segments = $packagePath.Split('/')
    if ($segments.Length -lt 4) {
        Write-Warning "Skipping unexpected path: $packagePath"
        continue
    }
    $module = "org.hl7.fhir.$($segments[3])"
    $url    = "https://raw.githubusercontent.com/$Repo/refs/tags/$HapiVersion/$module/src/main/java/$packagePath"

    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing
        $newBytes = [System.IO.File]::ReadAllBytes($tmp)
    }
    catch {
        Write-Warning "FAILED   $packagePath"
        Write-Warning "         $url"
        Write-Warning "         $($_.Exception.Message)"
        $failed.Add($packagePath)
        continue
    }
    finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }

    $oldBytes = [System.IO.File]::ReadAllBytes($file.FullName)
    $same = ($oldBytes.Length -eq $newBytes.Length) -and
            (-not (Compare-Object $oldBytes $newBytes -SyncWindow 0))
    if ($same) {
        $unchanged.Add($packagePath)
        continue
    }

    if ($DryRun) {
        Write-Host "WOULD UPDATE  $packagePath" -ForegroundColor Yellow
    }
    else {
        [System.IO.File]::WriteAllBytes($file.FullName, $newBytes)
        Write-Host "UPDATED       $packagePath" -ForegroundColor Green
    }
    $updated.Add($packagePath)
}

Write-Host ""
Write-Host ("Done. updated={0}  unchanged={1}  failed={2}" -f $updated.Count, $unchanged.Count, $failed.Count)

if ($failed.Count) {
    Write-Host ""
    Write-Host "Could not fetch (left untouched - check if moved/removed upstream):" -ForegroundColor Red
    $failed | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
}

Write-Host ""
Write-Host "Next: review and re-apply the matchbox patches:"
Write-Host "  git diff -- matchbox-engine/src/main/java/org/hl7/fhir"

if ($failed.Count) { exit 1 }

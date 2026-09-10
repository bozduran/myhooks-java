<#
.SYNOPSIS
    Removes the pre-commit and commit-msg hooks installed by
    scripts/install-hooks.ps1 (or install-hooks.sh), restoring any hook that
    was backed up.

.DESCRIPTION
    A hook is only removed when it carries the myhooks marker comment; hooks
    installed by something else are left untouched. If a
    <hook>.myhooks-backup file exists it is restored over the removed hook.

.PARAMETER TargetRepo
    Repository whose .git\hooks directory holds the hooks. Defaults to the
    current directory.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\deactivate-hooks.ps1 C:\src\MyReports
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$TargetRepo = (Get-Location).Path
)

$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
    [Console]::Error.WriteLine($Message)
    exit 1
}

$HooksDir = Join-Path $TargetRepo '.git\hooks'
if (-not (Test-Path -LiteralPath $HooksDir -PathType Container)) {
    Fail "error: $HooksDir not found; is $TargetRepo a git repository?"
}

function Remove-MyHook([string]$Name) {
    $path = Join-Path $HooksDir $Name
    $backup = "${path}.myhooks-backup"

    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Write-Host "no $Name installed"
        return
    }
    if (-not (Select-String -LiteralPath $path -Pattern 'myhooks-hook:' -Quiet)) {
        Write-Host "skipped $Name (not installed by myhooks)"
        return
    }

    Remove-Item -LiteralPath $path -Force
    if (Test-Path -LiteralPath $backup -PathType Leaf) {
        Move-Item -LiteralPath $backup -Destination $path
        Write-Host "restored previous $Name"
    } else {
        Write-Host "removed myhooks $Name"
    }
}

Remove-MyHook 'pre-commit'
Remove-MyHook 'commit-msg'

Write-Host "myhooks hooks deactivated in $HooksDir"

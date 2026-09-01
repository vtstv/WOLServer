# Simple WOL Server - 1-Click Amazon Appstore Deploy
param(
    [switch]$Build = $true,
    [switch]$Publish = $false
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PythonScript = Join-Path $ScriptDir "deploy_amazon.py"

$ArgsList = @()
if ($Build) { $ArgsList += "--build" }
if ($Publish) { $ArgsList += "--publish" }

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Simple WOL Server - Amazon Deployer" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

python $PythonScript @ArgsList

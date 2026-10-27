param(
    [Parameter(Mandatory=$true)][string]$Service,
    [ValidateSet('api')][string]$Role = 'api',
    [Parameter(Mandatory=$true)][string]$EnvFile,
    [Parameter(Mandatory=$true)][string]$HostEnvFile,
    [switch]$Check
)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$toolPython = Join-Path $projectRoot '.venv/Scripts/python.exe'
$arguments = @((Join-Path $PSScriptRoot 'dev.py'), '--service', $Service, '--role', $Role, '--env-file', $EnvFile, '--host-env-file', $HostEnvFile)
if ($Check) { $arguments += '--check' }
& $toolPython @arguments
exit $LASTEXITCODE

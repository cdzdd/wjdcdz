param([ValidateSet('smoke','quick','full')][string]$Profile='quick')
$ErrorActionPreference='Stop'
# Each group owns and removes its synthetic rows; no demo server is started.
& (Join-Path $PSScriptRoot 'Seed-PerformanceData.ps1') -Profile $Profile

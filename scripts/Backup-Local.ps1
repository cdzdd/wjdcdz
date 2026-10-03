param([switch]$TestDatabase)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $workspace '.runtime'
$config = Get-Content -LiteralPath (Join-Path $runtime 'local-env.json') -Raw | ConvertFrom-Json
$folder = Join-Path $runtime 'backups'
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$database = if ($TestDatabase) { $config.testDatabase } else { $config.database }
$output = Join-Path $folder ($database + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.sql')
& (Join-Path $config.mysqlHome 'bin\mysqldump.exe') "--defaults-file=$runtime\mysql-app.cnf" --single-transaction --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 "--result-file=$output" $database
if ($LASTEXITCODE -ne 0) { throw 'Backup failed. Do not use the incomplete output file.' }
Write-Output "Backup saved: $output"

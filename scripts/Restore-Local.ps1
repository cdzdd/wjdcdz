param([Parameter(Mandatory=$true)][string]$BackupFile,[switch]$TestDatabase)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $workspace '.runtime'
$config = Get-Content -LiteralPath (Join-Path $runtime 'local-env.json') -Raw | ConvertFrom-Json
$inputPath = (Resolve-Path -LiteralPath $BackupFile).Path
$database = if ($TestDatabase) { $config.testDatabase } else { $config.database }
$start = New-Object Diagnostics.ProcessStartInfo
$start.FileName = Join-Path $config.mysqlHome 'bin\mysql.exe'
$start.Arguments = "`"--defaults-file=$runtime\mysql-app.cnf`" --default-character-set=utf8mb4 $database"
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardInput = $true
$start.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
$process = [Diagnostics.Process]::Start($start)
$process.StandardInput.Write([IO.File]::ReadAllText($inputPath,[Text.Encoding]::UTF8))
$process.StandardInput.Close()
$process.WaitForExit()
if ($process.ExitCode -ne 0) { throw 'Restore failed; inspect the MySQL error above before restarting the application.' }
Write-Output "Restored database: $database."

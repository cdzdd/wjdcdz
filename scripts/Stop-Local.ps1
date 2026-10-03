param([switch]$KeepDatabase)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$runtime = Join-Path $workspace '.runtime'
$targets = @(@{name='backend';marker=(Join-Path $workspace 'server\target');exe='java.exe'},@{name='vite';marker=(Join-Path $workspace 'web\node_modules\vite\bin\vite.js');exe='node.exe'})
if (-not $KeepDatabase) { $targets += @{name='mysql-process';marker="--defaults-file=$(Join-Path $runtime 'mysql.ini')";exe='mysqld.exe'} }
foreach ($target in $targets) {
    $file = Join-Path $runtime "$($target.name).pid"
    if (-not (Test-Path $file)) { continue }
    $processId = 0
    if (-not [int]::TryParse((Get-Content -LiteralPath $file -Raw).Trim(),[ref]$processId) -or $processId -le 0) { continue }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
    if (-not $process) { continue }
    if ($process.Name -ne $target.exe -or -not $process.CommandLine -or -not $process.CommandLine.Contains($target.marker)) {
        Write-Warning "Skipped $($target.name) PID ${processId}: it does not belong to this project."
        continue
    }
    if ($target.name -eq 'mysql-process') {
        $config = Get-Content -LiteralPath (Join-Path $runtime 'local-env.json') -Raw | ConvertFrom-Json
        & (Join-Path $config.mysqlHome 'bin\mysqladmin.exe') "--defaults-file=$runtime\mysql-root.cnf" shutdown
        if ($LASTEXITCODE -ne 0) { throw 'Graceful database shutdown failed; no other MySQL process was stopped.' }
    } else {
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Wait-Process -Id $processId -Timeout 15 -ErrorAction SilentlyContinue
        $remaining = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($remaining -and -not $remaining.HasExited) { throw "Project process did not stop: $($target.name). PID file was retained." }
    }
    [IO.File]::WriteAllText($file,'0')
    Write-Output "Stopped project process: $($target.name)."
}
Write-Output 'Project stop completed. Database files are preserved.'

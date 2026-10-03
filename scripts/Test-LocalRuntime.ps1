param([switch]$IncludeApps)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $workspace '.runtime'
foreach ($name in 'Start-Local.ps1','Stop-Local.ps1','Initialize-Local.ps1','Backup-Local.ps1') {
    if (-not (Test-Path (Join-Path $PSScriptRoot $name))) { throw "Missing runtime script: $name" }
    $tokens = $null; $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$errors)
    if ($errors.Count) { throw "$name contains PowerShell syntax errors." }
}
& (Join-Path $PSScriptRoot 'Start-Local.ps1') -DatabaseOnly
$config = Get-Content -LiteralPath (Join-Path $runtime 'local-env.json') -Raw | ConvertFrom-Json
$mysql = Join-Path $config.mysqlHome 'bin\mysql.exe'
& $mysql "--defaults-file=$runtime\mysql-app.cnf" --batch --skip-column-names --execute="SELECT VERSION(), @@port; SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('fund_sim','fund_sim_test');"
if ($LASTEXITCODE -ne 0) { throw 'Database authentication failed.' }
# A colliding listener must be reported before any application is launched.
$portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,8080)
$probeStarted = $false
try {
    try { $portProbe.Start(); $probeStarted = $true } catch { }
    if ($probeStarted) {
        $rejected = $false
        try { & (Join-Path $PSScriptRoot 'Start-Local.ps1') } catch {
            if ($_.Exception.Message -notmatch 'Port 8080 is occupied') { throw }
            $rejected = $true
        }
        if (-not $rejected) { throw 'Startup failed to reject port collision.' }
        Write-Output 'Port collision was rejected without stopping its owner.'
    }
} finally { $portProbe.Stop() }
# A stale or forged PID must not make Stop-Local kill an unrelated process.
$probe = Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList '-NoProfile -Command Start-Sleep -Seconds 30' -WindowStyle Hidden -PassThru
$pidFiles = @((Join-Path $runtime 'backend.pid'),(Join-Path $runtime 'vite.pid'))
$previous = @{}
foreach ($pidFile in $pidFiles) { $previous[$pidFile] = if (Test-Path $pidFile) { Get-Content -LiteralPath $pidFile -Raw } else { '0' } }
try {
    foreach ($pidFile in $pidFiles) { $probe.Id | Set-Content -LiteralPath $pidFile }
    & (Join-Path $PSScriptRoot 'Stop-Local.ps1') -KeepDatabase
    if (-not (Get-Process -Id $probe.Id -ErrorAction SilentlyContinue)) { throw 'Stop script killed an unrelated process.' }
} finally {
    foreach ($pidFile in $pidFiles) { [IO.File]::WriteAllText($pidFile,$previous[$pidFile]) }
    Stop-Process -Id $probe.Id -Force -ErrorAction SilentlyContinue
}
if ($IncludeApps) {
    & (Join-Path $PSScriptRoot 'Start-Local.ps1')
    $health = Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health'
    if ($health.status -ne 'UP') { throw 'Backend health check failed.' }
    if ((Invoke-WebRequest 'http://127.0.0.1:5173' -UseBasicParsing).StatusCode -ne 200) { throw 'Web response failed.' }
}
Write-Output 'Local runtime verification passed.'

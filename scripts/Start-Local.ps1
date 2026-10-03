param([switch]$DatabaseOnly)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$runtime = Join-Path $workspace '.runtime'
& (Join-Path $PSScriptRoot 'Initialize-Local.ps1')
$config = Get-Content -LiteralPath (Join-Path $runtime 'local-env.json') -Raw | ConvertFrom-Json
$logs = Join-Path $runtime 'logs'
function Get-OwnedProcess([string]$name,[string]$marker) {
    $pidFile = Join-Path $runtime "$name.pid"
    if (-not (Test-Path $pidFile)) { return $null }
    $processId = 0
    if (-not [int]::TryParse((Get-Content -LiteralPath $pidFile -Raw).Trim(),[ref]$processId)) { return $null }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
    if ($process -and $process.CommandLine -and $process.CommandLine.Contains($marker)) { return $process }
    return $null
}
function Assert-FreePort([int]$port) {
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$port)
    try { $probe.Start() } catch { throw "Port $port is occupied by another process. Stop it or resolve the conflict before starting this project." } finally { $probe.Stop() }
}
$mysql = Join-Path $config.mysqlHome 'bin\mysql.exe'
$mysqlIni = Join-Path $runtime 'mysql.ini'
if (-not (Get-OwnedProcess 'mysql-process' "--defaults-file=$mysqlIni")) {
    Assert-FreePort 13306
    $dbProcess = Start-Process (Join-Path $config.mysqlHome 'bin\mysqld.exe') -ArgumentList "`"--defaults-file=$mysqlIni`"" -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'mysql-stdout.log') -RedirectStandardError (Join-Path $logs 'mysql-stderr.log') -PassThru
    $dbProcess.Id | Set-Content -LiteralPath (Join-Path $runtime 'mysql-process.pid')
}
$dbReady = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    & $mysql "--defaults-file=$runtime\mysql-app.cnf" --execute='SELECT 1' *> $null
    if ($LASTEXITCODE -eq 0) { $dbReady = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $dbReady) { throw 'Local database failed its authentication check. Read .runtime/logs/mysql-error.log.' }
if ($DatabaseOnly) { Write-Output 'Project database is ready at 127.0.0.1:13306.'; return }
$java = (Get-Command java.exe -ErrorAction Stop).Source
$maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
$npm = (Get-Command npm.cmd -ErrorAction Stop).Source
$node = (Get-Command node.exe -ErrorAction Stop).Source
$javaVersion = (& $java --version | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '(?m)^(java|openjdk) 21\.') { throw 'Java 21 is required.' }
$nodeMajor = [int]((& $node --version).TrimStart('v').Split('.')[0])
if ($nodeMajor -lt 22) { throw 'Node.js 22 or newer is required.' }
$server = Join-Path $workspace 'server'
$web = Join-Path $workspace 'web'
$backendMarker = Join-Path $server 'target'
$viteScript = Join-Path $web 'node_modules\vite\bin\vite.js'
$backend = Get-OwnedProcess 'backend' $backendMarker
$vite = Get-OwnedProcess 'vite' $viteScript
if (-not $backend) { Assert-FreePort 8080 }
if (-not $vite) { Assert-FreePort 5173 }
if (-not $backend) {
    & $maven -f (Join-Path $server 'pom.xml') '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    $jar = Get-ChildItem -LiteralPath $backendMarker -Filter '*.jar' | Where-Object Name -NotLike '*.original' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw 'No backend executable JAR was produced.' }
    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:SPRING_DATASOURCE_URL = $config.jdbcUrl
    $env:SPRING_DATASOURCE_USERNAME = $config.user
    $env:SPRING_DATASOURCE_PASSWORD = $config.password
    $env:DB_URL = $config.jdbcUrl; $env:DB_USER = $config.user; $env:DB_PASSWORD = $config.password
    $env:SERVER_ADDRESS = '127.0.0.1'; $env:SERVER_PORT = '8080'
    $backend = Start-Process $java -ArgumentList @('-jar',"`"$($jar.FullName)`"") -WorkingDirectory $server -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'backend-stdout.log') -RedirectStandardError (Join-Path $logs 'backend-stderr.log') -PassThru
    $backend.Id | Set-Content -LiteralPath (Join-Path $runtime 'backend.pid')
}
if (-not $vite) {
    Push-Location $web
    try {
        if (-not (Test-Path $viteScript)) { & $npm ci; if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency install failed.' } }
        & $npm run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
    $vite = Start-Process $node -ArgumentList @("`"$viteScript`"",'--host','127.0.0.1','--port','5173','--strictPort') -WorkingDirectory $web -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'web-stdout.log') -RedirectStandardError (Join-Path $logs 'web-stderr.log') -PassThru
    $vite.Id | Set-Content -LiteralPath (Join-Path $runtime 'vite.pid')
}
foreach ($url in 'http://127.0.0.1:8080/actuator/health','http://127.0.0.1:5173') {
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try { if ((Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { $ready = $true; break } } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw "Service did not become ready: $url. Check .runtime/logs." }
}
Write-Output 'Platform ready: http://127.0.0.1:5173 (backend 8080, isolated MySQL 13306).'

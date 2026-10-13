param([switch]$SkipBuild,[ValidateSet('all','upgrade')][string]$Mode='all')
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$runtime=Join-Path $workspace '.runtime'
$settings=Get-Content -LiteralPath (Join-Path $runtime 'local-env.json') -Raw | ConvertFrom-Json
$testDatabase=$settings.testDatabase
if ($testDatabase -notin @('fund_sim_test','fund_sim_upgrade_test')) { throw 'Browser acceptance requires an explicitly controlled test database.' }
if ($settings.testJdbcUrl -notmatch '^jdbc:mysql://127\.0\.0\.1:13306/(fund_sim_test|fund_sim_upgrade_test)\?[^\s]*$' -or $Matches[1] -ne $testDatabase) { throw 'Test JDBC URL and configured test database must match exactly.' }
$mysql=Join-Path $settings.mysqlHome 'bin/mysql.exe'
$mysqlDefaults=Join-Path $runtime 'mysql-app.cnf'
$database=(& $mysql "--defaults-file=$mysqlDefaults" "--database=$testDatabase" '--batch' '--skip-column-names' '--execute=SELECT DATABASE();' | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $database -ne $testDatabase) { throw 'Isolated test database verification failed.' }
foreach ($port in 18880,15173) {
    $probe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$port)
    try { $probe.Start() } catch { throw "Acceptance port $port is already occupied; its process will not be stopped." } finally { $probe.Stop() }
}
$runId=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$runRoot=Join-Path $runtime "clearing-acceptance-$runId"
[void](New-Item -ItemType Directory -Path $runRoot)
$server=Join-Path $workspace 'server'
$web=Join-Path $workspace 'web'
$java=(Get-Command java.exe -ErrorAction Stop).Source
$node=(Get-Command node.exe -ErrorAction Stop).Source
$npm=(Get-Command npm.cmd -ErrorAction Stop).Source
$viteScript=Join-Path $web 'node_modules/vite/bin/vite.js'
$playwright=Join-Path $web 'node_modules/@playwright/test/cli.js'
$marker="clearing-acceptance-$runId"
$names=@('DB_URL','DB_USER','DB_PASSWORD','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE','SERVER_ADDRESS','SERVER_PORT','FUND_EXPORTS_DIRECTORY','VITE_API_TARGET','E2E_BASE_URL','E2E_API','E2E_CLEARING_TEST','PLAYWRIGHT_JSON_OUTPUT_NAME')
$previous=@{}
foreach ($name in $names) { $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
$backend=$null;$frontend=$null;$jar=$null;$clockBefore=$null;$clockRestoreFailed=$false
$startedAt=Get-Date
function Wait-Ready([string]$url) {
    for ($attempt=0;$attempt -lt 90;$attempt++) {
        try { if ((Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { return } } catch { }
        Start-Sleep -Seconds 1
    }
    throw "Acceptance service did not become ready: $url. Evidence: $runRoot"
}
try {
    $env:DB_URL=$settings.testJdbcUrl;$env:DB_USER=$settings.user;$env:DB_PASSWORD=$settings.password
    $env:SPRING_DATASOURCE_URL=$settings.testJdbcUrl;$env:SPRING_DATASOURCE_USERNAME=$settings.user;$env:SPRING_DATASOURCE_PASSWORD=$settings.password
    $env:SPRING_PROFILES_ACTIVE='local';$env:SERVER_ADDRESS='127.0.0.1';$env:SERVER_PORT='18880'
    $env:FUND_EXPORTS_DIRECTORY=Join-Path $runRoot 'ta-exports'
    $env:VITE_API_TARGET='http://127.0.0.1:18880'
    $env:E2E_BASE_URL='http://127.0.0.1:15173';$env:E2E_API='1';$env:E2E_CLEARING_TEST=$testDatabase
    $env:PLAYWRIGHT_JSON_OUTPUT_NAME=Join-Path $runRoot 'playwright-results.json'
    if (-not $SkipBuild) {
        & mvn.cmd '-f' (Join-Path $server 'pom.xml') '-DskipTests' 'package' *> (Join-Path $runRoot 'backend-build.log')
        if ($LASTEXITCODE -ne 0) { throw "Acceptance backend build failed; inspect $runRoot/backend-build.log" }
    }
    $jar=Get-ChildItem -LiteralPath (Join-Path $server 'target') -Filter '*.jar' | Where-Object Name -NotLike '*.original' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw 'No executable backend JAR is available.' }
    if (-not (Test-Path -LiteralPath $viteScript) -or -not (Test-Path -LiteralPath $playwright)) { throw 'Frontend dependencies are missing; install web dependencies before running acceptance.' }
    $backendArguments=@('-Xms256m','-Xmx512m','-XX:+UnlockExperimentalVMOptions','-XX:G1MaxNewSizePercent=10',"-Dclearing.acceptance.marker=$marker",'-jar',"`"$($jar.FullName)`"",'--server.port=18880','--fund.scheduler.enabled=false')
    $backend=Start-Process -FilePath $java -ArgumentList $backendArguments -WorkingDirectory $server -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runRoot 'backend-stdout.log') -RedirectStandardError (Join-Path $runRoot 'backend-stderr.log') -PassThru
    $frontend=Start-Process -FilePath $node -ArgumentList @("`"$viteScript`"",'--host','127.0.0.1','--port','15173','--strictPort') -WorkingDirectory $web -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runRoot 'frontend-stdout.log') -RedirectStandardError (Join-Path $runRoot 'frontend-stderr.log') -PassThru
    @{database=$testDatabase;mode=$Mode;backendPort=18880;frontendPort=15173;backendPid=$backend.Id;frontendPid=$frontend.Id;marker=$marker;backendArguments=$backendArguments;startedAt=$startedAt.ToString('o')} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runRoot 'runtime-manifest.json')
    Wait-Ready 'http://127.0.0.1:18880/actuator/health';Wait-Ready 'http://127.0.0.1:15173'
    $capturedClock=(& $mysql "--defaults-file=$mysqlDefaults" "--database=$testDatabase" '--batch' '--skip-column-names' "--execute=SELECT value FROM app_settings WHERE name='demo_clock';" | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $capturedClock -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?$') { throw 'Test business clock could not be safely captured.' }
    $clockBefore=$capturedClock
    $clockBefore | Set-Content -LiteralPath (Join-Path $runRoot 'business-clock-before.txt')
    Push-Location $web
    try {
        $browserArguments=@('test','e2e/live-workflows.spec.ts','--workers=1','--reporter=line,json','--output',(Join-Path $runRoot 'browser'))
        if ($Mode -eq 'upgrade') { $browserArguments+=@('--grep','双卡编号、真实重清与阻断') }
        & $node $playwright @browserArguments *> (Join-Path $runRoot 'browser-run.log')
        $browserExit=$LASTEXITCODE
    } finally { Pop-Location }
    if ($browserExit -ne 0) { throw "Real browser acceptance failed; inspect $runRoot/browser-run.log" }
} finally {
    try {
        if ($clockBefore) {
            & $mysql "--defaults-file=$mysqlDefaults" "--database=$testDatabase" "--execute=UPDATE app_settings SET value='$clockBefore' WHERE name='demo_clock';" *> (Join-Path $runRoot 'clock-restore.log')
            if ($LASTEXITCODE -ne 0) { $clockRestoreFailed=$true }
        }
        # Oracle's java launcher can exit while its child JVM continues. Require this run's marker,
        # JAR path and port before stopping either launcher or child; never use a broad java/node kill.
        $owned=Get-CimInstance Win32_Process | Where-Object {
            $_.CommandLine -and $_.CreationDate -ge $startedAt -and (
                ($jar -and $_.CommandLine.Contains($marker) -and $_.CommandLine.Contains($jar.FullName) -and $_.CommandLine.Contains('--server.port=18880')) -or
                ($frontend -and $_.ProcessId -eq $frontend.Id -and $_.CommandLine.Contains($viteScript) -and $_.CommandLine.Contains('--port 15173'))
            )
        }
        foreach ($process in $owned) { Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue }
    } finally {
        foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    }
    if ($clockRestoreFailed) { throw "Test business clock restore failed; inspect $runRoot/clock-restore.log" }
}
Write-Output "Real browser acceptance passed. Evidence: $runRoot"

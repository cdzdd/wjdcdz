param([ValidateSet('quick','full')][string]$Profile='quick')
$ErrorActionPreference='Stop'
$workspace=Split-Path $PSScriptRoot -Parent
$settings=Get-Content -LiteralPath (Join-Path $workspace '.runtime/local-env.json') -Raw | ConvertFrom-Json
$env:DB_TEST_URL=$settings.testJdbcUrl
$env:DB_USER=$settings.user
$env:DB_PASSWORD=$settings.password
if ($env:DB_TEST_URL -notmatch '/(fund_sim_test|fund_sim_upgrade_test)\?') { throw 'Export benchmark requires an isolated test database.' }
$log=Join-Path $workspace ".runtime/application-export-performance-$Profile.log"
Push-Location (Join-Path $workspace 'server')
try {
    & mvn.cmd '-Dtest=ApplicationExportPerformanceTest' "-Dexport.performance=$Profile" '-DargLine=-Xms256m -Xmx512m -XX:+UnlockExperimentalVMOptions -XX:G1MaxNewSizePercent=10' test *> $log
    if ($LASTEXITCODE -ne 0) { throw "Export benchmark failed; inspect $log" }
    Get-Content -LiteralPath $log | Where-Object {$_ -match '^EXPORT_PERF'}
} finally { Pop-Location }

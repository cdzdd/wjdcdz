param([ValidateSet('seed','smoke','quick','full')][string]$Profile='seed')
$ErrorActionPreference='Stop'
$workspace=Split-Path $PSScriptRoot -Parent
$settings=Get-Content -LiteralPath (Join-Path $workspace '.runtime/local-env.json') -Raw | ConvertFrom-Json
if ($settings.testJdbcUrl -notmatch '^jdbc:mysql://[^/]+/(fund_sim_test|fund_sim_upgrade_test)\?') { throw 'Performance fixtures require an approved isolated test database.' }
$previous=@{}
foreach ($name in @('DB_TEST_URL','DB_USER','DB_PASSWORD','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD')) { $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
$env:DB_TEST_URL=$settings.testJdbcUrl
$env:DB_USER=$settings.user
$env:DB_PASSWORD=$settings.password
$env:SPRING_DATASOURCE_URL=$settings.testJdbcUrl
$env:SPRING_DATASOURCE_USERNAME=$settings.user
$env:SPRING_DATASOURCE_PASSWORD=$settings.password
$log=Join-Path $workspace ".runtime/clearing-performance-$Profile.log"
$result=Join-Path $workspace ".runtime/clearing-performance-$Profile.json"
try {
    & mvn.cmd '-f' (Join-Path $workspace 'server/pom.xml') '-Dtest=ClearingPerformanceTest' "-Dclearing.performance=$Profile" "-Dclearing.performance.output=$result" '-DargLine=-Xms256m -Xmx512m -XX:+UnlockExperimentalVMOptions -XX:G1MaxNewSizePercent=10' test *> $log
    if ($LASTEXITCODE -ne 0) { throw "Performance fixture failed; inspect $log" }
    Get-Content -LiteralPath $log | Where-Object { $_ -match '^CLEARING_PERF' }
    Write-Output "Evidence: $result"
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}

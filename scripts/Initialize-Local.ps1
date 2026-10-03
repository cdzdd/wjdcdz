$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$runtime = Join-Path $workspace '.runtime'
$envFile = Join-Path $runtime 'local-env.json'
if (Test-Path $envFile) { Write-Output 'Local database configuration already exists.'; return }
New-Item -ItemType Directory -Force -Path $runtime,(Join-Path $runtime 'logs'),(Join-Path $runtime 'downloads') | Out-Null
$version = '8.4.11'
$base = Join-Path $runtime "mysql-$version-winx64"
$archive = Join-Path $runtime "downloads\mysql-$version-winx64.zip"
if (-not (Test-Path (Join-Path $base 'bin\mysqld.exe'))) {
    if (-not (Test-Path $archive)) {
        Write-Output "Downloading official portable MySQL $version..."
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest "https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-$version-winx64.zip" -OutFile $archive
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $runtime -Force
}
$data = Join-Path $runtime 'mysql-data'
if (Test-Path $data) { throw 'An existing data directory was found without local-env.json. Preserve it and restore the configuration; do not reinitialize.' }
$portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,13306)
try { $portProbe.Start() } catch { throw 'Port 13306 is occupied; no existing database will be changed.' } finally { $portProbe.Stop() }
$ini = Join-Path $runtime 'mysql.ini'
$logs = Join-Path $runtime 'logs'
$iniText = "[mysqld]`nbasedir=$($base.Replace('\','/'))`ndatadir=$($data.Replace('\','/'))`nport=13306`nbind-address=127.0.0.1`nmysqlx=0`ncharacter-set-server=utf8mb4`ncollation-server=utf8mb4_0900_ai_ci`ninnodb-buffer-pool-size=128M`nlog-error=$($logs.Replace('\','/'))/mysql-error.log`npid-file=$($runtime.Replace('\','/'))/mysql.pid`n"
[IO.File]::WriteAllText($ini,$iniText,[Text.UTF8Encoding]::new($false))
$mysqld = Join-Path $base 'bin\mysqld.exe'
& $mysqld "--defaults-file=$ini" --initialize-insecure
if ($LASTEXITCODE -ne 0) { throw 'MySQL initialization failed. Check .runtime/logs/mysql-error.log; installed Visual C++ runtime is required.' }
$process = Start-Process $mysqld -ArgumentList "`"--defaults-file=$ini`"" -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'mysql-stdout.log') -RedirectStandardError (Join-Path $logs 'mysql-stderr.log') -PassThru
$process.Id | Set-Content -LiteralPath (Join-Path $runtime 'mysql-process.pid')
$mysql = Join-Path $base 'bin\mysql.exe'
$ready = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    & $mysql --protocol=tcp --host=127.0.0.1 --port=13306 --user=root --skip-password --execute='SELECT 1' *> $null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'Portable MySQL did not become ready. Check .runtime/logs.' }
$bytes = New-Object byte[] 32
$generator = [Security.Cryptography.RandomNumberGenerator]::Create()
$generator.GetBytes($bytes); $appPassword = [Convert]::ToBase64String($bytes)
$generator.GetBytes($bytes); $rootPassword = [Convert]::ToBase64String($bytes)
$generator.Dispose()
# Credentials go through stdin/config files, never command arguments or console output.
$sql = "CREATE DATABASE fund_sim CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE DATABASE fund_sim_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER 'fund_app'@'127.0.0.1' IDENTIFIED BY '$appPassword'; GRANT ALL PRIVILEGES ON fund_sim.* TO 'fund_app'@'127.0.0.1'; GRANT ALL PRIVILEGES ON fund_sim_test.* TO 'fund_app'@'127.0.0.1'; ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword';"
$sql | & $mysql --protocol=tcp --host=127.0.0.1 --port=13306 --user=root --skip-password --batch
if ($LASTEXITCODE -ne 0) { throw 'Database provisioning failed. Preserve .runtime and inspect the error before retrying.' }
$jdbcOptions = '?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false'
$config = [ordered]@{database='fund_sim';testDatabase='fund_sim_test';user='fund_app';password=$appPassword;port=13306;host='127.0.0.1';jdbcUrl="jdbc:mysql://127.0.0.1:13306/fund_sim$jdbcOptions";testJdbcUrl="jdbc:mysql://127.0.0.1:13306/fund_sim_test$jdbcOptions";mysqlHome=$base}
[IO.File]::WriteAllText($envFile,($config | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
foreach ($account in @(@{name='app';user='fund_app';password=$appPassword},@{name='root';user='root';password=$rootPassword})) {
    $text = "[client]`nuser=$($account.user)`npassword=$($account.password)`nhost=127.0.0.1`nport=13306`nprotocol=tcp`n"
    [IO.File]::WriteAllText((Join-Path $runtime "mysql-$($account.name).cnf"),$text,[Text.UTF8Encoding]::new($false))
}
Write-Output 'Portable MySQL ready at 127.0.0.1:13306. Credentials saved in .runtime/local-env.json.'

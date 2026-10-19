# Fresh isolated PostgreSQL cluster; leaves data available for inspection, never deletes existing databases.
param([string]$LocalEnvironment=(Join-Path $PSScriptRoot 'local-env.ps1'),[string]$PostgresBin='')
$ErrorActionPreference='Stop'
$cleanProjectRoot=Split-Path -Parent $PSScriptRoot
. $LocalEnvironment
$bin=if($PostgresBin){$PostgresBin}else{Join-Path $cleanProjectRoot '.tools/postgres/pgsql/bin'}
New-Item -ItemType Directory -Force (Join-Path $cleanProjectRoot '.local') | Out-Null
$dir=Join-Path $cleanProjectRoot ('.local/clean-pg-'+[guid]::NewGuid().ToString('N'))
$pw=Join-Path $cleanProjectRoot ('.local/clean-init-'+[guid]::NewGuid().ToString('N'))
$port=55433
if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw 'Clean test port 55433 is in use'}
[IO.File]::WriteAllText($pw,$env:MIGRATION_PASSWORD)
$started=$false
try{
 & "$bin/initdb.exe" -D $dir -U traderecon_migration "--pwfile=$pw" --auth=scram-sha-256 --encoding=UTF8 --locale=C
 if($LASTEXITCODE -ne 0){throw 'Fresh initdb failed'}
 $launch=Start-Process -FilePath "$bin/pg_ctl.exe" -ArgumentList @('-D',$dir,'-l',"$dir/startup.log",'-o',"`"-h 127.0.0.1 -p $port`"",'-w','start') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$dir/launcher.log" -RedirectStandardError "$dir/launcher.err"
 $launch.WaitForExit()
 if($launch.ExitCode -ne 0){throw 'Fresh database start failed'}
 $started=$true
 $env:PGPASSWORD=$env:MIGRATION_PASSWORD
 if($env:DB_PASSWORD -notmatch '^[A-Za-z0-9-]+$'){throw 'This native helper expects generated local password characters'}
 & "$bin/psql.exe" -h 127.0.0.1 -p $port -U traderecon_migration -d postgres -v ON_ERROR_STOP=1 -c "CREATE ROLE traderecon_app LOGIN PASSWORD '$env:DB_PASSWORD'"
 if($LASTEXITCODE -ne 0){throw 'Runtime role creation failed'}
 & "$bin/createdb.exe" -h 127.0.0.1 -p $port -U traderecon_migration traderecon_test
 if($LASTEXITCODE -ne 0){throw 'Test database creation failed'}
 $env:TEST_DB_URL="jdbc:postgresql://127.0.0.1:$port/traderecon_test"
 $env:AI_MODE='OFF';$env:AI_LIVE_APPROVED='false'
 & (Join-Path $cleanProjectRoot 'mvnw.cmd') -B -ntp verify
 if($LASTEXITCODE -ne 0){throw 'Fresh-cluster verification failed'}
 Write-Host "PASS fresh native cluster, all migrations and verification; retained at $dir"
}finally{
 Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
 if(Test-Path -LiteralPath $pw){Remove-Item -LiteralPath $pw}
 if($started){& "$bin/pg_ctl.exe" -D $dir -m fast -w stop}
}


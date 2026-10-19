# Local Windows fallback when Docker is unavailable. Does not install services or edit PATH globally.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localDir = Join-Path $projectRoot '.local'
$bin = Join-Path $projectRoot '.tools\postgres\pgsql\bin'
New-Item -ItemType Directory -Force $localDir | Out-Null
$settingsPath = Join-Path $localDir 'settings.json'
if (-not (Test-Path $settingsPath)) {
  $settings = @{
    migrationPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    appPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    demoPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(12))
  }
  $settings | ConvertTo-Json | Set-Content $settingsPath
}
. (Join-Path $PSScriptRoot 'local-env.ps1')
$dataDir = Join-Path $localDir 'pgdata'
if (-not (Test-Path (Join-Path $dataDir 'PG_VERSION'))) {
  $passwordFile = Join-Path $localDir 'init-password'
  [IO.File]::WriteAllText($passwordFile, $env:MIGRATION_PASSWORD)
  try {
    & "$bin\initdb.exe" -D $dataDir -U traderecon_migration --pwfile=$passwordFile --auth=scram-sha-256 --encoding=UTF8 --locale=C
    if ($LASTEXITCODE -ne 0) { throw 'initdb failed' }
  } finally { Remove-Item -LiteralPath $passwordFile }
}
& "$bin\pg_ctl.exe" -D $dataDir status *> $null
if ($LASTEXITCODE -ne 0) {
  & "$bin\pg_ctl.exe" -D $dataDir -l (Join-Path $localDir 'postgres.log') -o '-h 127.0.0.1 -p 55432' -w start
  if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL startup failed' }
}
$env:PGPASSWORD = $env:MIGRATION_PASSWORD
try {
  $role = & "$bin\psql.exe" -h 127.0.0.1 -p 55432 -U traderecon_migration -d postgres -Atc "SELECT 1 FROM pg_roles WHERE rolname='traderecon_app'"
  if ($role -ne '1') {
    # Password generated above is hex-only, not user input.
    & "$bin\psql.exe" -h 127.0.0.1 -p 55432 -U traderecon_migration -d postgres -v ON_ERROR_STOP=1 -c "CREATE ROLE traderecon_app LOGIN PASSWORD '$env:DB_PASSWORD'"
    if ($LASTEXITCODE -ne 0) { throw 'Runtime role creation failed' }
  }
  foreach ($database in @('traderecon','traderecon_test')) {
    $exists = & "$bin\psql.exe" -h 127.0.0.1 -p 55432 -U traderecon_migration -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname='$database'"
    if ($exists -ne '1') {
      & "$bin\createdb.exe" -h 127.0.0.1 -p 55432 -U traderecon_migration $database
      if ($LASTEXITCODE -ne 0) { throw 'Database creation failed' }
    }
  }
} finally { Remove-Item Env:PGPASSWORD }
Write-Host 'Local PostgreSQL ready on 127.0.0.1:55432; settings are in ignored .local/settings.json.'


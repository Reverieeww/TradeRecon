$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'bootstrap-local.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$bin = Join-Path $projectRoot '.tools\postgres\pgsql\bin'
$report = [Collections.Generic.List[string]]::new()
function Start-TestApp {
  param([string]$label)
  Copy-Item (Join-Path $projectRoot 'target/trade-recon-0.1.0-SNAPSHOT.jar') (Join-Path $projectRoot ".local\runtime-$label.jar")
  $process = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList @('-jar',".local/runtime-$label.jar") -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $projectRoot ".local\app-$label.log") -RedirectStandardError (Join-Path $projectRoot ".local\app-$label.err")
  for ($i=0; $i -lt 40; $i++) {
    if ($process.HasExited) { throw "Application exited; check .local/app-$label.log" }
    try {
      $health = Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health'
      if ($health.status -eq 'UP') { return $process }
    } catch { }
    Start-Sleep -Seconds 1
  }
  Stop-Process -Id $process.Id
  throw 'Application health check timed out'
}
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { throw 'Port 8080 is occupied; do not stop an unknown process.' }
$first = Start-TestApp 'first'
$report.Add('PASS: packaged application starts and real database health is UP.')
$env:PGPASSWORD = $env:MIGRATION_PASSWORD
try {
  $probeId = [guid]::NewGuid().ToString()
  & "$bin\psql.exe" -h 127.0.0.1 -p 55432 -U traderecon_migration -d traderecon -v ON_ERROR_STOP=1 -c "INSERT INTO dataset(id,name,kind) VALUES ('$probeId','restart-$probeId','DEMO')"
  if ($LASTEXITCODE -ne 0) { throw 'Could not write restart marker' }
  Stop-Process -Id $first.Id
  $first.WaitForExit()
  & "$bin\pg_ctl.exe" -D (Join-Path $projectRoot '.local\pgdata') -m fast -w restart -o '-h 127.0.0.1 -p 55432'
  if ($LASTEXITCODE -ne 0) { throw 'Database restart failed' }
  $second = Start-TestApp 'second'
  $value = & "$bin\psql.exe" -h 127.0.0.1 -p 55432 -U traderecon_migration -d traderecon -Atc "SELECT name FROM dataset WHERE id='$probeId'"
  if ($value -ne "restart-$probeId") { throw 'Committed marker did not survive restart' }
  $report.Add('PASS: committed dataset survives application AND PostgreSQL restart.')
  $report.Add('PASS: Flyway validation succeeds on restart without manual table creation.')
  $report.Add('Application remains running at http://127.0.0.1:8080; PID saved in .local/app.pid.')
  $second.Id | Set-Content (Join-Path $projectRoot '.local\app.pid')
} finally { Remove-Item Env:PGPASSWORD }
$report | Set-Content (Join-Path $projectRoot 'evidence\day1-restart-native.txt')
$report

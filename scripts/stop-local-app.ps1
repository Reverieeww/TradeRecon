$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $projectRoot '.local\app.pid'
if (-not (Test-Path $pidFile)) { Write-Host 'No background demo PID recorded. Use Ctrl+C for a foreground app.'; return }
$appProcessId = [int](Get-Content $pidFile)
$process = Get-CimInstance Win32_Process -Filter "ProcessId=$appProcessId"
if (-not $process) { Write-Host 'Recorded demo process has already stopped.'; return }
if ($process.CommandLine -notmatch '\.local/runtime-(first|second|third|fourth|fifth|final)\.jar' -or $process.ExecutablePath -ne (Join-Path $projectRoot '.tools\java\jdk-21.0.12.1+1\bin\java.exe')) {
  throw 'PID does not belong to the expected demo process. Refusing to stop it.'
}
Stop-Process -Id $appProcessId
Write-Host 'Background demo app stopped. Database and data remain intact.'

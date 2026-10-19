$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-env.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$base = 'http://127.0.0.1:8080'
# New synthetic smoke dataset each time; do not alter the learner's existing demo history.
$dataset = [guid]::NewGuid().ToString()
$env:PGPASSWORD = $env:DB_PASSWORD
try {
  & (Join-Path $projectRoot '.tools/postgres/pgsql/bin/psql.exe') -h 127.0.0.1 -p 55432 -U traderecon_app -d traderecon -v ON_ERROR_STOP=1 -c "INSERT INTO dataset(id,name,kind) VALUES ('$dataset','day3-http-$dataset','DEMO')" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated synthetic dataset' }
} finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
$login = Invoke-WebRequest "$base/login" -SessionVariable session
$match = [regex]::Match($login.Content, 'name="_csrf"[^>]*value="([^"]+)"')
if (-not $match.Success) { throw 'Login CSRF missing' }
$null = Invoke-WebRequest "$base/login" -Method Post -WebSession $session -Body @{username='analyst';password=$env:DEMO_PASSWORD;_csrf=$match.Groups[1].Value}
$token = Invoke-RestMethod "$base/api/csrf" -WebSession $session
$headers = @{}; $headers[$token.headerName] = $token.token
$report = [Collections.Generic.List[string]]::new()
$report.Add("Synthetic HTTP test dataset: $dataset")
function Upload-Day3([string]$name,[string]$source) {
  $null = Invoke-RestMethod "$base/api/imports" -Method Post -WebSession $session -Headers $headers -Form @{
    dataset=$dataset;source=$source;business_date='2026-10-18';feed_complete='false';file=(Get-Item (Join-Path $projectRoot "data/day3/$name"))
  }
}
function Run-Day3 {
  $headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
  $r = Invoke-RestMethod "$base/api/reconciliation-runs" -Method Post -WebSession $session -Headers $headers -Body @{dataset=$dataset;business_date='2026-10-18'}
  for($attempt=0;$attempt -lt 100;$attempt++) {
    $result = Invoke-RestMethod "$base/api/reconciliation-runs/$($r.id)" -WebSession $session
    if($result.status -eq 'SUCCEEDED') { return $result }
    if($result.status -eq 'FAILED') { throw 'Background run failed' }
    Start-Sleep -Milliseconds 200
  }
  throw 'Background run did not complete within 20 seconds'
}
Upload-Day3 '01_internal.csv' 'INTERNAL'
Upload-Day3 '02_broker.csv' 'BROKER'
$first = Run-Day3
foreach ($status in @('MATCHED','BREAK','WAITING','DATA_BLOCKED')) {
  if ($first.counts.$status -ne 1) { throw "Expected one $status, got $($first.counts | ConvertTo-Json -Compress)" }
}
$report.Add("PASS four statuses, total 4: $($first.id)")
$casesBefore = Invoke-RestMethod "$base/api/cases?dataset=$dataset" -WebSession $session
if ($casesBefore.Count -ne 2) { throw 'Expected two initial cases' }
$repeat = Run-Day3
$casesRepeated = Invoke-RestMethod "$base/api/cases?dataset=$dataset" -WebSession $session
if ((($casesBefore.id | Sort-Object) -join ',') -ne (($casesRepeated.id | Sort-Object) -join ',')) { throw 'Case identities changed on rerun' }
$report.Add('PASS identical rerun keeps two stable cases')
Upload-Day3 '03_broker_fix.csv' 'BROKER'
Upload-Day3 '04_internal_fill_gap.csv' 'INTERNAL'
$fixed = Run-Day3
if ($fixed.counts.MATCHED -ne 4 -or $fixed.total -ne 4 -or $fixed.completeness -ne 'PROVISIONAL') { throw 'Repair/completeness result incorrect' }
$resolved = Invoke-RestMethod "$base/api/cases?dataset=$dataset" -WebSession $session
if ($resolved.Count -ne 2 -or @($resolved | Where-Object review_state -ne 'RESOLVED_BY_DATA').Count -ne 0) { throw 'Cases not resolved by data' }
$old = Invoke-RestMethod "$base/api/reconciliation-runs/$($first.id)" -WebSession $session
if ($old.counts.BREAK -ne 1 -or $old.counts.DATA_BLOCKED -ne 1) { throw 'Historical result changed' }
$report.Add("PASS four matches and two resolved cases; original run remains unchanged: $($fixed.id)")
$runPage = Invoke-WebRequest "$base/runs?id=$($fixed.id)" -WebSession $session
$casePage = Invoke-WebRequest "$base/cases/$($resolved[0].id)" -WebSession $session
if ($runPage.Content -notmatch 'PROVISIONAL' -or $casePage.Content -notmatch 'RESOLVED_BY_DATA') { throw 'HTML rendering failed' }
$report.Add('PASS authenticated run and case HTML pages render real data')
$report | Set-Content (Join-Path $projectRoot 'evidence/day3-http-smoke.txt')
$report

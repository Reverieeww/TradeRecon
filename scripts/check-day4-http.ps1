$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'local-env.ps1')
$projectRoot=Split-Path -Parent $PSScriptRoot
$base='http://127.0.0.1:8080'
$dataset=[guid]::NewGuid().ToString()
$env:PGPASSWORD=$env:DB_PASSWORD
try {
  & (Join-Path $projectRoot '.tools/postgres/pgsql/bin/psql.exe') -h 127.0.0.1 -p 55432 -U traderecon_app -d traderecon -v ON_ERROR_STOP=1 -c "INSERT INTO dataset(id,name,kind) VALUES ('$dataset','day4-http-$dataset','DEMO')" | Out-Null
  if($LASTEXITCODE -ne 0){throw 'Could not create isolated dataset'}
} finally {Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
function Login-Demo([string]$user) {
  $page=Invoke-WebRequest "$base/login" -SessionVariable s
  $csrf=[regex]::Match($page.Content,'name="_csrf"[^>]*value="([^"]+)"').Groups[1].Value
  $null=Invoke-WebRequest "$base/login" -Method Post -WebSession $s -Body @{username=$user;password=$env:DEMO_PASSWORD;_csrf=$csrf}
  $token=Invoke-RestMethod "$base/api/csrf" -WebSession $s
  $h=@{};$h[$token.headerName]=$token.token
  return @{session=$s;headers=$h}
}
$analyst=Login-Demo 'analyst';$supervisor=Login-Demo 'supervisor';$viewer=Login-Demo 'viewer'
$report=[Collections.Generic.List[string]]::new();$report.Add("Synthetic dataset: $dataset")
function Post-Check($login,[string]$path,$body,[int]$expected) {
  $r=Invoke-RestMethod "$base$path" -Method Post -WebSession $login.session -Headers $login.headers -Body $body -SkipHttpErrorCheck -StatusCodeVariable code
  if($code -ne $expected){throw "Expected $expected at $path; observed $code"};return $r
}
function Upload-Synthetic([string]$file,[string]$source) {
  $null=Invoke-RestMethod "$base/api/imports" -Method Post -WebSession $analyst.session -Headers $analyst.headers -Form @{dataset=$dataset;source=$source;business_date='2026-10-18';feed_complete='false';file=(Get-Item (Join-Path $projectRoot "data/day3/$file"))}
}
function Wait-Run([string]$id) {
  for($i=0;$i -lt 100;$i++) {
    $r=Invoke-RestMethod "$base/api/reconciliation-runs/$id" -WebSession $analyst.session
    if($r.status -eq 'SUCCEEDED'){return $r};if($r.status -eq 'FAILED'){throw 'Run failed'}
    Start-Sleep -Milliseconds 200
  };throw 'Worker timeout'
}
function Proof($case) {return @{expectedVersion=[string]$case.version;resultId=[string]$case.current_result_id;evidenceHash=[string]$case.evidence_hash;reason='Reviewed synthetic test evidence'}}
Upload-Synthetic '01_internal.csv' 'INTERNAL';Upload-Synthetic '02_broker.csv' 'BROKER'
$analyst.headers['Idempotency-Key']=[guid]::NewGuid().ToString()
$body=@{dataset=$dataset;business_date='2026-10-18'}
$queued=Post-Check $analyst '/api/reconciliation-runs' $body 202
$duplicate=Post-Check $analyst '/api/reconciliation-runs' $body 202
if($queued.id -ne $duplicate.id){throw 'Duplicate request made a different run'}
$null=Post-Check $analyst '/api/reconciliation-runs' @{dataset=$dataset;business_date='2026-10-19'} 409
$first=Wait-Run $queued.id
$report.Add('PASS 202 queue, stable idempotency key, changed request 409, background completion')
$cases=Invoke-RestMethod "$base/api/cases?dataset=$dataset" -WebSession $analyst.session
$caseId=($cases | Where-Object execution_id -eq 'D3-BREAK').id
$case=Invoke-RestMethod "$base/api/cases/$caseId" -WebSession $analyst.session
$proof=Proof $case
$null=Post-Check $viewer "/api/cases/$caseId/claim" $proof 403
$null=Post-Check $analyst "/api/cases/$caseId/claim" $proof 200
$null=Post-Check $analyst "/api/cases/$caseId/claim" $proof 409
$case=Invoke-RestMethod "$base/api/cases/$caseId" -WebSession $analyst.session
$proof=Proof $case;$proof.action='ESCALATE';$null=Post-Check $analyst "/api/cases/$caseId/decisions" $proof 200
$case=Invoke-RestMethod "$base/api/cases/$caseId" -WebSession $analyst.session
$proof=Proof $case;$proof.action='CLOSE';$null=Post-Check $analyst "/api/cases/$caseId/decisions" $proof 403
$null=Post-Check $supervisor "/api/cases/$caseId/decisions" $proof 200
$closed=Invoke-RestMethod "$base/api/cases/$caseId" -WebSession $supervisor.session
if($closed.review_state -ne 'CLOSED_WITH_REASON' -or $closed.status -ne 'BREAK'){throw 'Manual closure incorrectly changed data status'}
$report.Add('PASS viewer blocked, claim, stale version 409, escalation, supervisor-only closure; data remains BREAK')
$analyst.headers['Idempotency-Key']=[guid]::NewGuid().ToString();$repeat=Post-Check $analyst '/api/reconciliation-runs' $body 202;$null=Wait-Run $repeat.id
$closedAgain=Invoke-RestMethod "$base/api/cases/$caseId" -WebSession $analyst.session
if($closedAgain.review_state -ne 'CLOSED_WITH_REASON'){throw 'Same evidence reopened a closed case'}
$null=Post-Check $analyst "/api/reconciliation-runs/$($repeat.id)/retry" @{} 202
Upload-Synthetic '03_broker_fix.csv' 'BROKER';Upload-Synthetic '04_internal_fill_gap.csv' 'INTERNAL'
$analyst.headers['Idempotency-Key']=[guid]::NewGuid().ToString();$fixed=Post-Check $analyst '/api/reconciliation-runs' $body 202;$fixedResult=Wait-Run $fixed.id
$resolved=Invoke-RestMethod "$base/api/cases/$caseId" -WebSession $analyst.session
if($fixedResult.counts.MATCHED -ne 4 -or $resolved.review_state -ne 'RESOLVED_BY_DATA'){throw 'Correction did not resolve cases'}
$old=Invoke-RestMethod "$base/api/reconciliation-runs/$($first.id)" -WebSession $analyst.session
if($old.counts.BREAK -ne 1){throw 'Old snapshot changed'}
$report.Add('PASS unchanged evidence preserves manual closure; corrected data resolves case; old run unchanged')
$page=Invoke-WebRequest "$base/cases/$caseId" -WebSession $supervisor.session
if($page.Content -notmatch 'MANUAL_CLOSE' -or $page.Content -notmatch 'expectedVersion'){throw 'Case page did not render audit or version contract'}
$report.Add('PASS case page renders manual audit and versioned review form')
$report | Set-Content (Join-Path $projectRoot 'evidence/day4-http-smoke.txt')
$report

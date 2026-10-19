param([string]$BaseUrl='http://127.0.0.1:8080',[ValidateSet('OFF','MOCK')][string]$Mode='OFF')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'local-env.ps1')
$projectRoot=Split-Path -Parent $PSScriptRoot
$base=$BaseUrl
$dataset=[guid]::NewGuid().ToString()
function Login-Demo([string]$user) {
  $page=Invoke-WebRequest "$base/login" -SessionVariable s
  $csrf=[regex]::Match($page.Content,'name="_csrf"[^>]*value="([^"]+)"').Groups[1].Value
  $null=Invoke-WebRequest "$base/login" -Method Post -WebSession $s -Body @{username=$user;password=$env:DEMO_PASSWORD;_csrf=$csrf}
  $token=Invoke-RestMethod "$base/api/csrf" -WebSession $s
  $h=@{};$h[$token.headerName]=$token.token
  return @{session=$s;headers=$h}
}
$analyst=Login-Demo 'analyst';$supervisor=Login-Demo 'supervisor';$viewer=Login-Demo 'viewer'
$created=Invoke-WebRequest "$base/datasets" -Method Post -WebSession $analyst.session -Headers $analyst.headers -Body @{name="release-http-$dataset"}
$dataset=[regex]::Match($created.BaseResponse.RequestMessage.RequestUri.Query,'dataset=([0-9a-f-]+)').Groups[1].Value
if(-not $dataset){throw 'Dataset creation did not redirect to imports'}
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
$aiProof=@{resultId=[string]$case.current_result_id;evidenceHash=[string]$case.evidence_hash}
$null=Post-Check $viewer "/api/cases/$caseId/ai-analysis" $aiProof 403
$aiResult=Post-Check $analyst "/api/cases/$caseId/ai-analysis" $aiProof $(if($Mode -eq 'OFF'){200}else{202})
if($Mode -eq 'MOCK'){
 for($a=0;$a -lt 100;$a++){
  $analysis=Invoke-RestMethod "$base/api/ai-analyses/$($aiResult.id)" -WebSession $viewer.session
  if($analysis.status -eq 'SUCCEEDED'){break}
  if($analysis.status -eq 'FAILED'){throw 'MOCK analysis failed'}
  Start-Sleep -Milliseconds 200
 }
 if($analysis.status -ne 'SUCCEEDED' -or $analysis.mode -ne 'MOCK'){throw 'MOCK worker did not complete'}
 $cached=Post-Check $analyst "/api/cases/$caseId/ai-analysis" $aiProof 200
 if($cached.id -ne $aiResult.id){throw 'Cache miss for same evidence'}
}else{
 if($aiResult.status -ne 'FAILED'){throw 'OFF unexpectedly queued analysis'}
}
$aiPage=Invoke-WebRequest "$base/cases/$caseId" -WebSession $analyst.session
if($aiPage.Content -notmatch 'Read-only AI assistance'){throw 'AI panel missing'}
$report.Add("PASS AI $Mode mode, role permission, template availability and analysis page")
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
if($Mode -eq 'MOCK'){
 $stale=Invoke-RestMethod "$base/api/ai-analyses/$($aiResult.id)" -WebSession $viewer.session
 if($stale.status -ne 'STALE' -or $null -ne $stale.response_json){throw 'Old AI evidence remained visible'}
 $report.Add('PASS AI output marked STALE and hidden after corrected evidence')
}
$report.Add('PASS unchanged evidence preserves manual closure; corrected data resolves case; old run unchanged')
$page=Invoke-WebRequest "$base/cases/$caseId" -WebSession $supervisor.session
if($page.Content -notmatch 'MANUAL_CLOSE' -or $page.Content -notmatch 'expectedVersion'){throw 'Case page did not render audit or version contract'}
$report.Add('PASS case page renders manual audit and versioned review form')

foreach($path in @("/imports?dataset=$dataset","/runs?id=$($fixed.id)","/cases?dataset=$dataset","/cases/$caseId","/runbooks.html")) {
 $p=Invoke-WebRequest "$base$path" -WebSession $viewer.session
 if($p.StatusCode -ne 200 -or $p.Content -notmatch '<html'){throw "Page failed: $path"}
}
$investigation=Invoke-RestMethod "$base/api/cases/$caseId/investigation" -WebSession $viewer.session
if($investigation.ai -ne 'OFF' -or $investigation.report.mode -ne 'TEMPLATE'){throw 'Investigation must be template-only'}
$csv=Invoke-WebRequest "$base/api/reconciliation-runs/$($fixed.id)/export" -WebSession $viewer.session
$rows=@($csv.Content | ConvertFrom-Csv)
if($rows.Count -ne 4){throw 'CSV did not include every result'}
$bad=Invoke-WebRequest "$base/cases?review=INVALID" -WebSession $viewer.session -SkipHttpErrorCheck
if($bad.StatusCode -ne 400 -or $bad.Headers['Content-Type'] -notmatch 'text/html'){throw 'Invalid HTML filter error'}
$report.Add('PASS live import/run/cases/detail/runbook pages, bounded TEMPLATE investigation, CSV export and readable filter errors')
$report | Set-Content (Join-Path $projectRoot "evidence/day7-http-$Mode.txt")
$report

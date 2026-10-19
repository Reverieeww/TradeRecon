$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-env.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$base = 'http://127.0.0.1:8080'
$page = Invoke-WebRequest "$base/login" -SessionVariable session
$match = [regex]::Match($page.Content, 'name="_csrf"[^>]*value="([^"]+)"')
if (-not $match.Success) { throw 'CSRF token was not found on login page' }
$null = Invoke-WebRequest "$base/login" -Method Post -WebSession $session -Body @{username='analyst';password=$env:DEMO_PASSWORD;_csrf=$match.Groups[1].Value}
$token = Invoke-RestMethod "$base/api/csrf" -WebSession $session
$headers = @{}; $headers[$token.headerName] = $token.token
$report = [Collections.Generic.List[string]]::new()
function Upload-Sample {
  param([string]$filename,[int[]]$expected)
  $response = Invoke-WebRequest "$base/api/imports" -Method Post -WebSession $session -Headers $headers -SkipHttpErrorCheck -Form @{
    dataset='00000000-0000-0000-0000-000000000001';source='INTERNAL';business_date='2026-10-15';feed_complete='false';file=(Get-Item (Join-Path $projectRoot "data/day2/$filename"))
  }
  if ([int]$response.StatusCode -notin $expected) { throw "Unexpected HTTP $($response.StatusCode): $($response.Content)" }
  $report.Add("PASS: $filename HTTP $($response.StatusCode)")
}
Upload-Sample '01_new.csv' @(201,200)
Upload-Sample '01_new.csv' @(200)
Upload-Sample '02_correction.csv' @(201,200)
Upload-Sample '03_conflict.csv' @(409)
Upload-Sample '04_gap.csv' @(201,200)
$uri = "$base/api/executions/E-GAP?dataset=00000000-0000-0000-0000-000000000001&source=INTERNAL"
$before = Invoke-RestMethod $uri -WebSession $session
$report.Add("Observed E-GAP status before repair: $($before.projection.status) (ACTIVE if this demonstration was already run)")
Upload-Sample '05_fill_gap.csv' @(201,200)
$after = Invoke-RestMethod $uri -WebSession $session
if ($after.projection.status -ne 'ACTIVE' -or $after.projection.current.revision -ne 3) { throw 'Version repair failed' }
$report.Add('PASS: E-GAP projects revision 3 after gap repair.')
$ui = Invoke-WebRequest "$base/imports" -WebSession $session
if ($ui.Content -notmatch 'Batch history' -or $ui.Content -notmatch 'REJECTED') { throw 'Import history page did not render expected content' }
$report.Add('PASS: authenticated import page renders accepted and rejected batch history.')
$report | Set-Content (Join-Path $projectRoot 'evidence/day2-http-smoke.txt')
$report

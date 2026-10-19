. (Join-Path $PSScriptRoot 'bootstrap-local.ps1')
Set-Location (Split-Path -Parent $PSScriptRoot)
& .\mvnw.cmd -B -ntp verify '-Dit.test=ImportBenchmark'
exit $LASTEXITCODE

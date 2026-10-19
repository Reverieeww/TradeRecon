# Interactive, process-scoped configuration. No key is written to project files.
$ErrorActionPreference='Stop'
$env:AI_LIVE_APPROVED='false'
$mode=Read-Host 'Mode (OFF, MOCK, LIVE)'
if($mode -notin @('OFF','MOCK','LIVE')){throw 'Choose OFF, MOCK or LIVE'}
$env:AI_MODE=$mode
if($mode -ne 'LIVE'){$env:AI_LIVE_APPROVED='false';Write-Host "Mode: $mode";return}
$model=Read-Host 'Exact model ID supporting Chat Completions json_schema'
$budget=Read-Host 'Total conservative reservation budget in USD for this ledger (not RMB)'
$inputPrice=Read-Host 'Verified input price USD per million tokens'
$outputPrice=Read-Host 'Verified output price USD per million tokens'
$max=Read-Host 'Maximum analysis requests (each can make up to 2 attempts)'
foreach($amount in @($budget,$inputPrice,$outputPrice)){if($amount -notmatch '^\d+(\.\d+)?$' -or [decimal]$amount -le 0){throw 'Prices and budget must be positive decimal numbers'}}
if($max -notmatch '^\d+$' -or [int]$max -lt 1 -or [int]$max -gt 1000){throw 'Request cap must be 1-1000'}
if([string]::IsNullOrWhiteSpace($model)){throw 'Model is required'}
Write-Host 'Only synthetic evidence goes to OpenAI. UI and evaluation use separate cumulative reservation ledgers.'
Write-Host 'Set a provider-side spend limit too. Do not reset ledgers to bypass a budget. Prices must cover the chosen model.'
$approval=Read-Host 'Type APPROVE to allow paid requests with these settings'
if($approval -cne 'APPROVE'){$env:AI_MODE='OFF';throw 'LIVE not approved'}
$key=Read-Host 'API key (hidden; not saved to disk)' -AsSecureString
$ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($key)
try {$env:OPENAI_API_KEY=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)} finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}
$env:AI_MODEL=$model;$env:AI_BUDGET_USD=$budget;$env:AI_INPUT_USD_PER_MILLION=$inputPrice;$env:AI_OUTPUT_USD_PER_MILLION=$outputPrice;$env:AI_MAX_REQUESTS=$max;$env:AI_LIVE_APPROVED='true'
Write-Host 'Configured for this terminal only. Start the app from this same terminal; already-running apps do not inherit changes.'


$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $projectRoot '.tools'
$env:JAVA_HOME = (Get-ChildItem (Join-Path $toolsRoot 'java') -Directory | Select-Object -First 1).FullName
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$settings = Get-Content (Join-Path $projectRoot '.local\settings.json') -Raw | ConvertFrom-Json
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55432/traderecon'
$env:DB_USER = 'traderecon_app'
$env:DB_PASSWORD = $settings.appPassword
$env:MIGRATION_USER = 'traderecon_migration'
$env:MIGRATION_PASSWORD = $settings.migrationPassword
$env:DEMO_PASSWORD = $settings.demoPassword
$env:TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/traderecon_test'
$env:TEST_DB_USER = $env:MIGRATION_USER
$env:TEST_DB_PASSWORD = $env:MIGRATION_PASSWORD
$env:TEST_APP_PASSWORD = $env:DB_PASSWORD


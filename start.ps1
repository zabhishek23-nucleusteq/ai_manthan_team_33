# docker writes progress to stderr; with 'Stop' PowerShell 5.1 treats that as fatal. Exit codes are checked explicitly.
$ErrorActionPreference = 'Continue'
Set-Location $PSScriptRoot
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is required' }
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker is not running' }
if (-not (Test-Path .env)) { Copy-Item .env.example .env; Write-Host 'Created .env from .env.example' }
$cfg = @{}
Get-Content .env | Where-Object { $_ -match '^\s*[^#=\s]+=' } | ForEach-Object {
  $k, $v = $_ -split '=', 2; $cfg[$k.Trim()] = $v.Trim() }
function Get-Cfg($k, $d) { if ($cfg.ContainsKey($k)) { $cfg[$k] } else { $d } }
docker compose up --build -d
if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }
$bp = Get-Cfg 'BACKEND_PORT' '8080'; $fp = Get-Cfg 'FRONTEND_PORT' '3000'
Write-Host 'Waiting for backend health...'
for ($i = 0; $i -lt 90; $i++) {
  try {
    $r = Invoke-RestMethod "http://localhost:$bp/actuator/health" -TimeoutSec 3
    if ($r.status -eq 'UP') {
      Write-Host "Frontend:  http://localhost:$fp"
      Write-Host "Backend:   http://localhost:$bp  (health: /actuator/health, docs: /swagger-ui.html)"
      Write-Host "Mock bank: http://localhost:$(Get-Cfg 'MOCK_BANK_PORT' '8081')"
      Write-Host 'Demo logins: see README (Run section)'
      exit 0
    }
  } catch {}
  Start-Sleep -Seconds 2
}
Write-Host 'Backend did not become healthy; run: docker compose logs backend'; exit 1

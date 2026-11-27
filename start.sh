#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command -v docker >/dev/null || { echo "Docker is required"; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker is not running"; exit 1; }
[ -f .env ] || { cp .env.example .env; echo "Created .env from .env.example"; }
set -a; . ./.env; set +a
docker compose up --build -d
BP="${BACKEND_PORT:-8080}"; FP="${FRONTEND_PORT:-3000}"
echo "Waiting for backend health..."
for _ in $(seq 1 90); do
  if curl -fs "http://localhost:$BP/actuator/health" | grep -q '"UP"'; then
    echo "Frontend:  http://localhost:$FP"
    echo "Backend:   http://localhost:$BP  (health: /actuator/health, docs: /swagger-ui.html)"
    echo "Mock bank: http://localhost:${MOCK_BANK_PORT:-8081}"
    echo "Prometheus: http://localhost:${PROMETHEUS_PORT:-9090}"
    echo "Grafana:    http://localhost:${GRAFANA_PORT:-3001}  (anonymous viewer; admin login in .env)"
    echo "Demo logins: see README (Run section)"
    exit 0
  fi
  sleep 2
done
echo "Backend did not become healthy; run: docker compose logs backend"; exit 1

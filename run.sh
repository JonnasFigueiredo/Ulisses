#!/usr/bin/env bash
# Executa o Ulisses rapidamente. Funciona no Git Bash (Windows), Linux e macOS.
#
#   ./run.sh            builda e SOBE tudo via Docker, espera ficar pronto e abre o navegador
#   ./run.sh dev        modo desenvolvimento nativo com hot reload (backend + frontend)
#   ./run.sh build      apenas builda backend (jar) e frontend (dist), sem rodar
#   ./run.sh logs       acompanha os logs dos containers
#   ./run.sh stop       derruba os containers do Docker
set -euo pipefail
cd "$(dirname "$0")"

FRONT_URL="http://localhost:5173"
BACK_URL="http://localhost:8080"

info() { printf '\033[36m▶ %s\033[0m\n' "$*"; }
ok() { printf '\033[32m✔ %s\033[0m\n' "$*"; }
err() { printf '\033[31m✖ %s\033[0m\n' "$*" >&2; }

# Detecta um JDK 21 se JAVA_HOME nao estiver definido (locais comuns em Win/Linux/mac).
detect_java() {
  [ -n "${JAVA_HOME:-}" ] && { info "JAVA_HOME=$JAVA_HOME"; return 0; }
  for base in "E:/androidStudio/jdk" "/c/Program Files/Java" "/c/Program Files/Eclipse Adoptium" \
    "$HOME/.jdks" "/usr/lib/jvm" "/Library/Java/JavaVirtualMachines"; do
    for d in "$base"/*21*; do
      [ -x "$d/bin/java" ] && { export JAVA_HOME="$d"; info "JAVA_HOME detectado: $JAVA_HOME"; return 0; }
      [ -x "$d/Contents/Home/bin/java" ] && {
        export JAVA_HOME="$d/Contents/Home"; info "JAVA_HOME detectado: $JAVA_HOME"; return 0;
      }
    done
  done
  info "JAVA_HOME nao detectado; usando o 'java' do PATH (precisa ser 21+)."
}

open_browser() {
  local url="$1"
  if command -v xdg-open >/dev/null 2>&1; then xdg-open "$url" >/dev/null 2>&1 &
  elif command -v open >/dev/null 2>&1; then open "$url" >/dev/null 2>&1 &
  else start "" "$url" >/dev/null 2>&1 || cmd //c start "" "$url" >/dev/null 2>&1 || true
  fi
}

# Espera uma URL responder (qualquer HTTP = pronto). $2 = tentativas (padrao 150 = ~5 min).
wait_ready() {
  local url="$1" tries="${2:-150}"
  info "Aguardando ficar pronto: $url"
  for _ in $(seq 1 "$tries"); do
    curl -s -o /dev/null --max-time 3 "$url" 2>/dev/null && return 0
    sleep 2
  done
  return 1
}

mode="${1:-docker}"
case "$mode" in
docker | up)
  command -v docker >/dev/null 2>&1 || { err "Docker nao encontrado. Use: ./run.sh dev"; exit 1; }
  [ -f .env ] || { cp .env.example .env; info ".env criado a partir de .env.example — troque os segredos em producao."; }
  info "Buildando e subindo o Ulisses via Docker (o primeiro start baixa deps + modelos ~185MB)..."
  docker compose up --build -d
  if wait_ready "$FRONT_URL"; then
    ok "Ambiente no ar!  Frontend: $FRONT_URL   Backend: $BACK_URL"
    info "Abrindo o navegador... (no 1o start os modelos ainda baixam por alguns minutos)"
    open_browser "$FRONT_URL"
  else
    err "Nao respondeu a tempo. Veja os logs: ./run.sh logs"
    exit 1
  fi
  info "Logs: ./run.sh logs   |   Parar: ./run.sh stop"
  ;;
dev)
  detect_java
  command -v node >/dev/null 2>&1 || { err "Node.js nao encontrado."; exit 1; }
  info "Instalando dependencias do frontend (se necessario)..."
  (cd frontend && npm install)
  info "Subindo backend (:8080) e frontend (:5173). Ctrl+C encerra os dois."
  (cd backend && ./mvnw -q spring-boot:run) & back=$!
  (cd frontend && npm run dev) & front=$!
  trap 'err "Encerrando..."; kill "$back" "$front" 2>/dev/null || true' INT TERM
  # abre o navegador quando o frontend estiver pronto (em segundo plano)
  (wait_ready "$FRONT_URL" && ok "Ambiente no ar: $FRONT_URL" && open_browser "$FRONT_URL") &
  wait "$back" "$front"
  ;;
build)
  detect_java
  info "Buildando backend (jar)..."
  (cd backend && ./mvnw -q -DskipTests package)
  info "Buildando frontend (dist)..."
  (cd frontend && npm install && npm run build)
  ok "Pronto: backend/target/*.jar e frontend/dist/"
  ;;
logs)
  docker compose logs -f
  ;;
stop | down)
  docker compose down
  ok "Containers encerrados."
  ;;
*)
  cat <<'EOF'
Uso: ./run.sh [comando]

  (vazio) | docker   Builda, sobe tudo via Docker, espera pronto e abre o navegador. Recomendado.
  dev                Desenvolvimento nativo com hot reload (backend + frontend).
  build              Builda backend (jar) e frontend (dist), sem rodar.
  logs               Acompanha os logs dos containers.
  stop               Derruba os containers do Docker.
EOF
  ;;
esac

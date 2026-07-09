#!/usr/bin/env bash
# Executa o Ulisses rapidamente. Funciona no Git Bash (Windows), Linux e macOS.
#
#   ./run.sh            sobe TUDO via Docker (build + backend + frontend). Recomendado.
#   ./run.sh dev        modo desenvolvimento nativo com hot reload (backend + frontend)
#   ./run.sh build      apenas builda backend (jar) e frontend (dist), sem rodar
#   ./run.sh stop       derruba os containers do Docker
set -euo pipefail
cd "$(dirname "$0")"

info() { printf '\033[36m▶ %s\033[0m\n' "$*"; }
err() { printf '\033[31m✖ %s\033[0m\n' "$*" >&2; }

# Detecta um JDK 21 se JAVA_HOME nao estiver definido (locais comuns em Win/Linux/mac).
detect_java() {
  [ -n "${JAVA_HOME:-}" ] && { info "JAVA_HOME=$JAVA_HOME"; return 0; }
  for base in "E:/androidStudio/jdk" "/c/Program Files/Java" "/c/Program Files/Eclipse Adoptium" \
    "$HOME/.jdks" "/usr/lib/jvm" "/Library/Java/JavaVirtualMachines"; do
    for d in "$base"/*21*; do
      if [ -x "$d/bin/java" ]; then
        export JAVA_HOME="$d"
        info "JAVA_HOME detectado: $JAVA_HOME"
        return 0
      fi
      if [ -x "$d/Contents/Home/bin/java" ]; then
        export JAVA_HOME="$d/Contents/Home"
        info "JAVA_HOME detectado: $JAVA_HOME"
        return 0
      fi
    done
  done
  info "JAVA_HOME nao detectado; usando o 'java' do PATH (precisa ser 21+)."
}

mode="${1:-docker}"
case "$mode" in
docker | up)
  command -v docker >/dev/null 2>&1 || {
    err "Docker nao encontrado. Instale o Docker ou use: ./run.sh dev"
    exit 1
  }
  [ -f .env ] || {
    cp .env.example .env
    info ".env criado a partir de .env.example — troque os segredos antes de producao."
  }
  info "Subindo o Ulisses via Docker (o primeiro start baixa deps + modelos ~185MB)..."
  docker compose up --build
  info "Frontend: http://localhost:5173   |   Backend: http://localhost:8080"
  ;;
dev)
  detect_java
  command -v node >/dev/null 2>&1 || {
    err "Node.js nao encontrado."
    exit 1
  }
  info "Instalando dependencias do frontend (se necessario)..."
  (cd frontend && npm install)
  info "Subindo backend (:8080) e frontend (:5173) em modo dev. Ctrl+C encerra os dois."
  (cd backend && ./mvnw -q spring-boot:run) &
  back=$!
  (cd frontend && npm run dev) &
  front=$!
  trap 'err "Encerrando..."; kill "$back" "$front" 2>/dev/null || true' INT TERM
  wait
  ;;
build)
  detect_java
  info "Buildando backend (jar)..."
  (cd backend && ./mvnw -q -DskipTests package)
  info "Buildando frontend (dist)..."
  (cd frontend && npm install && npm run build)
  info "Pronto: backend/target/*.jar e frontend/dist/"
  ;;
stop | down)
  docker compose down
  ;;
*)
  cat <<'EOF'
Uso: ./run.sh [comando]

  (vazio) | docker   Sobe tudo via Docker (build + backend + frontend). Recomendado.
  dev                Desenvolvimento nativo com hot reload (backend + frontend).
  build              Builda backend (jar) e frontend (dist), sem rodar.
  stop               Derruba os containers do Docker.
EOF
  ;;
esac

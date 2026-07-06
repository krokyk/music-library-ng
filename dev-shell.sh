#!/usr/bin/env bash

# Source this file from a shell in the project:
#   source ./dev-shell.sh
#
# The function names are intentionally short and distinct for tab completion:
#   kp       stop app/dev processes from previous runs
#   bapp     build the app
#   brun     stop, build, and run the packaged jar
#   freshrun stop, delete data, build, and run the packaged jar
#   runjar   run the existing packaged jar

export MLNG_ROOT="${MLNG_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
export MLNG_APP_PORT="${MLNG_APP_PORT:-8795}"
export MLNG_VITE_PORT="${MLNG_VITE_PORT:-5173}"
export MLNG_JAR="${MLNG_JAR:-$MLNG_ROOT/build/quarkus-app/quarkus-run.jar}"

_mlng_pids_for_port() {
  local port="$1"

  if command -v fuser >/dev/null 2>&1; then
    fuser "${port}/tcp" 2>/dev/null | tr ' ' '\n' | awk 'NF'
    return
  fi

  if command -v lsof >/dev/null 2>&1; then
    lsof -ti "tcp:${port}" 2>/dev/null
  fi
}

_mlng_pids_for_project_commands() {
  local pid comm args
  while read -r pid comm args; do
    [[ "$pid" =~ ^[0-9]+$ ]] || continue
    case "$comm" in
      awk|bash|dash|grep|ps|rg|sed|sh)
        continue
        ;;
    esac
    if [[ "$args" == *quarkus-run.jar* ]]; then
      printf '%s\n' "$pid"
      continue
    fi
    case "$args" in
      *quarkusDev*|*"npm run dev"*|*node_modules/.bin/vite*|*"vite --host"*) ;;
      *) continue ;;
    esac
    if [[ "$args" == *"$MLNG_ROOT"* ]] || _mlng_pid_cwd_is_project "$pid"; then
      printf '%s\n' "$pid"
    fi
  done < <(ps -eo pid=,comm=,args=)
}

_mlng_pid_cwd_is_project() {
  local pid="$1"
  local cwd
  cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null)" || return 1
  [[ "$cwd" == "$MLNG_ROOT" || "$cwd" == "$MLNG_ROOT/"* ]]
}

_mlng_unique_pids() {
  awk -v self="$$" '
    $1 ~ /^[0-9]+$/ && $1 != self && !seen[$1]++ {
      print $1
    }
  '
}

kp() {
  local pids=()
  mapfile -t pids < <(
    {
      _mlng_pids_for_port "$MLNG_APP_PORT"
      _mlng_pids_for_port "$MLNG_VITE_PORT"
      _mlng_pids_for_project_commands
    } | _mlng_unique_pids
  )

  if ((${#pids[@]} == 0)); then
    echo "No Music Library NG app processes found."
    return 0
  fi

  echo "Stopping Music Library NG app processes: ${pids[*]}"
  kill "${pids[@]}" 2>/dev/null || true
  sleep 1

  local alive=()
  local pid
  for pid in "${pids[@]}"; do
    if kill -0 "$pid" 2>/dev/null; then
      alive+=("$pid")
    fi
  done

  if ((${#alive[@]} > 0)); then
    echo "Force stopping: ${alive[*]}"
    kill -9 "${alive[@]}" 2>/dev/null || true
  fi
}

bapp() {
  (cd "$MLNG_ROOT" && ./gradlew build "$@")
}

runjar() {
  if [[ ! -f "$MLNG_JAR" ]]; then
    echo "Packaged jar not found: $MLNG_JAR"
    echo "Run bapp first."
    return 1
  fi

  local java_opts=()
  if [[ -n "${MLNG_MUSIC_ROOT:-}" ]]; then
    java_opts+=("-Dmusic-library.music-root=$MLNG_MUSIC_ROOT")
  fi

  # MLNG_JAVA_OPTS is intentionally split so callers can pass normal JVM flags.
  # Example: MLNG_JAVA_OPTS='-Dquarkus.log.console.level=DEBUG'
  # shellcheck disable=SC2086
  (cd "$MLNG_ROOT" && java "${java_opts[@]}" ${MLNG_JAVA_OPTS:-} -jar "$MLNG_JAR" "$@")
}

brun() {
  kp
  bapp "$@" && runjar
}

freshrun() {
  kp
  echo "Deleting runtime data: $MLNG_ROOT/data"
  rm -rf "$MLNG_ROOT/data"
  bapp "$@" && runjar
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "This file defines shell functions. Source it instead:"
  echo "  source ./dev-shell.sh"
else
  cat <<'EOF'
Music Library NG dev commands:
  kp        stop app/dev processes from previous runs
  bapp      build the app
  runjar    run the existing packaged jar
  brun      stop, build, and run the packaged jar
  freshrun  stop, delete data/, build, and run the packaged jar
EOF
fi

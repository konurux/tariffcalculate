#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
JAR=target/tariff-calc.jar

java_major() {
  "$1" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | awk -F. '{ if ($1 == "1") print $2; else print $1 }'
}

find_java() {
  local c candidates=()
  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME/bin/java")
  if [[ -x /usr/libexec/java_home ]]; then
    c=$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)
    [[ -n "$c" ]] && candidates+=("$c/bin/java")
  fi
  for c in /opt/homebrew/opt/openjdk*/bin/java /usr/local/opt/openjdk*/bin/java /usr/lib/jvm/*/bin/java; do
    [[ -x "$c" ]] && candidates+=("$c")
  done
  command -v java >/dev/null 2>&1 && candidates+=("$(command -v java)")
  for c in ${candidates[@]+"${candidates[@]}"}; do
    local v
    v=$(java_major "$c" 2>/dev/null || true)
    if [[ "$v" =~ ^[0-9]+$ ]] && (( v >= 17 )); then echo "$c"; return 0; fi
  done
  return 1
}

if ! JAVA=$(find_java); then
  echo "Нужна Java 17 или новее (сейчас: $(java -version 2>&1 | head -1 || echo 'java не найдена'))." >&2
  echo "Установите: macOS — brew install openjdk@17; Ubuntu — sudo apt install openjdk-17-jdk;" >&2
  echo "или укажите путь: JAVA_HOME=/путь/к/jdk-17 ./run.sh ..." >&2
  exit 1
fi
export JAVA_HOME="$(cd "$(dirname "$JAVA")/.." && pwd)"

if [[ ! -f "$JAR" || -n "$(find src pom.xml -newer "$JAR" -print -quit)" ]]; then
  if ! command -v mvn >/dev/null 2>&1; then
    echo "Для сборки нужен Maven 3.8+: macOS — brew install maven; Ubuntu — sudo apt install maven." >&2
    exit 1
  fi
  echo "Собираю $JAR (первый запуск, около минуты)..." >&2
  mvn -q -B package -DskipTests >&2
fi
exec "$JAVA" -jar "$JAR" "$@"

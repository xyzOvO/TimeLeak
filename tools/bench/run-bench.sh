#!/usr/bin/env bash
# 匹配层微基准。先用 run-tests.sh 那一套生成 classpath，再单独跑 MatchBench。
# 改动匹配层前后各跑一次，对比 MATCH_BENCH 输出的 median / perNode。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/d/livetoolchain/jdk/jdk-17.0.21.0.2+1}"
GRADLE="${GRADLE:-/d/livetoolchain/gradle-8.9/bin/gradle}"

CP_FILE="$ROOT/app/build/unitTestClasspath.txt"
if [ ! -f "$CP_FILE" ]; then
  echo "先跑一次 ./run-tests.sh 生成 classpath" >&2
  exit 1
fi
CP="$(cat "$CP_FILE")"
exec "$JAVA_HOME/bin/java" -cp "$CP" org.junit.runner.JUnitCore com.chengliu.app.core.MatchBench

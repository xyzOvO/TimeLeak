#!/usr/bin/env bash
# 运行 JVM 单元测试。
#
# 为什么不直接用 `gradle testDebugUnitTest`：
#   本工程路径含中文（D:/AI库/Chengliu）。Gradle 8.9 在 classpath 过长时会把测试
#   JVM 的参数写成 @argfile（UTF-8），而 java.exe 读 @argfile 用的是系统编码
#   （中文 Windows 为 GBK），中文路径被解成乱码，项目内的 class 目录在测试 JVM 里
#   全部失效，表现为「用例被扫描到，但 7 个类全部 ClassNotFoundException」。
#   同一份 classpath 以 -cp 直接传参则完全正常，所以这里先让 Gradle 把 classpath
#   导出成文件，再由本脚本直接启动 JUnit。
#
# 工程迁到纯 ASCII 路径后，`gradle testDebugUnitTest` 同样可用。
#
# 用法：
#   ./run-tests.sh
# 环境变量（有默认值，可按需覆盖）：
#   JAVA_HOME、GRADLE

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_HOME="${JAVA_HOME:-/d/livetoolchain/jdk/jdk-17.0.21.0.2+1}"
GRADLE="${GRADLE:-/d/livetoolchain/gradle-8.9/bin/gradle}"

export JAVA_HOME

"$GRADLE" --offline --no-daemon -p "$ROOT" writeUnitTestClasspath -q

CP_FILE="$ROOT/app/build/unitTestClasspath.txt"
if [ ! -f "$CP_FILE" ]; then
  echo "未生成 classpath 文件：$CP_FILE" >&2
  exit 1
fi

CP="$(cat "$CP_FILE")"

TEST_CLASSES=(
  com.chengliu.app.core.DnsWireTest
  com.chengliu.app.core.IpWireTest
  com.chengliu.app.core.RuleEngineTest
  com.chengliu.app.core.CaptureRoutesTest
  com.chengliu.app.core.Json5Test
  com.chengliu.app.core.FloodGuardTest
  com.chengliu.app.core.UpdateCheckerTest
  com.chengliu.app.core.SkipEngineNegationTest
  com.chengliu.app.core.ClickTargetTest
)

exec "$JAVA_HOME/bin/java" -cp "$CP" org.junit.runner.JUnitCore "${TEST_CLASSES[@]}"

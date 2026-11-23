#!/bin/sh
# Gradle Wrapper 启动脚本（精简版）
# gradle/wrapper/gradle-wrapper.jar 已内置：腾讯云镜像 Gradle 9.9.1 发行包
# gradle-wrapper-main 模块内嵌的 Gradle 官方原版，来源与校验见 README.md 第 3 节。
set -e

APP_HOME=$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

if [ ! -f "$CLASSPATH" ]; then
    echo "缺少 gradle-wrapper.jar，请从交付包恢复该文件（见 README.md 第 3 节）" >&2
    exit 1
fi

# JAVA_OPTS / GRADLE_OPTS 需按空白展开为多个 JVM 参数，故意不加引号。
# shellcheck disable=SC2086
exec "$JAVACMD" -Xmx64m -Xms64m $JAVA_OPTS $GRADLE_OPTS \
    -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"

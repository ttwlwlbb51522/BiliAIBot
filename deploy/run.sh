#!/usr/bin/env bash
# 前台运行脚本。生产环境建议使用 systemd（见 bilibili-bot.service）。
set -euo pipefail

APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$APP_DIR"

JAVA_BIN="${JAVA_BIN:-java}"
JAR="$APP_DIR/bilibili-deepseek-bot.jar"

if [[ ! -f "$JAR" ]]; then
  echo "找不到 $JAR，请先执行 mvn clean package 并上传 jar。" >&2
  exit 1
fi

# 配置文件默认与 jar 同级，由程序首次运行自动生成；prompt/ 和 data/ 位于工作目录。
exec "$JAVA_BIN" \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=Asia/Shanghai \
  -Djava.awt.headless=true \
  ${JAVA_OPTS:-} \
  -jar "$JAR"

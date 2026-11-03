#!/usr/bin/env bash
# 用法: ./scripts/bump-version.sh major|minor|patch
set -euo pipefail

PART="${1:-}"
case "$PART" in
  major|minor|patch) ;;
  *) echo "用法: $0 major|minor|patch" >&2; exit 2 ;;
esac

ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
VERSION_FILE="$ROOT/VERSION"
POM_FILE="$ROOT/pom.xml"
CURRENT="$(tr -d '[:space:]' < "$VERSION_FILE")"

if ! [[ "$CURRENT" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "VERSION 格式不合法: $CURRENT" >&2
  exit 1
fi

MAJOR="${BASH_REMATCH[1]}"
MINOR="${BASH_REMATCH[2]}"
PATCH="${BASH_REMATCH[3]}"

case "$PART" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
esac

NEW_VERSION="$MAJOR.$MINOR.$PATCH"

if ! grep -q '<artifactId>bilibili-deepseek-bot</artifactId>' "$POM_FILE"; then
  echo "未能在 pom.xml 中找到项目" >&2
  exit 1
fi

# 更新 pom.xml 中第一个 <version>（项目版本；依赖版本在后面）。
# 使用 GNU sed（Linux）。若在 macOS 上执行，请改用 gsed 或手动修改 pom.xml。
sed -i -E "0,/<version>[0-9]+\.[0-9]+\.[0-9]+<\/version>/s//<version>${NEW_VERSION}<\/version>/" "$POM_FILE"

if ! grep -q "<version>${NEW_VERSION}</version>" "$POM_FILE"; then
  echo "更新 pom.xml 失败，请手动检查版本号" >&2
  exit 1
fi

printf '%s\n' "$NEW_VERSION" > "$VERSION_FILE"
echo "版本已更新: $CURRENT -> $NEW_VERSION"
echo "下一步: 更新 CHANGELOG.md，运行 mvn clean package，然后提交并打标签 v$NEW_VERSION。"

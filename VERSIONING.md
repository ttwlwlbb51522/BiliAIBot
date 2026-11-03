# 版本管理规范

本项目采用语义化版本号：

```text
MAJOR.MINOR.PATCH
  1  .  0  .  0
```

## 版本号规则

| 变更类型 | 递增位置 | 示例 | 说明 |
| --- | --- | --- | --- |
| 大更新 | 第一位 `MAJOR` | `1.4.2` → `2.0.0` | 不兼容变更：配置格式破坏、核心架构重写、重大行为变化 |
| 小更新 | 第二位 `MINOR` | `1.4.2` → `1.5.0` | 向后兼容的新功能 |
| Bug 修复 | 第三位 `PATCH` | `1.4.2` → `1.4.3` | 修复缺陷、文档修正、不影响接口的小调整 |

规则：

- 递增某一位时，右侧低位归零。
- 发布版本使用 Git 标签 `vX.Y.Z`，例如 `v1.0.0`。
- `VERSION` 文件和 `pom.xml` 中的版本号必须保持一致。
- 已发布的标签不移动、不覆盖、不复用。

## 当前版本

```text
1.0.1
```

## 发布流程

1. 修改代码并更新 `CHANGELOG.md`。
2. 根据变更类型递增版本号：

```powershell
# Windows
.\scripts\bump-version.ps1 minor
```

```bash
# Linux / macOS
./scripts/bump-version.sh minor
```

3. 构建并测试：

```powershell
mvn clean package
```

4. 提交并打标签：

```bash
git add .
git commit -m "chore(release): v1.1.0"
git tag -a v1.1.0 -m "v1.1.0"
git push origin master
git push origin v1.1.0
```

也可以使用 PowerShell 脚本自动提交并打标签：

```powershell
.\scripts\bump-version.ps1 minor -Commit -Tag
```

## 提交信息

建议使用 Conventional Commits：

```text
feat: 新增二维码登录
fix: 修复 Cookie 过期后不重试的问题
docs: 更新 Linux 部署说明
refactor: 重构登录服务
chore(release): v1.1.0
```

## 分支与标签

- `master`：稳定分支，保持可构建、可部署。
- 功能开发可使用 `feature/*` 分支，合并前通过测试。
- 每次发布打一个 annotated tag：`vX.Y.Z`。
- `target/`、`logs/`、`data/`、本地 `application.properties` 不进入版本库。

## 构建产物

构建产物位于 `target/bilibili-deepseek-bot.jar`，不提交到 Git。部署时以对应 Git 标签构建出的 jar 为准。

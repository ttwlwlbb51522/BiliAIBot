# Bilibili DeepSeek 评论区 AI 机器人（Java）

在 B 站评论区里，用指定的**主人账号** @ 机器人账号，机器人就会读取这条评论及同一评论串的上下文，调用 DeepSeek 生成回复，并以**回复该评论**的形式发回评论区。

项目使用 Java 17 + Maven 编写，不依赖数据库，单进程轮询即可运行。

当前版本：**1.2.0**（见 [VERSION](VERSION) 与 [CHANGELOG.md](CHANGELOG.md)）

---

## 1. 功能

- 支持**二维码扫码登录**，直接在终端显示二维码，用 B 站手机客户端扫码确认。
- 支持**账号密码登录** B 站机器人账号，登录成功后缓存会话，后续启动自动复用。
- 登录后如触发 B 站安全验证，会把验证链接打印到控制台，手动完成后继续登录。
- 也支持传统 Cookie 登录，作为密码登录失败时的备用方式。
- 可配置**主人账号 UID**，只有该 UID 的 @ 才会触发。
- 通过 B 站「@ 我的」消息接口轮询，支持视频、动态、专栏、音频等评论区的类型识别。
- 调用 DeepSeek `chat/completions` 生成回复，支持自定义模型、温度、最大 token。
- 自动读取同一根评论下的最近对话，让 AI 能接上上下文聊天。
- 内置「蓝色大肥鱼」角色人设，也支持自由修改系统提示词。
- 提供 Linux systemd 部署文件，服务器可独立长期运行，不依赖 B 站客户端或你的电脑。
- Minecraft 服务端风格后台控制台，支持 `stop`、`status`、`version`、`help`。
- 后台日志详细记录 @ 接收、过滤、Prompt 构造、DeepSeek 调用和评论发送过程。
- 回复直接发在主人评论下方（正确设置 `root` / `parent`），不会另起一条一级评论。
- 自动清理 Markdown、限制 1000 字以内、避免截断 emoji。
- 本地状态文件去重，重启后不会重复回复同一条评论。
- 首次启动默认跳过历史 @，避免上线时批量补回复旧评论。
- 内置 **DRY-RUN 模式**：只把拟回复写日志，不实际发评论，方便先验证。
- 失败重试、登录失败提示、限流/风控提示、Cookie 失效提示。
- 单元测试 + 本地 HTTP mock 测试，无需真实账号即可验证主要逻辑。

---

## 2. 工作原理

```
┌─────────────────────┐
│ B站 @ 消息接口       │  GET /x/msgfeed/at
└──────────┬──────────┘
           │ 每 45 秒轮询
           ▼
┌─────────────────────┐
│ 过滤：发出者 UID     │
│ == 配置的主人 UID    │
│ 且确实 @ 了机器人    │
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 提取 source_id 等    │  root = root_id || source_id
│ 计算回复目标         │  parent = source_id
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 读取评论串上下文     │  GET /x/v2/reply/reply
│ 组装 prompt          │
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ DeepSeek Chat API   │  POST /chat/completions
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 清洗/截断回复        │
│ 发表二级评论         │  POST /x/v2/reply/add
└─────────────────────┘
```

相关 B 站接口（非官方，可能随版本变化）：

| 用途      | 接口                                                 |
|---------|----------------------------------------------------|
| 查询登录账号  | `GET https://api.bilibili.com/x/web-interface/nav` |
| 获取 @ 消息 | `GET https://api.bilibili.com/x/msgfeed/at`        |
| 获取评论串   | `GET https://api.bilibili.com/x/v2/reply/reply`    |
| 发表回复    | `POST https://api.bilibili.com/x/v2/reply/add`     |

> 这些是 Web 端接口，不是 B 站开放平台 API。使用自动化操作存在账号风险，请阅读文末的「风险与合规」。

---

## 3. 环境要求

- JDK 17 或更高版本
- Maven 3.8+
- 一个可以正常发评论的 B 站账号（建议使用**小号**，不要用主账号）
- 一个 DeepSeek API Key：<https://platform.deepseek.com/>

检查环境：

```powershell
java -version
mvn -v
```

---

## 4. 准备配置

### 4.1 登录机器人账号

程序支持三种登录方式：二维码、账号密码、Cookie。通过 `bilibili.login-type` 选择：

| 值          | 行为                                  |
|------------|-------------------------------------|
| `auto`     | 缓存会话 → 有效 Cookie → 有账号密码则密码登录 → 二维码 |
| `qrcode`   | 缓存会话 → 有效 Cookie → 二维码              |
| `password` | 缓存会话 → 有效 Cookie → 账号密码             |
| `cookie`   | 只使用缓存会话或配置的 Cookie                  |

#### 方式一：二维码登录（推荐）

不需要填写账号密码或 Cookie。保持登录信息为空，配置：

```properties
bilibili.login-type=auto
# 或者强制二维码：
# bilibili.login-type=qrcode
```

启动后终端会显示二维码：

```text
请使用 B 站手机客户端扫描下方二维码：
████████...（终端二维码）
二维码有效期约 180 秒，扫码后请在手机上确认登录。
```

用 B 站手机 App 扫码并确认即可，登录成功后 Cookie 会保存到 `data/session.json`，后续启动自动复用。二维码登录需要交互式终端，不能在 systemd 后台服务中扫码；服务器首次登录请通过 SSH 终端完成。

#### 方式二：账号密码登录

在配置中填写：

```properties
bilibili.username=机器人账号的手机号或邮箱
bilibili.password=机器人账号密码
```

> **重要限制**：B 站密码登录强制要求极验人机验证，无法只靠账号密码全自动完成。首次密码登录时，程序会在终端提示你完成一次验证：
>
> 1. 打开终端显示的验证码页面；
> 2. 填入程序输出的 `gt` 和 `challenge`；
> 3. 完成滑动/点击验证，复制 `validate` 和 `seccode`；
> 4. 回到终端粘贴这两个值。
>
> 登录成功后 Cookie 会缓存到 `data/session.json`，之后启动会自动复用，不需要每次验证。验证码页面只用于人机验证，**不要在任何第三方页面输入 B 站密码**。
>
> 如果 B 站认为登录环境有风险，会在密码验证后返回一个安全验证链接。程序会把这个链接**直接打印到控制台**（并尝试自动打开浏览器）。手动完成手机号/邮箱验证后，可以把浏览器最终跳转的 URL 或完整 Cookie 粘贴回控制台，程序会继续登录；也可以直接改用 `bilibili.cookie`。

> 密码会以明文形式保存在配置文件中，请务必保护好文件权限，不要提交到 Git 或发给他人。也可以改用环境变量 `BILI_PASSWORD`。

#### 方式三：Cookie 登录（备用）

1. 在浏览器中登录**机器人账号**。
2. 打开 <https://message.bilibili.com/>，按 `F12` 打开开发者工具。
3. 切到 **Network / 网络**，刷新页面。
4. 找到任意一个发往 `api.bilibili.com` 的请求，复制请求头里的完整 `Cookie`。
5. 填写到 `bilibili.cookie`，必须包含 `SESSDATA` 和 `bili_jct`。

> 登录优先级：`data/session.json` 缓存 → 有效 Cookie → 根据 `login-type` 使用账号密码或二维码。二维码和密码登录成功后都会写入会话缓存。

### 4.2 获取主人 UID

打开主人账号的空间页：

```
https://space.bilibili.com/123456789
```

`123456789` 就是 UID，填入配置。

### 4.3 配置文件的位置与自动生成

常用配置项和高级配置项都放在 `application.properties` 中，不再写死在 Java 代码里。

- **用 jar 运行**：配置文件位于 **jar 同级目录**。
  例如运行 `target\bilibili-deepseek-bot.jar`，配置文件就是 `target\application.properties`。
- **用 IDE / 源码运行**：配置文件位于当前工作目录。
- **手动指定**：使用 `-Dbot.config=路径` 或命令行第一个参数。

首次运行且文件不存在时，程序会自动生成带完整注释的配置模板，然后提示填写必填项。之后每次启动：

- 缺失的配置键会自动追加到文件末尾；
- 键存在但值为空时，会用默认值补全；
- 已经填写的值不会被覆盖。

第一次生成后，编辑配置文件中的以下内容。登录方式可以三选一，也可以全部留空使用二维码：

```properties
# 登录方式：auto / qrcode / password / cookie
bilibili.login-type=auto

# 方式一：二维码登录（推荐交互式终端；上面保持 auto 并留空以下登录信息即可）

# 方式二：账号密码
# bilibili.username=机器人账号的手机号或邮箱
# bilibili.password=机器人账号密码

# 方式三：Cookie（适合无人值守服务器）
# bilibili.cookie=SESSDATA=你的值; bili_jct=你的值; buvid3=你的值

bilibili.owner-uid=123456789
deepseek.api-key=sk-你的key

# 第一次先保持 true，观察日志确认效果
bilibili.dry-run=true
```

> 配置模板可参考 [application.properties.example](application.properties.example)。生成的配置文件包含登录凭据，`target/` 和根目录的 `application.properties` 都已被 `.gitignore` 忽略；不要把配置提交到公开仓库。

也可以用环境变量覆盖，适合服务器部署：

| 环境变量                         | 对应配置                             |
|------------------------------|----------------------------------|
| `BILI_LOGIN_TYPE`            | `bilibili.login-type`            |
| `BILI_USERNAME`              | `bilibili.username`              |
| `BILI_PASSWORD`              | `bilibili.password`              |
| `BILI_COOKIE`                | `bilibili.cookie`                |
| `BILI_OWNER_UID`             | `bilibili.owner-uid`             |
| `BILI_POLL_INTERVAL_SECONDS` | `bilibili.poll-interval-seconds` |
| `BILI_DRY_RUN`               | `bilibili.dry-run`               |
| `BILI_PROCESS_EXISTING`      | `bilibili.process-existing`      |
| `BILI_TRIGGER_ALIASES`       | `bilibili.trigger-aliases`       |
| `DEEPSEEK_API_KEY`           | `deepseek.api-key`               |
| `DEEPSEEK_BASE_URL`          | `deepseek.base-url`              |
| `DEEPSEEK_MODEL`             | `deepseek.model`                 |
| `APP_STATE_FILE`             | `app.state-file`                 |
| `APP_SESSION_FILE`           | `app.session-file`               |
| `BOT_SYSTEM_PROMPT_FILE`     | `reply.system-prompt-file`       |

PowerShell 示例：

```powershell
$env:BILI_USERNAME="机器人账号手机号或邮箱"
$env:BILI_PASSWORD="机器人账号密码"
$env:BILI_OWNER_UID="123456789"
$env:DEEPSEEK_API_KEY="sk-..."
java -jar target\bilibili-deepseek-bot.jar
```

---

## 5. 构建与运行

```powershell
# 在项目根目录执行
mvn clean package

# 首次运行：会在 target 下自动生成 application.properties，并提示填写必填项
java -jar target\bilibili-deepseek-bot.jar

# 编辑 target\application.properties，填入机器人账号密码、主人 UID、DeepSeek Key
notepad target\application.properties

# 再次运行
java -jar target\bilibili-deepseek-bot.jar
```

首次使用账号密码登录时，程序会暂停并提示完成极验人机验证。按照终端提示操作即可；成功后登录会话会保存到 `data/session.json`，下次启动不再重复验证。

> `mvn clean` 会删除整个 `target` 目录，包括里面的配置文件。需要保留配置时，请把 jar 和 `application.properties` 复制到一个单独的运行目录，或使用下面的显式配置路径。

也可以显式指定配置文件：

```powershell
java -Dbot.config=D:\path\to\application.properties -jar target\bilibili-deepseek-bot.jar
```

或：

```powershell
java -jar target\bilibili-deepseek-bot.jar D:\path\to\application.properties
```

启动成功后会看到类似日志：

```
机器人账号：DeepSeek小助手（UID 123456）
主人账号 UID：987654321
模型：deepseek-chat，轮询间隔：45s，状态文件：...\data\state.json
当前为 DRY-RUN 模式：只生成回复并写入日志，不会真的发表评论。
开始监听 @ 消息。按 Ctrl+C 退出。
```

### 第一次测试

1. 保持 `bilibili.dry-run=true` 启动。
2. 等日志出现「开始监听」。
3. 用**主人账号**在一个视频评论区发一条：
   ```
   @你的机器人账号昵称 用一句话介绍你自己
   ```
   > 必须真的选中 B 站的 @ 用户，让评论进入机器人的「@ 我的」消息。
4. 最多等待一个轮询周期，日志会出现 `[DRY-RUN] ... 拟回复：`。
5. 确认内容无误后，把 `bilibili.dry-run` 改为 `false`，重启。
6. 再 @ 一次，机器人会在你的评论下方回复。

> 程序首次启动默认跳过 `bilibili.first-run-grace-seconds`（默认 120 秒）以前的历史 @，避免上线瞬间回复一堆旧消息。若确实要处理历史消息，设置 `bilibili.process-existing=true`，并用 `bilibili.max-mention-age-minutes` 限制回溯范围。

### 控制台命令

启动成功后，程序会进入类似 Minecraft 服务端的后台控制台：

```text
> help
可用命令:
  help     显示此帮助
  status   查看运行状态和运行时长
  version  查看程序版本
  stop     安全停止机器人（也可用 shutdown/exit/quit）
```

常用命令：

```text
status   查看是否在运行、已运行时长
version  查看当前版本
stop     停止轮询并安全退出
```

`stop` 会先停止轮询线程，再退出程序；效果与 `Ctrl+C` 相同。Linux 使用 systemd 时也可以执行：

```bash
sudo systemctl stop bilibili-bot
```

> 如果程序的 stdin 被关闭或重定向（例如 systemd 的 `/dev/null`），控制台线程会自动退出，机器人继续在后台运行，不影响轮询。

### 运行日志

每条新的 @ 通知和后续处理过程都会输出到控制台和 `logs/bot.log`，主要阶段：

```text
[接收] @通知 event=... 用户=... 内容=...
[接收] 目标: 标题=... 评论区类型=... oid=... root=... source=...
[过滤] 命中主人 @: ...
[处理] 开始: ...
[处理] 评论串上下文: N 条
[处理] Prompt 构造完成: 2 条消息, N 字
[处理] 调用 DeepSeek: model=deepseek-chat
[处理] DeepSeek 返回: N 字, 耗时=...ms
[处理] 回复整理完成: N 字
[发送] 正在发表评论: oid=... type=... root=... parent=...
[发送] 成功: event=... 新评论 rpid=... 总耗时=...ms
```

非主人 @、未命中触发条件、过期评论也会用 `[过滤]` 或 `[处理]` 日志说明原因。相同的 @ 通知不会在每个轮询周期重复打印。

日志包含评论和回复内容，请妥善保护 `logs/` 目录。

### 5.1 Linux 服务器部署（systemd）

机器人本身**不依赖 B 站客户端**，它只通过 HTTPS 调用 B 站 Web API 和 DeepSeek API。只要 Linux 服务器能联网，你的电脑关机后它仍会继续运行。

首次获取 Cookie、完成密码登录的人机验证或扫码登录时，需要交互式终端/手机；登录完成后，服务器上的 Java 进程可以完全独立运行。

#### 推荐：服务器使用 Cookie 登录

服务器没有图形界面，密码登录的人机验证需要交互式终端。最稳妥的方式是在电脑浏览器登录机器人账号，复制 Cookie，填到服务器的配置文件中。

```bash
# 1. 安装 Java 17（Debian/Ubuntu 示例）
sudo apt update
sudo apt install -y openjdk-17-jre-headless

# 2. 创建运行用户和目录
sudo useradd --system --home /opt/bilibili-bot --shell /usr/sbin/nologin bilibot
sudo mkdir -p /opt/bilibili-bot/prompt /opt/bilibili-bot/data /opt/bilibili-bot/logs

# 3. 上传文件（在本地构建后执行，或手动用 scp 上传）
scp target/bilibili-deepseek-bot.jar user@服务器:/tmp/
scp application.properties.example user@服务器:/tmp/
scp prompt/system.txt prompt/character.json user@服务器:/tmp/
scp deploy/run.sh user@服务器:/tmp/

# 服务器上执行
sudo mv /tmp/bilibili-deepseek-bot.jar /opt/bilibili-bot/
sudo mv /tmp/application.properties.example /opt/bilibili-bot/application.properties
sudo mv /tmp/system.txt /tmp/character.json /opt/bilibili-bot/prompt/
sudo mv /tmp/run.sh /opt/bilibili-bot/run.sh
sudo chmod +x /opt/bilibili-bot/run.sh
sudo chown -R bilibot:bilibot /opt/bilibili-bot
sudo chmod 600 /opt/bilibili-bot/application.properties
```

编辑 `/opt/bilibili-bot/application.properties`，至少配置：

```properties
bilibili.cookie=SESSDATA=...; bili_jct=...; buvid3=...
bilibili.owner-uid=你的主人UID
deepseek.api-key=sk-...
bilibili.dry-run=true
```

> 服务器使用 Cookie 时，建议把 `bilibili.username` 和 `bilibili.password` 留空，避免 Cookie 失效后无人值守的服务卡在人机验证。

前台试运行：

```bash
sudo -u bilibot bash -c 'cd /opt/bilibili-bot && ./run.sh'
```

确认日志正常后按 `Ctrl+C` 停止。

#### 使用二维码登录的服务器方案

如果不想复制 Cookie，可以在 SSH 终端里先扫码登录一次，让服务器生成 `data/session.json`：

```bash
sudo -u bilibot bash -c 'cd /opt/bilibili-bot && ./run.sh'
```

配置保持：

```properties
bilibili.login-type=auto
# username/password/cookie 都留空
```

程序会在 SSH 终端打印二维码。用 B 站手机 App 扫码并确认，登录成功后按 `Ctrl+C` 停止，再启动 systemd。之后服务器会自动复用会话。

> systemd 后台没有交互式终端，不能显示/扫描二维码；二维码登录只用于首次生成 `data/session.json`，或会话过期后的重新登录。

#### 使用账号密码登录的服务器方案

账号密码登录必须先在一个**交互式 SSH 终端**中完成一次极验验证，生成 `/opt/bilibili-bot/data/session.json`，然后再交给 systemd 运行：

```bash
sudo -u bilibot bash -c 'cd /opt/bilibili-bot && ./run.sh'
# 按终端提示打开验证链接、完成验证、粘贴 validate/seccode
# 看到登录成功、开始监听后按 Ctrl+C
```

之后 systemd 会复用 `data/session.json`。如果 B 站要求登录后的安全验证，按控制台打印的链接手动完成，并把最终 URL/Cookie 粘贴回终端。会话过期后需要重新做一次人工登录。

#### 安装 systemd 服务

```bash
sudo cp deploy/bilibili-bot.service /etc/systemd/system/bilibili-bot.service
sudo systemctl daemon-reload
sudo systemctl enable --now bilibili-bot

# 查看状态和实时日志
sudo systemctl status bilibili-bot
sudo journalctl -u bilibili-bot -f
```

服务文件默认路径是 `/opt/bilibili-bot`、用户是 `bilibot`。如果你的路径或 Java 路径不同，请先修改 [deploy/bilibili-bot.service](deploy/bilibili-bot.service)。

验证重启后仍能自动运行：

```bash
sudo reboot
# 重新登录服务器后
sudo systemctl status bilibili-bot
```

#### 服务器注意事项

- **不需要安装 B 站客户端**，也不需要保持电脑开机。
- 服务器只需放行**出站 HTTPS**，不需要开放入站端口。
- `systemd` 服务设置了 `StandardInput=null`，不能进行人机验证；密码登录必须先手动生成 `data/session.json`，或直接使用 Cookie。
- `application.properties`、`data/session.json` 含登录凭据，务必设置 `600` 权限，不要提交到 Git。
- 建议备份 `data/state.json`、`data/session.json`；删除 `session.json` 会导致下次需要重新登录。
- 日志在 `/opt/bilibili-bot/logs/bot.log` 和 `journalctl -u bilibili-bot`。
- 如果机器时间不准，请启用 NTP；时间偏差可能导致 B 站接口签名/登录异常。
- 服务器 IP 与获取 Cookie 时的家庭 IP 不同，可能更容易触发 B 站风控；遇到 `HTTP 412` 时请降低轮询频率或更换网络环境。

---

## 6. 配置说明

### B 站

| 配置                                 | 默认值     | 说明                                         |
|------------------------------------|---------|--------------------------------------------|
| `bilibili.login-type`              | `auto`  | `auto` / `qrcode` / `password` / `cookie`  |
| `bilibili.username`                | 空       | 机器人账号（手机号/邮箱），与密码同时填写                      |
| `bilibili.password`                | 空       | 机器人账号密码（明文，注意保护）                           |
| `bilibili.cookie`                  | 空       | 备用登录方式，完整 Cookie，必须含 `SESSDATA`、`bili_jct` |
| `bilibili.owner-uid`               | `0`     | 主人 UID，只有这个 UID 能触发                        |
| `bilibili.require-bot-mention`     | `true`  | 是否额外校验确实 @ 了机器人                            |
| `bilibili.process-existing`        | `false` | 首次启动是否处理已存在的 @                             |
| `bilibili.first-run-grace-seconds` | `120`   | 首次启动时，最近多少秒内的 @ 仍视为新消息                     |
| `bilibili.max-mention-age-minutes` | `120`   | 超过此时间的 @ 不再回复                              |
| `bilibili.poll-interval-seconds`   | `45`    | 轮询间隔，最小 10 秒                               |
| `bilibili.request-timeout-seconds` | `20`    | B 站请求超时                                    |
| `bilibili.thread-context-size`     | `12`    | 读取多少条同串回复作为上下文，0 关闭                        |
| `bilibili.max-attempts`            | `3`     | 可重试错误最多跨轮询尝试次数                             |
| `bilibili.trigger-aliases`         | 空       | 额外触发别名，逗号分隔                                |
| `bilibili.dry-run`                 | `true`  | 只写日志，不实际发评论                                |
| `bilibili.at-feed-url`             | 官方      | @ 消息接口，一般不改                                |

### DeepSeek

| 配置                         | 默认值                        | 说明                             |
|----------------------------|----------------------------|--------------------------------|
| `deepseek.api-key`         | 空                          | API Key                        |
| `deepseek.base-url`        | `https://api.deepseek.com` | 可改为兼容代理地址                      |
| `deepseek.model`           | `deepseek-chat`            | 也可用 `deepseek-reasoner`（更慢、更贵） |
| `deepseek.temperature`     | `0.8`                      | 0 ~ 2                          |
| `deepseek.max-tokens`      | `800`                      | 回复 token 上限                    |
| `deepseek.timeout-seconds` | `90`                       | 请求超时                           |
| `deepseek.max-retries`     | `2`                        | 单次生成内部重试次数                     |

### 回复

| 配置                         | 默认值                 | 说明                   |
|----------------------------|---------------------|----------------------|
| `reply.max-length`         | `900`               | 最终评论最大字符数，B 站上限 1000 |
| `reply.mention-owner`      | `true`              | 回复开头是否 @ 主人          |
| `reply.prefix`             | 空                   | 固定前缀，例如 `【AI】`       |
| `reply.fallback-text`      | `嗯嗯，我在听，你继续说～`      | 模型返回空内容时的兜底回复        |
| `reply.system-prompt-file` | `prompt/system.txt` | 人设/规则文件              |
| `reply.max-context-chars`  | `3000`              | 评论串上下文最大字符数          |

### 运行

| 配置                 | 默认值                 | 说明             |
|--------------------|---------------------|----------------|
| `app.state-file`   | `data/state.json`   | 去重状态文件         |
| `app.session-file` | `data/session.json` | 登录 Cookie 会话缓存 |

---

## 7. 自定义人设

当前内置人设是「蓝色大肥鱼」：一只自称“鲸鱼娘”的蓝鲸少女，慵懒、傲娇、爱吃米饭，对主人软糯，绝不承认自己胖。

- 实际生效的系统提示词：[prompt/system.txt](prompt/system.txt)
- 原始角色卡：[prompt/character.json](prompt/character.json)

编辑 `prompt/system.txt` 可以修改：

- 角色名字、身份、语气、性格
- 动作描写和口头禅
- 回复长度与格式要求
- 禁止话题
- 是否说明自己是 AI

`{{max_length}}` 会被替换成 `reply.max-length`。修改后重启生效。

> `prompt/character.json` 保留原始角色卡，便于以后重新生成或调整人设；程序当前不会动态解析该文件，真正生效的是 `prompt/system.txt`。

---

## 8. 项目结构

```
.
├── application.properties.example  # 配置模板（首次运行会自动生成 application.properties）
├── CHANGELOG.md                    # 更新日志
├── VERSION                         # 当前版本号
├── VERSIONING.md                   # 版本管理规范
├── deploy/
│   ├── bilibili-bot.service        # systemd 服务文件
│   └── run.sh                      # Linux 前台运行脚本
├── scripts/
│   ├── bump-version.ps1            # Windows 版本号递增脚本
│   └── bump-version.sh             # Linux 版本号递增脚本
├── prompt/
│   ├── system.txt                  # 当前生效的系统提示词（蓝色大肥鱼）
│   └── character.json              # 原始角色卡（参考/备份）
├── pom.xml
└── src
    ├── main
    │   ├── java/cn/citprobe/bilibot
    │   │   ├── BotApplication.java      # 启动入口
    │   │   ├── BotRunner.java           # 定时轮询、异常兜底
    │   │   ├── auth/                    # 账号密码/Cookie 登录、会话缓存
    │   │   ├── bilibili/                # B站接口客户端与 DTO
    │   │   ├── config/                  # 配置定位、生成、补全、加载与校验
    │   │   ├── console/                 # Minecraft 风格控制台命令
    │   │   ├── deepseek/                # DeepSeek Chat 客户端
    │   │   ├── mention/                 # @ 事件解析、过滤、类型识别
    │   │   ├── service/                 # 业务编排、Prompt、回复清洗
    │   │   ├── state/                   # 去重状态持久化
    │   │   └── util/
    │   └── resources/
    │       ├── config-template.properties  # 自动生成配置文件用的带注释模板
    │       └── logback.xml                # 控制台 + logs/bot.log
    └── test/                            # 单元测试与 HTTP mock 测试
```

日志默认输出到控制台和 `logs/bot.log`，可以设置环境变量 `LOG_LEVEL=DEBUG`、`LOG_DIR`。

### 控制台编码

程序启动时会自动识别终端编码：

- Windows 真实控制台：按 `stdout.encoding` / `native.encoding` / `sun.jnu.encoding` 识别 GBK 或 UTF-8；
- 管道、IDE、`systemd`/journal：统一使用 UTF-8；
- `logs/bot.log` 始终使用 UTF-8。

如果终端仍出现乱码，可以手动指定：

```powershell
$env:CONSOLE_ENCODING="UTF-8"
java -jar target\bilibili-deepseek-bot.jar
```

或：

```powershell
java -Dconsole.encoding=GBK -jar target\bilibili-deepseek-bot.jar
```

Linux 服务器一般不需要设置；systemd 日志会以 UTF-8 输出。

---

## 9. 测试

```powershell
mvn test
```

测试覆盖：

- 配置校验与账号密码/Cookie 二选一校验
- 配置文件首次生成、缺失键补全、空值修复、用户值不覆盖
- B 站密码 RSA 加密、Cookie 解析、登录会话缓存
- @ 通知字段映射（含 `at_time`、嵌套回复 root/parent）
- 主人 UID / @ 检测 / 评论区类型识别
- Markdown 清洗、长度截断、emoji 不截断
- Prompt 构造与触发词剥离
- 状态持久化与去重
- B 站 at 消息接口 JSON 解析（本地 mock）
- DeepSeek 请求/响应（本地 mock）

---

## 10. 常见问题

### 启动提示缺少配置

编辑日志中显示的配置文件，至少填写 `bilibili.owner-uid`、`deepseek.api-key`。登录信息可以填写账号密码、Cookie，也可以留空并在终端使用二维码登录。用 jar 运行时配置在 jar 同级目录；用源码/IDE 运行时在当前工作目录。

### 二维码不显示或扫不出来

二维码登录需要**交互式终端**，不能在 systemd 后台服务、重定向输出或部分 IDE 控制台中完成。请通过 SSH 或本机终端运行一次：

```bash
sudo -u bilibot bash -c 'cd /opt/bilibili-bot && ./run.sh'
```

终端二维码会同时保存为 `data/login-qrcode.png`，如果终端里的二维码显示异常，可以打开/下载这个图片再扫描。扫码后需要在手机 B 站客户端点击确认。

### 二维码登录超时

二维码有效期约 180 秒。超时后重新启动程序生成新二维码；登录成功后会话会缓存到 `data/session.json`，后续不需要重复扫码。

### 密码登录一直要求人机验证 / 验证码过期

B 站密码登录强制极验，无法绕过。请重新启动程序，按终端提示打开验证页面，并在验证码过期前粘贴 `validate` 和 `seccode`。如果反复失败，或 B 站要求手机号验证，请改用 `bilibili.cookie`。

### 密码登录后要求安全验证

这是 B 站的风控流程。控制台会打印形如 `https://passport.bilibili.com/...` 的验证链接，按提示在浏览器手动完成验证。完成后可选择：

1. 粘贴浏览器最终跳转的完整 URL（如果 URL 查询参数中包含 `SESSDATA` 和 `bili_jct`）；
2. 从浏览器开发者工具复制完整 Cookie 粘贴到控制台；
3. 直接回车退出，把 Cookie 填到 `bilibili.cookie`。

程序不会自动破解极验或短信验证码。

### 密码登录失败，错误码 `-105` / `-629` / `-2100`

- `-105`：人机验证失败或过期，重新启动再试。
- `-629`：账号密码错误，或登录环境被 B 站判定为高风险。
- `-2100`：需要手机号/邮箱验证，请先在浏览器完成验证，或改用 Cookie。
- 成功后不要删除 `data/session.json`，它会避免下次重复验证；删除后需要重新验证。

### 日志出现 `-101 账号未登录`

Cookie 或缓存会话已失效。程序会自动尝试账号密码登录；如果未配置账号密码，请重新获取包含 `SESSDATA`、`bili_jct` 的完整 Cookie。

### `mvn clean` 后配置文件不见了

`mvn clean` 会删除 `target`，因此放在 `target\application.properties` 的配置也会被删除。请把 jar 和配置文件复制到独立的运行目录，或用 `-Dbot.config` 指定一个 `target` 之外的路径。

### 配置项缺失或值为空

程序启动时会自动补全：缺失键追加到文件末尾，空值用模板默认值修复，已有值不会覆盖。日志会明确列出补全了哪些键，并显示配置文件完整路径。

### 日志出现 `HTTP 412` / 风控

- 降低频率：把 `bilibili.poll-interval-seconds` 调到 60~120。
- 不要用机房 IP、代理池或频繁切换 IP。
- 确认 Cookie 来自常用浏览器环境。
- 停止一段时间后再试。自动化操作本身有账号风险。

### 主人 @ 了但没反应

按顺序检查：

1. `bilibili.owner-uid` 是否是**发评论账号**的 UID，而不是机器人账号的 UID。
2. 评论里是否真的通过 B 站 @ 面板选中了机器人账号。
3. 机器人账号的「@ 我的」消息里能否看到这条通知。
4. 是否在首次启动的历史窗口内被跳过（`first-run-grace-seconds`），或超过 `max-mention-age-minutes`。
5. 评论区是否关闭、是否被 UP 主拉黑、是否需要验证码。
6. 日志级别调到 `DEBUG` 看是否被过滤。

### 报错 `12002` / `12051` / `12015` / `12035`

- `12002` / `12052`：评论区已关闭。
- `12051`：重复评论或刷屏限制。
- `12015`：需要验证码，程序不会自动破解，需换账号或稍后重试。
- `12035`：被 UP 主加入评论黑名单。

### DeepSeek 401 / 402 / 403

API Key 错误、余额不足或无权限。程序不会把这类评论标记为已处理，修复配置后下一轮会自动重试。

### 回复内容被截断

模型输出太长。调大 `deepseek.max-tokens`，或让系统提示词要求更短。最终仍会受 `reply.max-length` 和 B 站 1000 字限制。

### 程序重启后会不会重复回复？

不会。`data/state.json` 同时记录事件 ID 和源评论 rpid。只要状态文件保留，同一条评论不会被重复回复。删除状态文件会导致去重历史丢失。

---

## 11. 风险与合规

- 本项目使用的是**非官方 Web 接口**，接口字段和行为可能随时变化。
- 账号密码以**明文**保存在配置文件中，`data/session.json` 也包含登录 Cookie。请限制文件权限，不要提交到 Git 或发送给他人。
- B 站密码登录强制人机验证，首次登录需要人工完成；本项目不包含验证码破解功能。
- 使用脚本自动发评论可能违反 B 站用户协议，存在**限制、封禁账号**的风险。请使用小号、低频率、仅在自己可控的场景使用。
- AI 生成内容可能出错。建议保持 `reply.prefix=【AI】` 或在系统提示词中要求注明 AI 身份。
- 评论内容和上下文会发送到 DeepSeek API，请勿在评论中放置隐私信息。
- 机器人回复是公开内容，发布前建议先用 `dry-run` 检查。
- 请遵守 B 站社区规范以及 DeepSeek 的使用条款。因使用本项目造成的账号或内容问题由使用者自行承担。

---

## 12. 参考

- [bilibili-API-collect](https://github.com/socialsisteryi/bilibili-API-collect)：B 站 Web 接口字段、密码登录与评论区操作说明。
- [DeepSeek API 文档](https://api-docs.deepseek.com/)：Chat Completions 请求格式与模型说明。
- [B 站消息中心](https://message.bilibili.com/)：@ 消息接口与回复目标的字段行为参考。

---

## 13. 版本管理

版本号格式为 `MAJOR.MINOR.PATCH`：

- **MAJOR**（第一位）：不兼容的大更新。
- **MINOR**（第二位）：向后兼容的新功能。
- **PATCH**（第三位）：Bug 修复。

当前版本为 `1.2.0`。`VERSION` 与 `pom.xml` 保持同步，发布时使用 Git 标签 `vX.Y.Z`。

递增版本号：

```powershell
# Windows
.\scripts\bump-version.ps1 minor
```

```bash
# Linux
./scripts/bump-version.sh minor
```

完整规范见 [VERSIONING.md](VERSIONING.md)，变更记录见 [CHANGELOG.md](CHANGELOG.md)。
- [Geetest 手动验证器](https://kuresaru.github.io/geetest-validator/)：密码登录时辅助完成极验验证（只输入 gt/challenge，不要输入账号密码）。

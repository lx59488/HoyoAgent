# QQ 入站（P10）· 设计与落地

> 目标：入口从「浏览器 / curl」变成「QQ 里直接聊」。
> 本文记录**为什么是这条路**、**边界在哪**、**Step 0 怎么跑**、**Step 1 怎么写**。
> 最后更新：2026-11-16（Step 0 脚本已写：离线自检 24 项 + 假服务端 E2E 通过，**未接真实协议端实跑**；
> 同日核实 NapCat 已停止，首选协议端改为 LLBot）

---

## 1. 结论

可行。真正卡住的不是 QQ 协议，是**当前进程只有一份米游社凭据**——所以第一版只能是「只回自己私聊」的形态。

## 2. 两条路线，个人主体实际上只剩一条

| | 官方 QQ 机器人（开放平台） | 第三方协议端 + OneBot v11 |
|---|---|---|
| 场景 | 个人主体**只能开 QQ 频道**；群聊仅企业主体；单聊要「定向邀请」 | 私聊 / 群聊都能 |
| 合规 | 合规 | 违反腾讯用户协议，可能风控/封号 |
| 接入 | AppID/AppSecret + 事件推送，Webhook 要 5 秒内 ack | 装 LLBot，扫码登录，开一个 WS 端口 |
| 上手 | 要主体认证、审核、发布 | 半小时 |

腾讯文档把场景写死了：个人开发者「QQ群：暂不开放，消息列表单聊：暂不开放」，只剩频道
（[bot-docs](https://github.com/tencent-connect/bot-docs/blob/645787a45937e5d9c4f0f61afefdffde0f38696e/docs/README.md)、[QQ Bot 接入指南](https://bot.qq.com/wiki/)）。
「在 QQ 里像和一个人聊天」只有第二条路。

**协议端选 LLBot（LuckyLilliaBot，原 LLOneBot/LLTwoBot）**：基于 NTQQ，Windows 有 Desktop 版，
OneBot 11 支持正向 WS / 反向 WS / HTTP / Webhook —— 正好是桥需要的那一种。
go-cqhttp 早已停更；Lagrange 的 V2 仓库已 archive，且明确不再支持 OneBot 11（只留 Milky）。

### 2.1 协议端现状（2026-11-16 核实）

这一层**没有一个是官方支持的，随时可能消失**。当天核实到的事实：

| 协议端 | 状态 |
|---|---|
| **NapCat** | ❌ **已停止**。2026-11-12 03:26 自清空仓库：README 只剩一句「到这里告一段落。」，releases 清空（这两条是我直接查 GitHub API 看到的）；「45 个分支删到 1 个、761 个 tag 删除」出自第三方存档仓库的记录。最后版本 **v4.18.42（2026-10-30）**。旧版仍能跑，但不再修 |
| **LLBot** | ✅ 活跃（最近提交 2026-11-13，V7.9.2）。OneBot 11 四路齐全 |
| Lagrange | ⚠️ V2 已 archive，且明确**不再支持 OneBot 11** |
| go-cqhttp | ❌ 长期停更 |

同期可交叉验证的一件事：AstrBot 在 2026-11-12 移除了 NapCat 的部署指引。
至于同赛道其他项目为何退场，目前只有第三方转述，本文不采信、也不复述。

**结论不变，但预期要降**：这条路是借来的，不是基础设施。
代码侧唯一的对策是——**只依赖 OneBot 11 协议，绝不碰任何协议端的私有 API**。
这样换实现只改 `QQ_ONEBOT_WS_URL` 一行，不动代码。这条对策在 Step 0 里已经成立（见 §5.5）。

**代价必须认清**：第三方协议端在腾讯眼里算外挂软件，会监测异常登录方式。
**用小号**，别放重要社交关系、别绑支付（[同类项目的统一口径](https://www.npmjs.com/package/dsh-qq-onebot)）。

## 3. 架构：OneBot 是「入站适配器」，不是第二套 Agent

```
QQ 用户 ──► LLBot（协议端）──► OneBot v11 WS ──► adapter/onebot ──► AgentChatService ──► AiService + Tools
                                （本机 3001）                              │
                     ◄── send_private_msg ◄──────────────────────────────┘
```

与现有 HTTP 入口的关系：

| | `adapter/http` | `adapter/onebot` |
|---|---|---|
| 会话 id 来源 | `X-Session-Id`（浏览器给 UUID） | `qq:c2c:{QQ号}` / `qq:group:{群号}:{QQ号}` |
| 逻辑 | 同一套 `AgentChatService` | 同一套 |
| 记忆 | `SessionMemoryStore` 按 id 分桶 | 同一套 |

ROADMAP 早就定过这条边界（「`adapter` 只表示入站」），所以 **Agent 层一行不用改**。
`AgentController` 那句注释（"将来 onebot 给 `{platform}:{scope}:{id}`"）现在兑现了。

### 会话 id 规则

- 私聊：`qq:c2c:{userId}` —— 一个人一段上下文
- 群聊：`qq:group:{groupId}:{userId}` —— **不能**用群号当 id，否则全群共用一段对话，甲问完乙接着答
- 日志一律 `mask()`：`SessionMemoryStore` 已经这么做了，适配器也要照做

## 4. 安全：这一条比技术方案重要

`CredentialProvider` 是**进程级单例**（构造时读死 `hoyo.user-id`，没有按 memoryId 解析的入口），
`CheckinTool` 也不接受身份参数。前者是待办（P8），后者是**故意**的
（[mihoyo-auth.md](mihoyo-auth.md) §7：用户身份只能来自框架注入，绝不能来自模型参数）。

后果很直接：**bot 一旦进群，群里任何人都能让它用你的米游社 cookie 签到，并看到你的账号数据。**

所以在 P8 多账号做完之前，闸门只有两条：

1. **白名单**：`QQ_ALLOWLIST` 为空 = 谁都不回（fail closed），不是「谁都能用」。
2. **群聊默认关**：`QQ_GROUP_MODE=off`。打开后仍然只回白名单里的 QQ 号——
   但注意，**回复是发在群里的**，白名单挡住的是「谁能触发」，挡不住「谁能看见」。

多用户 = `@ToolMemoryId → userId → 凭据`，这是 P8 的活，也是这个入口能扩到群的前置。

## 5. Step 0：一次性验证（`scripts/qq-bridge/QQBridge.java`）

单文件、零依赖（JDK 自带 `java.net.http.WebSocket` + 80 行 JSON 解析），删掉这一个文件就干净了。
**现在就可以跑，但它不是产品代码**——功能只有：收文本、调 `/agent`、发文本。

### 5.1 装协议端（LLBot）

1. 下 `LLBot-Desktop-win-x64.zip`，解压到固定目录，双击 `llbot.exe` → 启动。
   **必须由 LLBot 拉起 QQ**——自己先开的 QQ 不会被接管
2. **用小号扫码登录**。私聊场景要求对方（你自己的号）是 bot 账号的**好友**
3. WebUI `http://localhost:3080` → OneBot11 → **正向 WS**：`port=3001`、
   `token`（建议填，同 `QQ_ONEBOT_TOKEN`）、`messageFormat=array`、`reportSelfMessage=false`
4. 确认端口在听：`Get-NetTCPConnection -State Listen -LocalPort 3001`

等价配置在 `bin/llbot/data/config_<QQ号>.json` 的 `ob11.connect[]` 里，`type: "ws"` 那一条。
`onlyLocalhost` 默认就是 `true`，别为了远程访问把它关了。

> **老 NapCat 也能用**：v4.18.42 及更早的版本配置在 WebUI `6099`「网络配置 → WebSocket 服务器」，
> 端口同样 3001、格式 array。桥不用改。但它已停止维护（见 §2.1），QQ 客户端一升级就没人修。
> 另外，别和电脑版 QQ 用同一个号同时登录：两边互挤会表现成「突然不回消息」，手机 QQ 是另一条线。

### 5.2 配置

写进根目录 `.env`（`QQBridge` 和 Spring 读同一份，环境变量优先）：

```properties
QQ_ONEBOT_WS_URL=ws://127.0.0.1:3001
QQ_ONEBOT_TOKEN=          # 与 LLBot 正向 WS 的 token 一致；没设就留空
QQ_AGENT_BASE_URL=http://127.0.0.1:8080
QQ_ALLOWLIST=你的QQ号      # 必填，否则所有消息都被忽略
QQ_GROUP_MODE=off         # 群聊先别开
QQ_REPLY_CHUNK_CHARS=800
QQ_TYPING_HINT=true       # 超过 2.5s 还没答，先回一句"查一下"
```

### 5.3 跑

```powershell
# 终端 1：Agent
.\mvnw.cmd -B -ntp spring-boot:run

# 终端 2：桥（在仓库根目录，.env 才读得到）
java scripts/qq-bridge/QQBridge.java

# 不连协议端，只验纯函数（JSON/切片/白名单）
java scripts/qq-bridge/QQBridge.java --selftest
```

然后用你的主号给机器人小号发「签到了吗」。

### 5.4 排障

| 症状 | 先看这里 |
|---|---|
| 连不上 3001 | WS 服务器没启用 / 端口不同 / token 不一致（日志是 `连接断开`） |
| 连上了但不回 | `QQ_ALLOWLIST` 没填你的号——日志会有「忽略：… 不在白名单」 |
| 回「【服务出错】」 | Agent 没起，或不是 8080 |
| 回了「查一下…」就没下文 | 单轮超过 120 秒；看服务端日志里的 `[TOOL]` / LLM 报错 |
| 突然断了 | 小号被挤下线或被风控；LLBot 那边日志会有掉线（它还能配掉线邮件通知） |
| 私聊发了没反应 | 私聊要求对方是 bot 好友；或 LLBot 没拉起 QQ（自己开的 QQ 不被接管） |

### 5.5 已验证 / 未验证（2026-11-16）

用假服务端（临时脚手架，未提交）跑了两轮，验证的是**桥自己的逻辑**，不是某个协议端的兼容性：

| 场景 | 结果 |
|---|---|
| WS 握手 + 双向收帧（含 echo 配对） | ✅ |
| 不在白名单的私聊 | ✅ 0 帧回出，日志有「忽略」行——这是 fail closed 的证据 |
| 在名单的私聊 | ✅ 带 `X-Session-Id: qq:c2c:10001` 打到 `/agent`，中文查询参数解码正确 |
| 2000 字回复 | ✅ 切成 800/800/400 三条 `send_private_msg` |
| Agent 没起 | ✅ 回「【连不上 Agent】…」；**这个 bug 就是 E2E 抓到的**（原本是 `【出错了】null`，因为 `ConnectException` 的 message 是 null） |
| 服务端关闭连接 | ✅ 3s → 6s 退避重连 |
| `--selftest` | ✅ 24 项 |

**没验证的**：LLBot 真实的帧格式差异、token 鉴权、被挤下线、QQ 侧限流，
以及真实 LLM 一轮的耗时（120s 超时、2.5s「查一下」提示都只在假 agent 下走过）。
这些只能等装好 LLBot 用 P10-5 验。

## 6. Step 1：`adapter/onebot` 要补什么

Step 0 故意省掉的东西，按重要性排：

1. **抽 `AgentChatService`**。现在 `assistant.chat` + `recorder.drain()` + 一次性会话清理
   全在 `AgentController` 里；两个入口都要用，必须提出去，否则 OneBot 只能 loopback HTTP
   （多一次序列化、多一个端口依赖，还把「HTTP 是协议、不是内部调用」搞混了）。
2. **同会话串行**。Step 0 每条消息一个虚拟线程：并发快，但同一会话连发两条时回复可能乱序，
   而记忆窗口按到达顺序写。Step 1 按 sessionId 排队（不同会话仍并行）。
3. **`@ConfigurationProperties(prefix="qq")` + `qq.enabled=false`**。没装协议端也能正常启动，
   不能因为 QQ 没连上就起不来——签到调度还要跑。
4. **出站抽象 `ReplySender`**。将来换官方机器人（或加第二个平台）只换实现，不碰业务。
5. **事件映射单测**：把真实 LLBot 抓的一条事件 JSON 存成测试资源，断言映射结果。
   这比对着日志猜格式可靠得多。

保留不变的三条：会话 id 规则、白名单 fail-closed、适配器只做翻译不解析身份。

## 7. 已知限制（Step 0 明确不做的）

- **只处理文本**：图片/语音/@ 一律不进模型。不是不会，是没做——先把文本闭环跑通
- **无输入提示**：OneBot v11 没有「正在输入」这类动作，只能发文字提示
- **进程级单凭据**：所以只能自用（见 §4）
- **消息不落库**：重启即丢，记忆本来也在内存里
- **公网暴露**：3001 与 WebUI（LLBot 3080，老 NapCat 6099）只绑 `127.0.0.1`。要跨机一定是内网 + token，别开公网

## 8. 合规

- 第三方协议端违反腾讯用户协议，账号可能被风控/封号——**用小号**，风险自担
- 米游社 cookie 的敏感性等同于账号密码（[ROADMAP](ROADMAP.md) P4 的坑记过），
  别让它出现在群聊回复里
- 官方路线（QQ 频道）合规但个人主体受限；要正经上线再做主体认证，不改这套适配器

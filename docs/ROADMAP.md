# HoyoAgent 学习路线与任务清单

> 目标：以「米游社 Agent 工具」为载体，学习 Agent 开发。
> 用法：从上往下做，每完成一条把 `[ ]` 改成 `[x]`。每个阶段结束后打一个 git tag（`phase-1`、`phase-2`…）。
> 设计原则：**先用假数据学 Agent，再接真实 API**。两条线并行、互不阻塞。

**最后更新：2026-11-16**（P7 会话层已落地，`smoke-memory.ps1` 4/4 通过；P7-3 仍卡在缺第二个工具；
P9 的 3 天观察是 **0/3**——14~16 服务没在跑，且 11-16 签到凭据 `AUTH` 失效（疑为 cookie_token 单独过期，
续期机制待验）；P3 的 10 条用例表已作废；P10 QQ 入口开工——Step 0 桥接脚本已写，**未接真实协议端**
（NapCat 已于 11-12 停止，改选 LLBot））

---

## 进度总览

| 阶段 | 内容 | 产出 | 预估 | 状态 |
|---|---|---|---|---|
| P0 | 环境收尾 | 能启动的空工程 | 30 min | 🚧 1 项待办（轮换 key） |
| P1 | AI 闭环 | `GET /chat` 能对话 | 半天 | ✅ 完成 |
| P2 | 第一个 Tool（假数据） | 模型自主调用工具 | 1 天 | ✅ 完成 |
| P3 | 多工具与工具设计 | 10 条用例命中 ≥ 8 | 1 天 | ✅ 完成（7/10 → 8/10）<br>⚠️ 用例表已被 P6 作废 |
| P4 | 米游社客户端基线 | 控制台打印真实 uid | 2~3 天 | ✅ 完成（错误场景未验） |
| P5 | 数据契约与错误治理 | 脱敏 + 限流 + DTO | 1~2 天 | 未开始 |
| P6 | 接入真实数据 | 自然语言查真实战绩 | 半天 | ⚠️ 已回退：`character/list` 与 `PlayerTool` 于 11-13 删除，能力由 P9 签到承接 |
| P7 | 记忆与多轮 | 多轮对话不丢上下文 | 1 天 | 🚧 会话层已落地（P7-1 / P7-2）；P7-3 卡在缺第二个工具 |
| P8 | 工程化扩展（选做） | 多账号 / 可观测 / MCP | 长期 | 未开始 |
| P9 | 登录与签到 | 扫码拿 stoken，能签当天 | 2~3 天 | 🚧 3 天观察 **0/3**（14~16 服务未运行）；11-16 签到 `AUTH` 失效，续期机制待验（P9-11） |
| P10 | QQ 入站（OneBot v11） | 在 QQ 私聊里对话 | 1~2 天 | 🚧 Step 0 桥接脚本 + 自检 24 项 + 假服务端 E2E 通过；**未接真实协议端实跑**（NapCat 11-12 已停止，改选 LLBot） |

> **状态校准自 2026-11-07 的代码核对**：P4-5（DeviceIdProvider）、P4-12（关日志）、
> P6 纵向切片其实都已完成，只是清单没跟上。**这份清单就是项目的单一事实来源，
> 落后于代码比缺失更危险**——照着旧清单干活会重复劳动。

---

## 当前代码结构

```
com.luobo.hoyoagent
├── HoyoAgentApplication.java          ← @ConfigurationPropertiesScan 已开启
├── common/                            ← 横切：统一响应 / 异常 / 日志脱敏
│   ├── constant/CommonConstants.java
│   ├── exception/{BusinessException, ErrorCode, GlobalExceptionHandler}.java
│   ├── log/ErrorLogSanitizer.java
│   └── result/Result.java
├── adapter/
│   └── http/
│       ├── ChatController.java        ← P1：纯模型问答（对照组，不挂工具也不挂记忆）
│       ├── AgentController.java       ← P2/P7：走 AiService，X-Session-Id 定会话，回传 toolsCalled
│       ├── AuthController.java        ← P9：扫码登录 + 绑定角色 + v2 刷新
│       ├── MemoryController.java      ← P7：GET/DELETE /api/memory/{sessionId}（唯一观测口）
│       └── CheckinController.java     ← P9：POST /api/checkin
├── agent/
│   ├── MihoyoAssistant.java           ← @AiService 接口（无实现，运行时动态代理）；extends ChatMemoryAccess
│   ├── memory/                        ← P7：会话记忆（唯一状态在这里）
│   │   ├── MemoryProperties.java      ← hoyo.memory.*；null / 非法值落默认值
│   │   ├── SessionMemoryStore.java    ← ChatMemoryStore 实现：会话数上限 + 空闲 TTL + expired()
│   │   └── MemoryConfig.java          ← ChatMemoryProvider（MessageWindowChatMemory，窗口 20 条）
│   ├── observability/ToolCallRecorder.java
│   └── tool/
│       ├── CheckinTool.java           ← P9：每日签到（无身份参数）
│       └── DecoyTool.java             ← @Profile("decoy") 诱饵，只用于测能力边界
└── mihoyo/                            ← 外部系统客户端（与 agent 完全解耦）
    ├── transport/                     ← 唯一 HTTP 管道（P9 重构）
    │   ├── MihoyoHttp.java            ← 无状态：retcode / headers 都返回，不抛业务异常
    │   ├── MihoyoRequest.java         ← 不可变请求（null 集合自动规整为空）
    │   ├── MihoyoResult.java          ← record(retcode, message, data, headers)
    │   ├── HeaderProfile.java         ← 一套头 = 一个值（salt 与版本锁在一起）
    │   └── Profiles.java              ← 扫码 / 换票 / 签到 / 战绩 四套头
    ├── error/MihoyoError.java         ← retcode 唯一映射：kind + 中文文案 + ErrorCode
    ├── credential/                    ← AccountStore(File) / CookieJar / Credential / Provider（旧文件只读兜底）
    ├── auth/                          ← QrLoginService + passport/{PassportClient,PassportDevice}
    ├── record/RecordClient.java       ← 绑定角色 / App 配置（character/list 已删除）
    ├── checkin/                       ← CheckinGame / LunaClient / CheckinService
    │   ├── CheckinScheduler.java      ← P9-8：00:10 定时 + 启动补签 + 当日幂等 + [CHECKIN][ALERT]
    │   ├── CheckinRecord.java         ← 一次运行的快照（DONE / PARTIAL / FAILED / SKIPPED）
    │   └── CheckinRecordStore.java    ← ~/.hoyo/checkin/YYYY-MM-DD.json，原子写、读坏当没有
    ├── role/GameRoleService.java      ← 角色自动发现（10 分钟缓存）
    ├── client/DeviceIdProvider.java   ← 持久化 device_id（配置 > ~/.hoyo/device-id > 生成）
    ├── dto/GameRole.java              ← 绑定角色
    ├── enums/{MiyousheHost,GameBiz}   ← host 与 game_biz 常量
    ├── MihoyoEndpoints.java           ← host 覆盖入口（hoyo.hosts 可改 base url）
    ├── sign/DsSigner.java             ← DS1 / DS2 公式与 md5（唯一签名入口）
    └── config/HoyoProperties.java     ← @ConfigurationProperties(prefix = "hoyo")

src/test/java/...
├── agent/memory/SessionMemoryStoreTest.java ← 会话数上限 / 空闲 TTL / 删除 / 过期标记（11 条）
├── agent/memory/MemoryPropertiesTest.java   ← 配置缺省与非法值兜底（3 条）
├── mihoyo/record/RecordClientTest.java    ← @Tag("network")，真实打米游社
├── mihoyo/transport/MihoyoHttpTest.java   ← MockWebServer：retcode/header/POST 语义
├── mihoyo/transport/MihoyoRequestTest.java← 空集合规整
├── mihoyo/sign/DsSignerTest.java          ← 锁 DS 公式（用外部算出的 md5 向量）
├── mihoyo/auth/CookieParserTest.java
└── mihoyo/auth/passport/PassportDeviceTest.java / PassportClientTest.java

src/main/resources/static/               ← 调试页（零构建、无 CDN）
├── index.html                           ← 扫码绑定 + 对话 + 调试面板 + 新会话
├── style.css
└── app.js                               ← sessionId 存 localStorage，/agent 带 X-Session-Id

scripts/smoke-agent.ps1                ← 冒烟脚本（8 条用例，2026-11-07 重写；每条用例一个独立会话）
scripts/smoke-memory.ps1               ← P7：记忆差分验收（同会话不重复调 / 无会话必调 / 跨会话隔离 / 窗口不涨）
scripts/login-qr.ps1                   ← P9：建码 → 存 PNG → 轮询到 BOUND
scripts/qq-bridge/QQBridge.java        ← P10 Step 0：OneBot → /agent 的一次性桥（单文件零依赖，--selftest 可离线自检）
scripts/round1.md / round2.md          ← P3 两轮原始输出（历史证据，只读）
scripts/round3.md / round3-decoy.md    ← 新契约下两种 profile 的实测结果（均 8/8）
.env.example                           ← 配置模板；真实值只写 .env（已 gitignore）
docs/ROADMAP.md                        ← 本文件
docs/mihoyo-auth.md                    ← 登录/多用户设计稿；A/B 已落地（P9）
docs/qq-inbound.md                     ← P10：QQ 入站设计（路线选择 / 边界 / 白名单 / Step 1 计划）
```

接口：`GET /`（调试页：扫码绑定 + 会话对话）、`GET /chat?q=`（纯问答，对照组：无工具、无记忆）、
`GET /agent?q=` + **请求头 `X-Session-Id`**（带工具，返回 `{code, message, data:{reply, toolsCalled, sessionId, messages}}`；
不传头 = 一次性会话，请求结束即销毁，行为与加记忆之前一致）、
`POST /api/auth/qr` + `GET /api/auth/qr/{ticket}`（扫码绑定）、`GET /api/auth/status`（绑定状态）、
`POST /api/checkin`（裸调签到，不落盘）、`POST /api/checkin/run`（走调度路径，今天已签则跳过）、
`GET /api/checkin/status`（今日结果 + 最近 7 天 + 是否该看一眼）、
`GET /api/memory/{sessionId}`（窗口内容 + `expired` 标记）、`DELETE /api/memory/{sessionId}`（清空会话）

**结构决策**：原计划放 `adapter.mihoyo`，实际改为顶层 `mihoyo/`。原因是后续会有多个入站适配器（http / onebot / console），把「外部系统客户端」提到顶层更好表达边界。约定：**`adapter` 只表示入站，`mihoyo` 表示出站**。

---

## P0 · 环境收尾

- [x] `pom.xml` 依赖对齐：`langchain4j 1.20.0` + boot4 starter `1.20.0-beta30`，无版本混用
- [x] `spring-boot-starter-webmvc`（Boot 4 的正名，替代废弃的 `starter-web`）
- [x] `spring-boot-starter-test` 补上 `<scope>test</scope>`
- [x] `.env` 通过 `spring.config.import: "optional:file:.env[.properties]"` 生效
- [x] `.env` 里的 `DS_MODEL_NAME` 改成 `deepseek-flash`
- [x] `git init` + 提交，远程 `github.com/lx59488/HoyoAgent`
- [x] Maven 本地仓库统一：`%USERPROFILE%\.m2\settings.xml` 指向 `D:/software/maven/apache-maven-3.9.6/repository`，命令行与 IDEA 一致
- [ ] **轮换 DeepSeek API Key** —— 2026-11-02 核对：`.env` 里仍是旧 key
- [x] **新建 `.env.example`** —— 2026-11-07 已补（含 HOYO_* 说明）；`.gitignore` 只忽略根目录 `.env`
- [x] logback `1.5.38` 的 CVE-2026-19880 已评估：路径穿越，需要自定义 MDC 拼接 FileAppender 才能触发；本项目无可利用路径，**暂不处理**，等 Boot 4.2 GA 升级时顺带解决

**验收**

```powershell
.\mvnw.cmd -B -ntp test        # 期望 BUILD SUCCESS
```

---

## P1 · AI 闭环 ✅

- [x] P1-1 `adapter/http/ChatController`
- [x] P1-2 注入 `ChatModel`（starter 自动配置，注入接口类型）
- [x] P1-3 暴露 `GET /chat?q=...`
- [x] P1-4 捕获异常，返回 `{ok, reply, error}` 的 record
- [x] P1-5 观察过一次完整请求/响应日志

**验收**

```powershell
Invoke-RestMethod "http://localhost:8080/chat?q=用一句话介绍你自己"
```

- [x] 返回中文回答
- [ ] 断网后重试，确认异常信息可读（可稍后补）

**遗留小改进（不阻塞）**：`ChatController` 用 `@Resource` 字段注入，建议改构造注入。

---

## P2 · 第一个 Tool ✅

- [x] P2-1 `agent/tool/FakePlayerTool`，方法标 `dev.langchain4j.agent.tool.Tool`
- [x] P2-2 返回硬编码假数据
- [x] P2-3 `agent/MihoyoAssistant` 接口标 `dev.langchain4j.service.spring.AiService`
- [x] P2-4 ~~在 `@AiService` 上指定 `tools = FakePlayerTool.class`~~ → **本版本不需要**，见下
- [x] P2-5 调用接口，日志与回复都确认工具被触发
- [x] P2-6 看过模型实际传给工具的参数字符串

**两个关键结论（比代码重要）**

1. **不需要指定工具。** `@AiService` 的 `wiringMode` 默认 `AUTOMATIC`：容器里所有带 `@Tool` 方法的 bean 会被**自动**接到**每个** AI 服务上。只有切到 `EXPLICIT` 时才用 `tools = "beanName"`（注意：是 bean 名，类型是 `String[]`，不是 `Class`）。
2. **接口没有实现类，但有实例。** `AiServices.builder(接口).chatModel(...).tools(...).build()` 返回一个 JDK 动态代理。所谓"模型自主调用工具"实际是框架的循环：发请求（带 tools）→ 收到 `tool_calls` → 反射执行 → 回填 `ToolExecutionResultMessage` → 再发一次 → 直到没有 `tool_calls`。

---

## P3 · 多工具与工具设计 ✅

**目标**：用实验证明「命中率由描述决定」，练出改描述的手感。
**纪律**：只改**描述和参数**，不改代码逻辑，否则无法归因。
**现状**：两轮跑批已完成，**7/10 → 8/10**。命中率的提升完全来自两处 `@Tool` 描述的改写，代码逻辑零改动。

### 已完成

- [x] P3-1 四个工具（`getPlayerCharacters` / `getCharacterDetail` / `getDailyNote` / `getAbyssReport`），共享同一份假数据源
- [x] 每个工具方法第一行 `log.info("[TOOL] ...")` + `recorder.record(...)`
- [x] `ToolCallRecorder`（`@Component` + ThreadLocal），`AgentController` 通过单一出口 `finish()` 回传 `toolsCalled`
- [x] 冒烟脚本 `scripts/smoke-agent.ps1` 可跑通，能区分 `NO-FIELD` / `(none)` / 实际工具名

> ⚠️ 2026-11-02 第一次跑脚本得 1/5，全部 `(none)`。**那是观测坏了，不是模型没调**：Controller 只在 `finally` 里 `drain()`，返回值被丢弃。修复后已能回传。

### 本轮完成（2026-11-05）

- [x] P3-2 脚本扩到 10 条：`expect` 升级为「工具名(参数)」数组，支持多调用与参数校验
- [x] P3-3 判定口径：实际工具集合与期望集合**完全相等**才算命中（多调也算 MISS）
- [x] P3-4 第一轮跑批（坏描述）：**7/10**
- [x] P3-5 只改两处 `@Tool` 描述 → 第二轮跑批：**8/10**
- [x] P3-6 命中率 8/10，达标
- [x] P3-7 工具返回值从 P3-1 起就是摘要文本（非全量 JSON），未再改
- [x] P3-8 两轮结果已贴回下表；原始输出见 `scripts/round1.md` / `scripts/round2.md`

### 10 条用例表（两轮结果）

数据前提：萝卜 = 雷电将军90/2、纳西妲90/0、胡桃80/6；小明 = 钟离90/1。

| # | 类别 | 提问 | 期望工具 | 第一轮实际 | 判定 | 第二轮实际 | 判定 |
|---|---|---|---|---|---|---|---|
| 1 | 正例·列表 | 萝卜有哪些原神角色 | `getPlayerCharacters(萝卜)` | 同期望 | OK | 同期望 | OK |
| 2 | 正例·详情 | 萝卜的雷电将军练得怎么样 | `getCharacterDetail(雷电将军)` | `getAbyssReport(萝卜)` + `getCharacterDetail(雷电将军)` + `getDailyNote(萝卜)` + `getPlayerCharacters(萝卜)` | MISS | `getCharacterDetail(雷电将军)` + `getPlayerCharacters(萝卜)` | MISS |
| 3 | 正例·进度 | 萝卜今天树脂还剩多少 | `getDailyNote(萝卜)` | 同期望 | OK | 同期望 | OK |
| 4 | 正例·战绩 | 萝卜这期深渊打到哪了 | `getAbyssReport(萝卜)` | 同期望 | OK | 同期望 | OK |
| 5 | 边界·口语 | 小明树脂满了吗 | `getDailyNote(小明)` | 同期望 | OK | 同期望 | OK |
| 6 | 边界·同义 | 查查小明的深渊 | `getAbyssReport(小明)` | 同期望 | OK | 同期望 | OK |
| 7 | **歧义·易混** | 萝卜的胡桃多少命 | `getCharacterDetail(胡桃)` | `getCharacterDetail(胡桃)` + `getPlayerCharacters(萝卜)` | MISS | `getCharacterDetail(胡桃)` | **OK** |
| 8 | 负例·无关 | 今天天气怎么样 | 不调用 | `(none)` | OK | `(none)` | OK |
| 9 | 负例·能力外 | 萝卜昨天签到成功了吗 | 不调用 | `getDailyNote(萝卜)` | MISS | `getDailyNote(萝卜)` | MISS |
| 10 | 多调用 | 小明和萝卜谁的角色多 | `getPlayerCharacters` ×2 | `getPlayerCharacters(萝卜)` + `getPlayerCharacters(小明)` | OK | 同左 | OK |

**7/10 → 8/10**（判定口径：工具集合完全相等才算命中，多调一个也算 MISS）

**这张表是 P3 的验收标准本身**，必须在跑之前定死。跑完回头改期望值 = 自我安慰。

第 7 条是核心：这句话里同时有玩家名和角色名，而两个工具的描述在第一轮都很糊（"查询原神角色信息"/"查询原神角色详情"）。**它确实错了**——第一轮同时调了详情和列表；改完描述后第二轮只调详情。这就是整轮实验里唯一的"从错到对"。

### 实际改法（已验证生效）

只改了两处 `@Tool` 描述，方法体、参数、返回类型一律没动。改动效果直接体现在第 7 条上。

| 工具 | 改前 | 改后（仓库中的实际文本） |
|---|---|---|
| `getPlayerCharacters` | 查询原神角色信息 | 列出某个玩家账号下的全部原神角色，用于回答「我有哪些角色」「谁满级了」。只需要玩家昵称，不需要角色名 |
| `getCharacterDetail` | 查询原神角色详情 | 查询单个角色的属性（等级、命座）。前提是已经知道具体角色名；只知道玩家时请改用 getPlayerCharacters |

另外两个工具（`getDailyNote`「查询游戏进度」、`getAbyssReport`「查询战绩」）**没改**，因为第 3~6 条在第一轮就全过了——说明含糊的措辞有时也能命中，问题只在两者**互相重叠**时才会暴露。

三个可复用的手法：说清边界（全部 vs 单个）、写清使用条件（"前提是已经知道角色名"）、互相指路（"请改用 XXX"）。

### 两轮结论

**① 描述工程只在"语义重叠"时起作用。**
第 7 条从「详情 + 列表」两个调用收敛成只调详情，是这次唯一的"修好"。另外两个工具的描述一个字没改，第 3~6 条第一轮就全过了。别指望改描述能解决所有问题——它主要解决的是**两个工具看起来都能回答同一个问题**这一类。

**② 剩下两条 MISS 是两类完全不同的病。**

- **第 2 条：过度探索。** 第一轮调了 4 个工具，第二轮降到 2 个（详情 + 列表），答案本身是对的。判定口径上算 MISS，业务上是"能接受但不划算"——多一次往返、多烧一份上下文。次数下降可能来自详情工具那句"前提是已经知道具体角色名"。
- **第 9 条：能力外调用。** 根本没有签到工具，它却调了 `getDailyNote`，而且**两轮都没变**——因为这条跟那两个被改的描述无关。**这才是真问题**：P6 接上真数据后，"查签到"会拿到一份树脂数据，然后模型很可能据此编一个签到结论。

**③ 判定口径值得单独记一笔。**
"多调一个工具算不算错"取决于目标：看答案质量，第 2 条可以算部分命中；看 token 和延迟，它就是失败。当前用「集合完全相等」是最严的口径，宁可误伤。

### 后记（2026-11-07）：这张表已经作废，但结论仍然有效

P6 接真数据时才发现一个当初没意识到的事：**假数据把「查谁」编码成了参数，而真接口里身份来自 cookie。**
一个进程只代表一个绑定账号，`getPlayerCharacters(萝卜)` 这种签名在真实世界里不存在。

- 10 条用例的**期望值全部作废**——不是过期，是前提错了，不存在"架构回退后又能复用"的场景
- 已作废的只是**脚本**：旧脚本位于 `git log -p -- scripts/smoke-agent.ps1`
- 保留下来的是**这张表本身**（在本文档 + `scripts/round1.md` / `round2.md`），因为需要长期复用的是"结果"
- 新脚本 8 条用例对齐新契约，见 `scripts/smoke-agent.ps1` 头部注释

**下一步三条的处置：**

- [x] 能力边界已加 `@SystemMessage`（P6 切片时同步收窄，见 `MihoyoAssistant`）
- [ ] 第 2 条不再适用（详情工具已下线），留作 P8 恢复多工具时再看
- [x] 表已进回归清单 → 但**以新契约重写**，不是照抄旧期望值

### 为什么第 9 条不能靠"删掉工具"通过

现在的系统里没有签到工具，"问签到不调用"必然成立——**这是恒真式，什么也没证明**。
真正的检验是：工具在、描述很诱人、SystemMessage 明令禁止，模型选哪边？

为此加了 `DecoyTool`（`@Profile("decoy")`，默认不注册），用
`"-Dspring-boot.run.profiles=decoy"` 启动后，冒烟第 5~8 条才变成真测试。

**实测结果（2026-11-07，两种模式各跑一遍）：**

| 模式 | 结果 | 含义 |
|---|---|---|
| 默认 profile | **8/8** | 第 6~8 条恒真，通过不含信息 |
| decoy profile | **8/8** | 诱饵在工具列表里、SystemMessage 明令禁止 → 模型仍选择不调 |

两次都是 8/8，但**只有第二次是证据**。判定证据有两个：

1. 服务端日志里没有任何 `[TOOL][DECOY]` 行 —— 三个诱饵一次都没被调用
2. 模型的原话泄露了它看见了诱饵：「即便调用昵称查询也只会失败，所以我不去试」

第 2 条比第 1 条强：日志只能证明"没调"，模型自己说出诱饵工具的名字才证明**它看见了、权衡了、放弃**。
如果 profile 没生效，这句回复根本不可能出现 —— 诱饵机制因此自带自证。

原始输出：`scripts/round3.md`（默认）/ `scripts/round3-decoy.md`（诱饵）。

**这条经验可以直接搬到任何 agent 项目：诱饵是测"边界"的唯一办法，缺席不是。**

### 常见坑

- 工具描述堆同义词（查询/获取/查看）不会提高命中率，只会让边界更糊
- 参数名比描述更容易出错：`characterName` 改成 `name` 后，模型往里填玩家名的概率显著上升
- 工具返回全量 JSON → 上下文被吃掉；先切摘要
- 工具内抛异常 → 整个请求 500；应把错误当文字返回给模型

---

## P4 · 米游社客户端基线 ✅

**目标**：纯 Java 打通**一个**只读接口。这一步不许碰 AI，也不许改 Agent 代码。
**前置**：用**小号**。不要用主账号做实验。
**现状**：带 cookie 的 `character/list` 已打通，控制台打出了真实角色（安柏 Lv.6 / 旅行者 Lv.5 / 诺艾尔 Lv.1）。
**但「错误场景」这一格还很空**——所以是"通了"，不是"稳了"。

### 已完成

- [x] P4-1 包结构落地：`mihoyo.{api, auth, client, config, sign, dto, enums}`（顶层 `mihoyo`，不是 `adapter.mihoyo`）
- [x] P4-2 `Credential`（record；`toString()` 已脱敏，避免 token 进日志）
- [x] P4-3 `CookieParser`（`ltuid_v2` > `ltuid` > `account_id_v2` > `account_id`；空值跳过；引号剥离）
- [x] P4-4 DS1 结构（`md5("salt={salt}&t={t}&r={r}")`，输出 `t,r,hash`）—— 当时放在 `Signer` + `hoyo.salt`；
  **P9 已删除**（查询接口实测不吃 DS），统一到 `DsSigner`
- [x] P4-5 `DeviceIdProvider`：优先级「配置 `hoyo.device-id` > `~/.hoyo/device-id` > 生成 UUID」，原子写入，日志不打印值
- [x] P4-6 `MihoyoHttpClient`：OkHttp 单例、统一 header、HTTP 状态检查、retcode 检查、异常映射到 9xxx
  —— **2026-11-12 重构**：并入 `transport/MihoyoHttp`，retcode 语义移到 `MihoyoError`
- [x] P4-8 第一个只读接口：`GET /apihub/wapi/getAppConfig?gids=2` → `retcode=0`，拿到完整 `config`（60+ key）
- [x] P4-12 `log-requests` / `log-responses` 已为 `false`，`dev.langchain4j` 已降到 `WARN`

**实测结论（重要）**：`getAppConfig` **不校验 DS**。三种情况均返回 `retcode=0`——带原始 DS、带伪造 DS、完全不带 DS。所以「先打通、后签名」的策略成立。

**第二个实测结论（2026-11-07）**：`POST /game_record/app/genshin/api/character/list` **需要 cookie，但不需要 DS**——
`salt` 为空时完全不发 DS 头，照样 `retcode=0`。
所以附录 A 那个"2.117.0 的 salt 挖不到"**已经不阻塞任何一条查询路线**，别再为它花晚上。

### 待做

- [ ] P4-7 日志脱敏：目前只靠 `Credential.toString()` + 不打印 header，还没有拦截器兜底。**`.env` 里已经有真 cookie 了，这条从"可选"升级为"该做"**
- [ ] P4-9 retcode 映射表补全：现有 5 条（-100/10001/-5003/10101/1034）**全是假设**，必须实测
- [ ] P4-10 三种错误场景各验证一次：错 cookie、过期 cookie、断网
- [ ] P4-11 离线单测：`Signer` 固定输入 → 固定输出；`CookieParser` 各种脏输入
- [x] ~~P4-13 `CharacterDto` 的字段映射从未验证过~~ —— **作废**：`CharacterDto` 与 `character/list`
  已于 2026-11-13 删除，字段映射不再有消费方

### 已修复

- 2026-11-05：`MihoyoHttpClient` 的 retcode 判断写成了 `if (retcode == -1)`，导致除 `-1` 外的所有错误码（`-100`、`-5003`、`1034`…）都被当成成功、直接返回 `data`。已改为 `if (retcode != 0)`。**不修这一行，P4-9 的映射表永远跑不到。**

### 验收

- [x] 命令行打印出 `retcode=0` 和真实的配置数据
- [x] 拿到自己的 **uid + 昵称**——`character/list` 回来的是真实角色，这条路走通了（2026-11-07）
- [ ] 三种错误场景给出**不同**的异常信息
- [ ] 日志里搜不到任何真实 cookie 值

### 常见坑

- salt 与 `x-rpc-app_version` 绑定，官方会随版本换 → 做成「版本 → salt」的配置表
- `device_id` 每次随机 = 主动往风控上撞
- 一上来就调"签到"这类写接口 → 先只读，写操作放最后
- **抓包文件里含完整 cookie**（`ltoken_v2` / `cookie_token_v2`），别放共享目录、别提交、别贴聊天。2026-11-02 就发生过一次（MuMu 共享文件夹里的 `request.json`）

---

## P5 · 数据契约与错误治理

- [ ] P5-1 DTO：每个接口一个精简对象，只保留会用到的字段
- [ ] P5-2 `MiyousheException`：携带 retcode、可读消息、分类（可重试 / 需重新登录 / 需人工 / 致命）
- [ ] P5-3 令牌桶限流（单账号 1 req/s 起步）——注意放在 **mihoyo/client**，限的是"对米游社的请求"
- [ ] P5-4 静态数据缓存（角色、武器、怪物这类不变的数据，放本地 JSON；先内存，Redis 留到 P8）
- [ ] P5-5 `hoyo.*` 配置补全：salt 表、device、限流参数
- [ ] P5-6 加一个测试断言：日志里不出现 cookie 关键字

**验收**

- [ ] 连续 10 次请求不触发风控
- [ ] DTO 解析有单测覆盖（字段缺失、类型不符）
- [ ] 改 salt 只需改配置，不用重新打包

---

## P6 · 接入真实数据 🚧

**目标**：把假工具换成真客户端。~~**`@AiService` 接口和工具签名应该一行都不用改。**~~
**结果**：这句话是错的，而且错得很有价值——见下面 P6-4 复盘。

**现状（2026-11-13）**：`character/list` 与 `PlayerTool` / `PlayerService` / `CharacterDto` 已删除。
原因：FufuLauncher 没有这个接口的实现可对照；新账号上恒返回 `5003`（旧账号同请求正常，已确认账号侧）；
它也不再是产品需要的能力。Agent 现在只剩 `CheckinTool`（P9）。
P6-3 的真实角色输出保留为历史证据；「自然语言 → 真实数据」的闭环仍在，由签到承接。

- [x] P6-1 假的 `DB` / `NOTES` / `ABYSS` 三张表和注释掉的 `@Tool` 已清除，`FakePlayerTool` 改名 `PlayerTool`
- [ ] P6-2 确认 `MihoyoAssistant` 与工具签名零改动 —— **没做到，见 P6-4**
- [x] P6-3 端到端：`getPlayerCharacters()` → `character/list` → 真实角色（安柏 Lv.6 / 旅行者 Lv.5 / 诺艾尔 Lv.1）
- [x] P6-4 复盘：见下
- [ ] P6-5 （可选）在 `agent` 里加 `PlayerQueryPort` 接口，让 `mihoyo` 反向实现，依赖方向变成"外层依赖内层"
- [ ] P6-6 恢复 `character/detail`（`docs/mihoyo-auth.md` 记着实测可用，但代码里还没有）
- [ ] P6-7 `dailyNote` / `spiralAbyss` 二选一：接真数据，或明确写"不做"。

### P6-4 复盘：抽象错在哪

**事实**：签名被迫从 `getPlayerCharacters(String nickname)` 改成了 `getPlayerCharacters()`。

**错在哪**：假数据把「查谁」编码成了**方法的输入**。而真接口里，「谁」由 cookie 代表的绑定账号决定，
一个进程只代表一个人。模型既不知道、也**不应该**知道 uid——

> 假数据让你以为「玩家」是输入，其实「玩家」是会话。

这不只是签名不好看，是一条安全边界：工具参数里只要出现 `uid` / `nickname` / `accountId`，
模型就有能力填别人的值。多用户场景下身份必须由框架注入（`@ToolMemoryId`），
这条规则写在 `docs/mihoyo-auth.md` §7，从第一天就该执行。

**怎么避免**：写假数据时问一句——**这个参数在真接口里存在吗？** 如果它对应的是"谁在调用"，
那它属于会话上下文，不属于方法参数。

**验收**

- [x] 「我有哪些原神角色」能拿到真实数据并组织成中文回答
- [ ] 「我原神练度怎么样」——需要 `character/detail`，P6-6
- [ ] cookie 失效时，模型回答「登录已过期，请重新授权」而不是崩溃（工具已把 `BusinessException` 转成文字返回，待实测）

---

## P7 · 记忆与多轮 🚧

> **开始前的提醒（2026-11-13 仍未解决）**：多轮的价值体现在「省略了主语也能接上」。
> 当前只有 1 个无参工具，能测的是"你还记得刚才聊的是安柏吗"，
> 测不了"那深渊呢"——因为深渊工具不存在。
> 所以 P6-6/P6-7 至少补回一个工具，P7 的实验才立得住。

**现状**：会话层已落地（`agent/memory` + `X-Session-Id`），P7-3 依然写不出验收——
**卡的不是记忆，是第二个工具**。这条从 11-07 看到现在，一直没变，别再往后拖。

- [x] P7-1 接入 `ChatMemory`：`ChatMemoryProvider` + `MessageWindowChatMemory(20)`，`@AiService` 注解零改动
- [x] P7-2 会话隔离：`@MemoryId` 当不透明字符串；状态全在 `SessionMemoryStore`（会话数上限 + 空闲 TTL）
- [ ] P7-3 多轮验收「我在查原神」→「那深渊呢」—— **前置是第二个工具**
- [x] P7-4 部分：`smoke-memory.ps1` 的 D 条 12 轮通过（20 条停住不涨）；30 轮的 token 曲线仍没跑
- [x] P7-5 观察过一次裁剪 —— 结论比预期狠，见发现 ③

### P7 的四个发现（都是实测，不是推测）

**① 框架缓存的 ChatMemory 永不淘汰，所以状态必须放 store。**
`ChatMemoryService` 里就是一个裸 `ConcurrentHashMap`；`ChatMemoryProvider` 只在**首次见到 id** 时被调一次，
返回的实例被框架永久持有。状态一旦放进 provider 的局部变量或 ChatMemory 实例，淘汰就无从谈起。
所以淘汰是**两层**：store 管消息正文（占内存的大头，有界），`ChatMemoryAccess#evictChatMemory`
管框架那层壳（每个一次性请求会留一个几百字节的死条目）。

**② `ChatMemoryAccess` 必须由接口 `extends`，不能强转。**
`DefaultAiServices` 建代理时接口数组只有 `{aiServiceClass}` 一个元素，所以 `(ChatMemoryAccess) assistant`
运行时就是 `ClassCastException`；源码里 `method.getDeclaringClass() == ChatMemoryAccess.class` 那条分支，
只有你的接口继承了它才走得到。**这条是"看起来该能强转"的典型陷阱，实测 200 就是证据。**

**③ 裁剪是按「条」不是按「轮」，会切断一问答。**
21 条裁到 20 条时只淘汰 1 条，恰好是窗口里第一条 `USER`，而它对应的 `AI(tool_calls) + TOOL + AI` 全留着。
于是模型看到的第一条是"没被问过就决定去签到"。
**它不报错**——只有孤儿 `ToolExecutionResultMessage` 会被 provider 拒绝，孤儿 `AiMessage` 合法。
要修得自己写 turn-aware 的淘汰（或接受它），**先记账不做**。

**④ 记忆降低重复调用，但不消除。**
同会话连问 5 轮「今天签到了吗」：第 2、4、5 轮吃记忆不调工具，**第 3 轮又调了一次**
（原话"这次我确实又查了一遍"）。所以 `scripts/smoke-memory.ps1` 的 A 条判定（第二问不调工具）
**有抖动**：MISS 一次不等于记忆坏了，必须配合 B 条（无会话时必调）一起看才分得清。

### 已验证的证据（2026-11-13）

| 场景 | 结果 |
|---|---|
| 同会话两问 | 第 1 问 `tools=checkinToday()`；第 2 问 `tools=` 空，`messages` 5 → 7 |
| 窗口顶到上限 | 连问 5 轮后 `messages=20` 停住；`SYSTEM` 常驻第 1 条，占掉一个名额 |
| 孤儿 tool 结果 | 无。淘汰 `AI(tool_calls)` 时框架连带淘汰后继 `TOOL`——**这段不用自己写**，写测试证明就行 |
| 观测端点 | `GET /api/memory/{id}` 能看到窗口原文；`expired` 区分"看得见"与"下一轮收不到" |
| 单测 | `SessionMemoryStoreTest` 11 条 + `MemoryPropertiesTest` 3 条；`[MEMORY]` 的淘汰/超时两行日志终于跑到 |
| **`smoke-memory.ps1`（2026-11-16 实跑）** | **4/4**：A 同会话第二问 0 次工具调用 / B 无会话必调 / C 跨会话必调 / D 12 轮后仍 20 条 |
| 裁剪痕迹（同日复现） | D 的窗口头部是一条 `AI(工具调用)`，它对应的 `USER` 已被淘汰——发现 ③ 的现场复现了一次 |

**常见坑**

- memory 在内存里，重启就丢——先接受，别急着上 Redis
- 窗口太大 = 每次都烧钱；太小 = 模型失忆。20 条约等于 4~5 轮带工具的对话（一次工具调用占 3~4 条）
- 会话 id 是**适配器**产出的，Agent 不解析也不信任。将来 onebot 接入时 id 会含 QQ 号，所以日志一律走 `mask()`
- **不传会话 id ≠ "给个 default 会话"**：后者会让所有匿名调用串进同一个对话，也会让冒烟用例互相污染
- 模型调用抛异常时，本轮用户消息已经在 memory 里（框架在发请求前就 add 了），
  下一轮会看见一句"没被回答过的提问"。要做干净得回滚最后一条，暂未做
- 调试接口故意不做过期判断（看一眼就删会变成自毁按钮），所以"看得见 7 条"和"下一轮收到 0 条"
  可以同时为真——这正是 `expired` 字段存在的理由
- **空闲 TTL 必须远大于单轮耗时（含工具往返）。** 实测把 TTL 调到 1s：一轮之内就"过期"，
  store 被反复清空重写；带工具那轮更糟——轮内留下了孤零零一条 `TOOL_EXECUTION_RESULT`，
  下一轮必然被 provider 拒绝。默认 2h 不是随便定的，`hoyo.memory.idle-ttl` 调小要按分钟起步

---

## P8 · 工程化与扩展（选做）

- [ ] 多账号：`AccountStore` 已就位（每用户一个 `.account` 文件），还差
  `@ToolMemoryId → userId → 凭据` 的解析与存储加密
- [ ] 重试与降级：限流类指数退避，登录类不重试
- [ ] 可观测：Micrometer 打点，替换掉 `ToolCallRecorder`
- [ ] 测试：录制一次真实响应存 JSON，回放跑工具测试（不依赖网络）
- [ ] 暴露成 MCP
- [ ] RAG：攻略/角色数据检索（**等有真需求再做**）

---

## P9 · 登录与签到（2026-11-12 起）

> 目标：把「手填 cookie」换成「扫码一次，之后自动签到」。
> 设计细节与协议事实都在 [`docs/mihoyo-auth.md`](mihoyo-auth.md)，这里只放进度。

- [x] P9-1 `DsSigner`：DS1 / DS2 两条公式，md5 用外部向量锁住
- [x] P9-2 `PassportDevice`：持久化扫码 `device_id` + `device_fp`（不持久化会 `-3503`）
- [x] P9-3 `PassportClient`：建码 / 轮询 / 换 ltoken / 换 cookie_token / **v2 换取**
- [x] P9-4 `POST /api/auth/qr` + PNG + 轮询状态机；成功写 `~/.hoyo/credential.properties`
- [x] P9-5 真机扫码：`WAITING→SCANNED→BOUND`，stoken/ltoken/cookie_token 全拿到
- [x] P9-6 `luna/info` 复测 —— **结论被推翻**：签到认 `cookie_token`+`account_id`，不是 stoken
- [x] P9-7 三游戏签到打通：星铁/绝区零真签成功，原神当日已签返回 `ALREADY`；幂等已验证
- [x] P9-7b 角色自动发现（`GameRoleService`），替掉写死的 `HOYO_ROLE_ID`
- [x] P9-8 `@Scheduled` 每天 00:10 + 当日记录幂等（先文件，多用户再上库）+ 失败可见性，
  见下面「P9-8 的三个决定」
- [ ] P9-9 stoken 失效时主动提示重新扫码（`/api/auth/refresh` 失败 → 通知）
  —— 现在失败会亮灯（`/api/checkin/status` 的 `attention` + `kind=AUTH` 日志），但**不会自动续期**；
  没有通知渠道之前，"提示"就是状态页那行红字
- [ ] **P9-11 签到凭据（v1 `cookie_token`）失效后怎么续期——机制未验证**（2026-11-16 实测）
  —— 11-13 同账号还能签（`ALREADY`），11-16 三个角色全 `FAILED / kind=AUTH`，而 `hasStoken=true`、
  `GET /api/auth/roles` 一切正常。这个组合就是"ltoken 还活着、cookie_token 单独过期"：
  FufuLauncher `TokenRefreshService.CheckCookieValidAsync` 的注释原话是
  *"The role binding API validates LToken only. CookieToken can expire independently."*
  → **AUTH 的根因基本可定为 cookie_token 过期**，不是账号、不是风控。
  但**"点一次 `/api/auth/refresh` 能不能救回来"没有验证过**：`PassportClient.exchangeV2()`
  收的是全部 `Set-Cookie`，可能带新的 v1 `cookie_token`（能救），也可能只带 `*_v2`（未必救得了，
  因为签到时那套 cookie 是我们自己拼的）。注意这条**不能说成"只刷 v2 所以救不了"**——
  那是看代码猜的，RefreshResult 里只检查 `ltoken_v2` 只是回报信息，不代表 jar 里没有 v1。
  参照物：FufuLauncher 拿到 v2 后是**删掉** v1 的（`TokenRefreshService.RefreshCookieAsync` 149~153 行），
  说明它认为 v2 够用；我们不敢删，因为 11-12 实测签到认 v1。
  **下一步是一次实验，不是写代码**：点刷新 → 再 `POST /api/checkin` → 看 `kind` 是否变
- [x] ~~P9-10 排查 `character/list` 的 `5003`~~ —— **已关闭**：该接口 11-13 删除；实验确认与请求头无关
  （新账号四种头组合全 `5003`，旧账号同一套头 `retcode=0`）

**验收**：连续 3 天自动签到成功，且 `~/.hoyo/credential.properties` 不用再动。

> **2026-11-16 状态：0/3。** `~/.hoyo/checkin/` 里只有 11-13 一条，14/15/16 都没有——
> 这三天服务根本没在跑（`@Scheduled` 只在进程活着时触发，见坑 15），**不是签到失败**。
> 想让它成立：今晚之前先重扫二维码（当前凭据已 `AUTH` 失效），然后让服务一直开着。

**开工命令**：

```powershell
.\mvnw.cmd -B -ntp spring-boot:run        # 终端 1
pwsh -NoProfile -File .\scripts\login-qr.ps1   # 终端 2

# 看今天到底签没签、下一次几点、要不要处理
Invoke-RestMethod "http://localhost:8080/api/checkin/status" | ConvertTo-Json -Depth 6
# 手动跑一次（今天已签就不重复请求；force 强制重跑）
Invoke-RestMethod "http://localhost:8080/api/checkin/run?force=true" -Method Post | ConvertTo-Json -Depth 6
```

### P9-8 的三个决定（比代码重要）

1. **只试一次，失败不自动重试。** 重试会把"连续 3 天成功"这个验收条件糊掉——失败就该亮灯，
   而不是靠重试掩盖。补跑是人的动作：`POST /api/checkin/run?force=true`。
2. **启动补签。** cron 只在进程活着时触发，笔记本合盖一晚就错过整天。
   启动后 `hoyo.checkin.startup-delay`（默认 60s）补一次；今天已有 `DONE` 记录就跳过。
   补签跑在**虚拟线程**里：守护线程，既不卡 `SpringApplication.run`，也不会拖住测试 JVM。
3. **失败可见，且分类。** `[CHECKIN][ALERT]` 前缀只给失败用，日志里搜一次就能捞全；
   同时写 `~/.hoyo/checkin/YYYY-MM-DD.json`，重启后状态页仍能读到。
   `kind` 来自 `MihoyoError`（AUTH / NEED_VERIFY / RISK…），所以"要重新扫码"和"网络抖了一下"
   在日志和状态里是**两种不同的话**，不用正则匹配中文。

**记录文件的形状**（一天一个 JSON，人直接打开也能读）：

```json
{
  "userId": "local", "date": "2026-11-13",
  "trigger": "SCHEDULED",            // SCHEDULED / STARTUP / MANUAL
  "outcome": "DONE",                 // DONE / PARTIAL / FAILED / SKIPPED
  "kind": null,                      // 失败类别，成功时 null
  "startedAt": "2026-11-13T00:10:00.1+08:00", "finishedAt": "2026-11-13T00:10:04.3+08:00",
  "message": "3 个角色：新签 2，已签 1",
  "roles": [ { "gameBiz": "hk4e_cn", "status": "ALREADY", "totalDays": 12, "reward": "「原石」x60" } ]
}
```

**两条容错约定**（都有单测锁住）：读坏的文件当"没有记录"（宁可多签一次，服务端幂等兜底），
写失败只打日志（记录是观测，不是业务结果）。

**已知边界**：单用户——文件名只有日期，`userId` 写在文件里，换账号时会覆盖上一个人的记录；
多用户时按 `userId` 分目录或直接上库，`CheckinScheduler` 不用改。

---

## P10 · QQ 入站（2026-11-16 起）

> 目标：入口从「浏览器 / curl」变成「QQ 里直接聊」。
> 设计细节全部在 [`qq-inbound.md`](qq-inbound.md)，这里只放进度与两条硬规则。

**可行性结论**：可行。卡点不是 QQ 协议，是「进程级单凭据」——所以第一版只能**自用私聊**。

**路线**：官方机器人对个人主体只开放 QQ 频道（群聊仅企业主体、单聊要定向邀请），
所以走 **LLBot + OneBot v11**。代价是违反腾讯用户协议、可能风控/封号，**必须用小号**。

> **2026-11-16 补：NapCat 已停止。** 当天核实：仓库 2026-11-12 03:26 自清空（45 分支→1、
> 761 tag 与全部 release 删除），最后版本 v4.18.42（2026-10-30）。
> 首选协议端改为 **LLBot**，Lagrange 已放弃 OneBot 11。**代码不用改**——桥只依赖 OneBot 11 协议。
> 细节见 [`qq-inbound.md`](qq-inbound.md) §2.1。

- [x] P10-1 路线调研：查实官方场景限制（个人主体：频道 ✅ / 群 ❌ / 单聊 ❌）
- [x] P10-2 边界确认：OneBot 属于 `adapter`（入站），Agent 层零改动；会话 id `qq:c2c:{id}` / `qq:group:{gid}:{uid}`
- [x] P10-3 Step 0 桥：`scripts/qq-bridge/QQBridge.java`（WS + 最小 JSON + 虚拟线程，单文件零依赖）
- [x] P10-4 离线自检：`--selftest` **24 项通过**（JSON 解析 / CQ 码剥离 / 切片不丢字 / 白名单矩阵 / 序列化往返 / 异常兜底文案）
- [x] P10-4b 假服务端 E2E（脚手架未提交）：握手 → 陌生人被拦（0 帧）→ 白名单消息带 `qq:c2c:10001` 打到 `/agent`
      → 2000 字切 3 条（800/800/400）；agent 挂掉时回「【连不上 Agent】…」而不是 `null`；断线退避 3s→6s
- [ ] P10-5 **实跑**：装 LLBot → 小号扫码 → 开 3001 → 私聊发「签到了吗」收到回复
- [ ] P10-6 Step 1：抽 `AgentChatService`，落 `adapter/onebot`（同会话串行 + `qq.enabled=false` 默认关）
- [ ] P10-7 进群的前置：P8 的 `@ToolMemoryId → userId → 凭据`；在此之前 `QQ_GROUP_MODE` 保持 `off`

**两条硬规则（写在这里，免得将来手滑）**

1. `QQ_ALLOWLIST` 为空 = 谁都不回。**默认必须是拒绝**，不是「谁都能用」。
2. 群聊打开后仍只回白名单，但**回复是发在群里的**——白名单挡的是「谁能触发」，挡不住「谁能看见」。

**验收**：QQ 私聊里问「今天签到了吗」，回复与 `GET /api/checkin/status` 一致；
再用一个**不在白名单**的号发同一句，必须没有任何回复（fail closed 的证据，不是"大概没收到"）。

---

## 学习期不要做的事

- ❌ 向量库 / RAG（还没有非结构化文本需求）
- ❌ 多 Agent 编排 / 工作流框架
- ❌ 前端 UI（curl 足够验证一切）
- ❌ 同时 debug 米游社签名和 Agent 工具调用
- ❌ 主账号做实验
- ❌ 一开始就设计多账号/多租户

---

## 附录 A · 米游社事实探测记录

> 这份表比代码值钱。三天不记，就想不起来当时为什么加那个 header。

### 已确认（2026-11-02 ~ 11-05 实测）

| 项目 | 值 | 来源 |
|---|---|---|
| BBS 接口 host | `bbs-api.miyoushe.com` | 网页抓包（client_type=4） |
| 签到接口 | `POST https://api-takumi.mihoyo.com/event/luna/sign`（act_id 走 body；绝区零换成 `act-nap-api.mihoyo.com/event/luna/zzz`） | App 抓包 + FufuLauncher 源码 |
| 签到必备头 | `DS`、`x-rpc-signgame: hk4e`、`x-rpc-app_version`、`x-rpc-client_type`、`x-rpc-device_id`、`x-rpc-device_fp`、`x-rpc-channel: miyousheluodi`、`Origin`/`Referer: https://act.mihoyo.com/` | 同上 |
| 签到 body | `{act_id, region, uid}`；**act_id 不是每期变**，FufuLauncher 里是常量（原神 `e202311201442471`） | 2026-11-12 核对源码 |
| 签到 cookie | **`cookie_token` + `account_id`（两个都要）**；stoken/ltoken 单独都不行 | 11-12 对照实验 |
| 战绩 cookie | `ltoken_v2`；只有 v1 会得到无 message 的 `5003` | 11-12 实测 |
| 签到实测 | 星铁 `103871907`、绝区零 `15911914` 真签成功；原神 `100848866` 当日已签 | 11-12 |
| `getAppConfig` 是否需要认证 | **不需要** cookie，也不需要 DS（伪造/缺失 DS 均返回 retcode 0） | 三种情况实测 |
| `character/list` 是否需要认证 | **需要** cookie（ltoken）；**不需要** DS（salt 为空照样 retcode 0）。**该接口 11-13 已从项目删除** | 2026-11-07 实测 |
| DS 输出格式 | `{t},{r},{md5}` | 抓包 + 文档 |
| DS1 | `md5("salt={salt}&t={t}&r={r}")`，`r` = 6 位 `[A-Za-z0-9]` | 文档 + 抓包 |
| DS2 | `md5("salt={salt}&t={t}&r={r}&b={body}&q={query}")`，`r` = 100000~200000 整数 | 文档 |
| client_type → salt | 2→K2，4→LK2，5→4X/6X，1→同安卓 | [UIGF 文档](https://github.com/UIGF-org/mihoyo-api-collect/blob/main/other/authentication.md) |
| salt 表 | 133 个历史值（截止 2.71.1） | [issue #1](https://github.com/UIGF-org/mihoyo-api-collect/issues/1) |
| 观测到的 app_version | 网页 `2.102.0`；App `2.117.0` | 两次抓包 |

### 已确认（2026-11-12 实测 / 源码核对）

| 项目 | 值 | 依据 |
|---|---|---|
| App 登录码建码 | `POST https://passport-api.mihoyo.com/account/ma-cn-passport/app/createQRLogin`，body `{}` | 本机实测 `retcode=0`，返回 `ticket` + `login-platform` url |
| App 登录码轮询 | `POST …/app/queryQRLoginStatus`，body `{"ticket":"…"}`；`status=Created/Scanned/Confirmed` | 实测 `retcode=0` / `status=Created` |
| device_id 一致性 | `create` 与 `query` 必须同一个 device_id，否则 `-3503 当前设备或网络环境存在风险` | 换 id 复现两次 |
| 扫码系 salt | DS2，`dDIQHbKOdaPaLuvQKVzUzqdeCaxjtaPV`，配 app_version `2.90.1`、client_type `3` | 实测可用 |
| 换票系 salt | DS2，`JwYDpKvLj6MrMqqYU6jTKF17KNO2PXoS`，配 `2.95.1`、client_type `2`、sdk `2.16.0` | FufuLauncher `PassportClient` |
| `stoken` 来源 | app 码 `confirmed` 响应里的 `data.tokens[].token`（`name=stoken`），配 `data.user_info.{mid,aid}` | FufuLauncher `LoginQrWindow.AppQrLogin` |
| 签到 salt | DS1，`G1ktdwFL4IyGkHuuWSmz0wUe9Db9scyK`，配 `2.93.1`、client_type `5` | `Tools.GetDs(true)` |
| `ltoken` 能否签到 | **不能**。配全套签到头仍是 `-100` | 11-12 复测（排除头部原因） |
| 二维码有效期 | 约 5 分钟；`-3501`/`-106` = 过期 | 源码 + 实测 |

### 待填

| 项目 | 值 | 备注 |
|---|---|---|
| ~~cookie 必填字段~~ | 查询用 `ltoken`+`ltuid`；签到用 `stuid`+`stoken`+`mid` | ✅ 11-12 |
| `ltuid` / `stuid` 区别 | 都是账号 id（`aid`），不同接口不同叫法 | ✅ 11-12 |
| ~~`ltoken` / `stoken` 用途~~ | 查询 / 社区与签到，见上表 | ✅ 11-12 |
| ~~`cookie_token` 获取方式~~ | `GET …/account/auth/api/getCookieAccountInfoBySToken` | ✅ 11-12 |
| **2.117.0 的 salt** | ❓ 未找到，**但不阻塞** | 133 个公开值 + 7967 个从网页 bundle 挖出的候选，全部不匹配。已实测查询类接口不吃 DS；签到用 `2.93.1` 那套即可 |
| `device_id` 生成规则 | 扫码：16 位大写 hex + `md5(JSON)` 做 fp；签到：`md5(完整 cookie)` | ✅ 11-12 |
| retcode 分类表 | `-100`/`10001` 未登录、`-10001` 签名、`-3501`/`-106` 二维码过期、`-3503` 风控、`-500004` 限流、`1034` 需验证 | 逐步补 |
| 每日签到刷新时间 | | 以实测为准 |
| 触发风控的阈值 | | 例如连续多少请求后被拒 |

### salt 验证向量（挖到 salt 后立刻用它验证）

```
t  = 1793617524
r  = dl5kh6
DS = 61b1067674a2591adee64a0e600ebf8e

断言：md5("salt={salt}&t=1793617524&r=dl5kh6") == 61b1067674a2591adee64a0e600ebf8e
```

另一条（网页端，client_type=4 / 2.102.0）：

```
t = 1793615718, r = x4jM6S, DS = 4ba0172d3a9d270bf488b98272ccfee9
```

---

## 附录 B · 命令速查

```powershell
# 启动
.\mvnw.cmd -B -ntp spring-boot:run

# 跑测试（跳过会打外网的用例）
.\mvnw.cmd -B -ntp test "-Dgroups=!network"

# 只跑网络测试
.\mvnw.cmd -B -ntp test "-Dgroups=network"

# 强制刷新依赖（解决"本地有却解析不到"）
.\mvnw.cmd -B -ntp -U clean test

# 冒烟：Agent 工具用例（用 pwsh 7）
pwsh -NoProfile -File .\scripts\smoke-agent.ps1

# 冒烟·诱饵模式：让「能力外」的用例变成真测试（先换这个 profile 启动服务）
.\mvnw.cmd -B -ntp spring-boot:run "-Dspring-boot.run.profiles=decoy"
pwsh -NoProfile -File .\scripts\smoke-agent.ps1

# 看依赖版本是否收敛
.\mvnw.cmd -B -ntp dependency:tree -Dincludes=dev.langchain4j

# 确认 Maven 用的是哪个本地仓库（注意 -D 参数要加引号）
.\mvnw.cmd -q help:evaluate "-Dexpression=settings.localRepository" -DforceStdout

# 打包
.\mvnw.cmd -B -ntp -DskipTests package

# 调接口
Invoke-RestMethod "http://localhost:8080/chat?q=你好"
Invoke-RestMethod "http://localhost:8080/agent?q=萝卜有哪些原神角色" | ConvertTo-Json -Depth 4

# 签到：状态 / 手动跑调度路径 / 裸调（不落盘）
Invoke-RestMethod "http://localhost:8080/api/checkin/status" | ConvertTo-Json -Depth 6
Invoke-RestMethod "http://localhost:8080/api/checkin/run" -Method Post | ConvertTo-Json -Depth 6
Invoke-RestMethod "http://localhost:8080/api/checkin" -Method Post | ConvertTo-Json -Depth 6

# 定时任务失败后想从日志里捞：搜这一个前缀就够了
Select-String -Path .\logs\*.log -Pattern '\[CHECKIN\]\[ALERT\]'

# 扫码绑定米游社：终端 1 起服务，终端 2 跑脚本（会弹出二维码 PNG）
pwsh -NoProfile -File .\scripts\login-qr.ps1

# 只想拿建码结果（自己渲染二维码时用）
(Invoke-RestMethod "http://localhost:8080/api/auth/qr" -Method Post).data

# 部署时覆盖 .env 位置
java -jar target\HoyoAgent-0.0.1-SNAPSHOT.jar "--spring.config.import=optional:file:./config/.env[.properties]"
```

> 凭据优先级：`~/.hoyo/credential.properties`（扫码写入，带 stoken）**高于** `.env` 的 `HOYO_COOKIE`。
> 想强制回到手填 cookie，把那个文件删掉即可。

---

## 附录 C · 环境事实（已核实）

| 项 | 值 |
|---|---|
| JDK | 25.0.4.1 |
| Maven Wrapper | 3.10.0（`~/.m2/wrapper/dists`） |
| 系统 Maven | 3.9.6（`D:\software\maven\apache-maven-3.9.6`） |
| 本地仓库 | `D:/software/maven/apache-maven-3.9.6/repository`（由 `~/.m2/settings.xml` 指定，命令行与 IDEA 共用） |
| Spring Boot | 4.1.2 |
| langchain4j | 1.20.0（boot4 starter 1.20.0-beta30） |
| OkHttp | 4.12.0（仅供米游社客户端，LLM 走 Spring RestClient） |
| JSON | Jackson 3（`tools.jackson.databind`）；langchain4j 内部是 Jackson 2 |
| 日志 | logback 1.5.38（CVE-2026-19880，已评估无影响） |
| Web starter | `spring-boot-starter-webmvc` |
| Shell | PowerShell 7.6.9（`pwsh`）；`ExecutionPolicy: RemoteSigned` |

**已知踩过的坑（避免重复）**

1. BOM 版本和 starter 版本必须对齐，否则运行时 `NoSuchMethodError`
2. `.env` 不会自动生效，需要 `spring.config.import` 或环境变量
3. Boot 4 默认 Jackson 3（`tools.jackson`），langchain4j 内部用 Jackson 2（`com.fasterxml`），别混用 ObjectMapper
4. `spring-boot-starter-test` 不加 `scope` 会把 JUnit/Mockito 打进运行时 jar（约 10MB）
5. `file:` 相对路径基于工作目录，换目录启动 jar 会找不到 `.env`
6. **`@AiService` 的 `tools` 属性填的是 bean 名（`String[]`），不是 Class**；AUTOMATIC 模式下根本不用填
7. **Windows PowerShell 5.1 默认禁止执行脚本** → 统一用 `pwsh`（7.x），或加 `-ExecutionPolicy Bypass`
8. **观测坏了会被误读成模型坏了**。冒烟全 `(none)` 的真实原因是 Controller 把记录丢了；回复文本里明明有假数据。先看原始证据，再看汇总指标
9. **`settings.xml` ≠ `settings.xml`**。Maven 只读 `~/.m2/settings.xml`，名字少个 `s` 就会被完全忽略（配置内容对也没用）
10. **两套本地仓库会导致"本地有却 Failed to resolve"**。IDEA 与命令行各自指向不同 repository，Maven 会认为本地缓存"不来自当前仓库"而重新下载。统一到一份 `settings.xml` 即可
11. **`-D` 参数在 PowerShell 里要加引号**：`"-Dexpression=settings.localRepository"`，否则会被拆成一截生命周期阶段报错
12. **Mockito 的 `self-attaching` / `dynamic agent` 警告是噪音**：Mockito 5 的 inline mock maker 运行时挂 byte-buddy agent，JDK 21+ 会告警，与服务代码无关
13. **删了源文件不等于删了类**：`mvn test` 不会清理 `target/classes` 里的旧 `.class`，Spring 组件扫描照样扫到它。2026-11-07 改名 `FakePlayerTool` → `PlayerTool` 后直接 `test`，报 `IllegalConfigurationException: Duplicated definition for tool: getPlayerCharacters`。**动过类名/包名/删过类，一律用 `clean test`**
14. **"不调用工具"的用例可能是恒真的**：工具不存在时，模型当然不会调用它。测能力边界必须放诱饵（`DecoyTool`），否则通过与否都不含信息
15. **`@Scheduled` 只在进程活着时触发**：关机/合盖的那一晚等于整天没签。必须配一个"启动补一次"，
    并且用"今天有没有 DONE 记录"判断该不该补，否则每次重启都会多签一遍
16. **失败不重试才看得见失败**：给定时任务加自动重试，会把"连续 3 天成功"验收里的那一天悄悄补上，
    于是你永远不知道它当晚失败过。重试要么不做，要么单独记录"第几次尝试"
17. **`ChatMemory` 和 `ChatMemoryProvider` 不能共存，且 provider 必须恰好一个**：
    同时存在启动就炸（`Only one out of [chatMemory, chatMemoryProvider] can be configured`），
    多个 provider 也炸（`Conflict: multiple beans of type ...`）。`@AiService` 是 **按类型**自动接线的
18. **`ChatMemoryAccess` 要接口继承，不能强转**：框架代理的接口数组只有你的接口一个元素，
    `(ChatMemoryAccess) assistant` 运行时必 `ClassCastException`
19. **`MessageWindowChatMemory` 按条裁、不按轮裁**：会留下"没有提问的回答"。
    不报错（只有孤儿 TOOL 结果非法），但语义是脏的。要轮级淘汰得自己实现 `ChatMemory`
20. **"没传会话 id 就给个 default 会话"是把两个 bug 写进一行代码**：匿名请求互相串味，
    冒烟用例逐条污染。正确做法是一次性 id + 用完 `evictChatMemory`，否则框架那层 map 会留死条目
21. **空闲 TTL 比单轮耗时还短，等于一轮之内反复失忆**：TTL=1s 时 `getMessages` 在轮内就返回空，
    窗口被清空重写；带工具的轮次还会在轮内留下孤儿 `TOOL_EXECUTION_RESULT`，下一轮直接 400。
    TTL 的单位应该是"轮"而不是"秒"

**维护记录**

- 2026-11-12 清理：删除 `Signer`（DS 逻辑已由 `DsSigner` 统一）、`hoyo.salt` / `hoyo.role-id` / `hoyo.server`
  三个死配置、Spring 初始化模板 `HELP.md`；修复 `ErrorCode` 里 `MOYO_SIGN_INVALID` 与 `MOYO_DATA_PRIVATE`
  撞码（都是 `9006`，后者改为 `9007`）。角色 uid 改为自动发现后，`.env` 的 `HOYO_ROLE_ID`/`HOYO_SERVER` 已不读取。
- 2026-11-12 修复（冒烟抓出来的两个真问题）：
  1) `MihoyoAssistant` 的 `SystemMessage` 仍写着"没有签到能力"，导致用户说「帮我签个到」时模型
     直接拒绝；能力清单已补签到（11-13 又收窄为「只有签到」），冒烟第 6 条从负例改成正例。
  2) `LunaInfo.first_bind` 在已签到账号上是 `null`，原来用原始 `boolean` 接会反序列化失败
     （模型报"字段 first_bind 为 null"并重复调用工具）；改成 `Boolean` + `isFirstBind()`，
     并加 `LunaInfoTest` 锁住 null / missing / true 三种形态。
  冒烟结果：8/8（默认 profile，`scripts/smoke-agent.ps1`）。
- 2026-11-12 重构（Step 6/7）：`RecordClient` 接上身份并复用 `MihoyoHttp`；删除
  `MiyousheApi` 与 `MihoyoHttpClient`（`new OkHttpClient` 从 3 处降到 2 处，次日再降到 1）；新增
  `PlayerService` 把「角色发现 + 战绩查询」从工具层挪到服务层；`/agent`、`/chat` 统一为
  `Result<T>` 封皮（冒烟脚本同步改动）；`HoyoProperties` 只留 `hosts/timeout/deviceId`，
  UA/版本/盐交给 `Profiles`。
- 2026-11-13 删除角色查询：`character/list` 没有参考实现可对照（FufuLauncher 的数据页用本地数据），
  新账号上恒 `5003`、旧账号同请求正常（已确认账号侧），且产品不再需要。删除
  `RecordClient.characters`、`CharacterDto`、`PlayerService`、`PlayerTool`；Agent 只剩
  `CheckinTool`，`SystemMessage` 与冒烟用例同步收窄（8/8 通过）。
- 2026-11-13 收尾（PassportClient 并入 MihoyoHttp）：`new OkHttpClient` 降到 **1 处**；
  删掉 `lastSetCookies` 共享字段——v2 换取直接从 `MihoyoResult.headers()` 读 `Set-Cookie`，
  并发 refresh 不再可能串号；`Profiles` 补 `QR_APP`/`QR_WEB`（扫码系 client_type 3/2 的差别显式化）；
  删除 `CookieParser`，cookie 解析统一到 `Credential.of` / `CookieJar`；
  新增 `PassportV2ExchangeTest`（MockWebServer + `hoyo.hosts.passport` 覆盖）、`CookieJarTest`、`CredentialTest`。
  验证：单测 30 条、network 5 条、`/api/auth/refresh` 与三游戏签到真机跑通。
- 2026-11-13 存储抽象：新增 `AccountStore` + `FileAccountStore`（每用户一个 `.account` 文件，
  文件名 = `sha256(userId)` 前 16 位，不泄露平台账号；原子写；POSIX 权限）。
  `LegacyCredentialFile` 只读兜底旧 `credential.properties`（不迁移、不删除，可手动移除）；
  `CredentialProvider` 改为按 `hoyo.user-id`（默认 `local`）解析。
  验证：启动 `source=legacy` → 刷新后写入 `~/.hoyo/accounts/` → 重启 `source=store`，
  签到与 refresh 正常；单测 38 条。
- 2026-11-13 调试页：新增 `src/main/resources/static/{index.html,style.css,app.js}`
  （零构建、同源、无 CDN）。支持扫码绑定/轮询/倒计时、v2 刷新、绑定角色、
  Agent/Chat 双模式对话、工具调用 chips、原始 JSON 调试面板；
  后端新增 `GET /api/auth/status`（userId / source / uid / 掩码 mid / token 有无，不含凭据值）。
  验证：headless 截图渲染正常，静态资源与接口均 200。
- 2026-11-13 P9-8 定时签到：新增 `CheckinScheduler`（`@Scheduled` 00:10 + `ApplicationReadyEvent`
  后虚拟线程补签 + `ReentrantLock` 防重入）、`CheckinRecord`、`CheckinRecordStore`
  （`~/.hoyo/checkin/YYYY-MM-DD.json`，原子写，读坏当没有）；`CheckinService` 的
  `RoleResult` / `Summary` 带上 `ErrorKind`（从 `MihoyoError` 透传，读角色失败时按 9xxx 码回推），
  失败不再只能靠中文文案区分；新增 `@EnableScheduling`、`GET /api/checkin/status`、
  `POST /api/checkin/run`；调试页多一块「每日签到」（今日结果 + 最近 7 天 + 下次时间）。
  配置项 `hoyo.checkin.{enabled,cron,zone,startup-delay,dir}` 进 `.env.example`。
  验证：单测 59 条全绿（新增 `CheckinRecordStoreTest` 6 条、`CheckinSchedulerTest` 15 条）。
  真机三跑（2026-11-13）：`startup-delay=60s` 启动 → `[CHECKIN] 完成 STARTUP：3 个角色：新签 0，已签 3`
  → 记录 `DONE`；同进程再 `POST /api/checkin/run` 打日志"今天已经签过，未重复请求"；重启后
  `[CHECKIN] 今天已签过（…），跳过 STARTUP`（没有发请求）；把 `hoyo.hosts.takumi` 指到不可达端口复现失败
  → `ERROR [CHECKIN][ALERT] STARTUP 签到FAILED：读取绑定角色失败：米游社请求失败（kind=UNKNOWN）`，
  `GET /api/checkin/status` 的 `needsAttention=true`、`attention` 给出人话原因。调试页截图核对过。
- 2026-11-13 P7 会话层（`465bbe4`）：新增 `agent/memory/{MemoryProperties,SessionMemoryStore,MemoryConfig}`
  —— `ChatMemoryProvider` 按 `@MemoryId` 给每条会话一个 `MessageWindowChatMemory`（窗口 20），
  状态统一放 `SessionMemoryStore`（会话数上限 200 + 空闲 TTL 2h + 两层淘汰）；
  `MihoyoAssistant` 改为 `extends ChatMemoryAccess`（不强转，见坑 18），`chat(@MemoryId, @UserMessage)`；
  `/agent` 加 `X-Session-Id` 头，缺省走一次性会话并 `evictChatMemory`；新增 `GET/DELETE /api/memory/{sessionId}`
  （窗口原文 + `expired`）；调试页 sessionId 存 localStorage + 「新会话」按钮；
  `scripts/smoke-agent.ps1` 改为每条用例独立会话，新增 `scripts/smoke-memory.ps1`（差分验收，未跑）。
  配置项 `hoyo.memory.{max-messages,max-sessions,idle-ttl}` 进 `application.yml` 与 `.env.example`。
  验证：单测 14 条全绿（`SessionMemoryStoreTest` 11、`MemoryPropertiesTest` 3，`[MEMORY]` 淘汰/超时日志首次跑到）；
  真机两问（第 2 问不调工具）、连顶 5 轮到 20 条停住、无孤儿 tool 结果。
  同日补充：`SessionView` 增加 `expired`（由 `SessionMemoryStore#expired()` 提供，**只看不 `touch()`**，
  否则打开调试页就等于给会话续命）。实测 TTL=10s：一轮后 `expired=false`，空闲 11s 后 `expired=true`
  且 3 条内容仍在；另用 TTL=1s 复现了坑 21（轮内过期把窗口切碎）。
- 2026-11-16 `smoke-memory.ps1` 首次实跑：**4/4**（A 同会话第二问 0 次工具调用 / B 无会话必调 /
  C 跨会话必调 / D 12 轮后仍 20 条）。D 的窗口头部是一条 `AI(工具调用)` 而其 `USER` 已淘汰，
  发现 ③ 当场复现。顺带跑出一个真问题：**签到三个角色全 `kind=AUTH`**（11-13 同账号还是 `ALREADY`），
  组合特征（ltoken 正常 + 签到失败）指向 cookie_token 单独过期 → 记成 P9-11，
  但**续期能不能靠点一次刷新解决，尚未验证**（本轮的"只刷 v2、救不了"是看代码猜的，已撤回）。
  另核 `~/.hoyo/checkin/` 只有 11-13 一条，P9 的"连续 3 天"实际 **0/3**（14~16 服务未运行，非失败）。
  遗留：裁剪按条不按轮（发现 ③）；30 轮未跑；模型调用失败时本轮用户消息留在记忆里
  （`smoke-memory.ps1` 已于 11-16 补跑，见下条）。

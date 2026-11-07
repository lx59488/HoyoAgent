# HoyoAgent 学习路线与任务清单

> 目标：以「米游社 Agent 工具」为载体，学习 Agent 开发。
> 用法：从上往下做，每完成一条把 `[ ]` 改成 `[x]`。每个阶段结束后打一个 git tag（`phase-1`、`phase-2`…）。
> 设计原则：**先用假数据学 Agent，再接真实 API**。两条线并行、互不阻塞。

**最后更新：2026-11-07**（P1~P4 完成；P6 纵向切片已通，只留 1 个工具；P3 的 10 条用例表已作废，见 P3 后记）

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
| P6 | 接入真实数据 | 自然语言查真实战绩 | 半天 | 🚧 纵向切片已通（1/4 工具） |
| P7 | 记忆与多轮 | 多轮对话不丢上下文 | 1 天 | 未开始 |
| P8 | 工程化扩展（选做） | 多账号 / 可观测 / MCP | 长期 | 未开始 |

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
│       ├── ChatController.java        ← P1：纯模型问答（对照组）
│       └── AgentController.java       ← P2：走 AiService，回传 toolsCalled
├── agent/
│   ├── MihoyoAssistant.java           ← @AiService 接口（无实现，运行时动态代理）
│   ├── observability/ToolCallRecorder.java
│   └── tool/
│       ├── PlayerTool.java            ← P6：1 个真数据工具（身份来自 cookie，无参数）
│       └── DecoyTool.java             ← @Profile("decoy") 诱饵，只用于测能力边界
└── mihoyo/                            ← P4：外部系统客户端（与 agent 完全解耦）
    ├── api/MiyousheApi.java           ← 业务接口：路径 + 参数 → JsonNode
    ├── client/MihoyoHttpClient.java   ← OkHttp 单例 + 统一 header + retcode 检查
    ├── client/DeviceIdProvider.java   ← 持久化 device_id（配置 > ~/.hoyo/device-id > 生成）
    ├── auth/{Credential, CookieParser, CredentialProvider}.java
    ├── dto/CharacterDto.java          ← ⚠️ 用 Jackson 2 注解，见附录 C 坑 3
    ├── enums/MiyousheHost.java        ← BBS / RECORD / TAKUMI 三个 host
    ├── MihoyoEndpoints.java           ← host 覆盖入口（hoyo.hosts 可改 base url）
    ├── sign/Signer.java               ← DS1；salt 空则不发 DS
    └── config/HoyoProperties.java     ← @ConfigurationProperties(prefix = "hoyo")

src/test/java/...
└── mihoyo/MiyousheApiTest.java        ← @Tag("network")，真实打米游社

scripts/smoke-agent.ps1                ← 冒烟脚本（8 条用例，2026-11-07 重写）
scripts/round1.md / round2.md          ← P3 两轮原始输出（历史证据，只读）
scripts/round3.md / round3-decoy.md    ← 新契约下两种 profile 的实测结果（均 8/8）
.env.example                           ← 配置模板；真实值只写 .env（已 gitignore）
docs/ROADMAP.md                        ← 本文件
docs/mihoyo-auth.md                    ← 多用户登录设计稿（P7/P8 用，未实现）
```

接口：`GET /chat?q=`（纯问答）、`GET /agent?q=`（带工具，返回 `{ok, reply, toolsCalled}`）

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
- [x] P4-4 `Signer`：DS1 结构（`md5("salt={salt}&t={t}&r={r}")`，输出 `t,r,hash`）；salt 从 `hoyo.salt` 读，**空则不发 DS 头**
- [x] P4-5 `DeviceIdProvider`：优先级「配置 `hoyo.device-id` > `~/.hoyo/device-id` > 生成 UUID」，原子写入，日志不打印值
- [x] P4-6 `MihoyoHttpClient`：OkHttp 单例、统一 header、HTTP 状态检查、retcode 检查、异常映射到 9xxx
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
- [ ] **P4-13 `CharacterDto` 的字段映射从未验证过**：`MiyousheApiTest` 只打印了 `name` 和 `level`，`actived_constellation_num` → `constellation` 这条没人看过。而小号角色命座全是 0，测了也分不出"映射成功"和"没映射上"。做法：录一份真实响应存 `src/test/resources/fixtures/`，写离线断言——顺手就把 P8 的回放测试开了头

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

**现状（2026-11-07）**：纵向切片通了。1 个工具走真数据，其余 3 个已删除。
「自然语言 → 真实数据 → 中文回答」全链路已验证。

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

## P7 · 记忆与多轮

> **开始前的提醒**：多轮的价值体现在「省略了主语也能接上」。
> 当前只有 1 个无参工具，能测的是"你还记得刚才聊的是安柏吗"，
> 测不了"那深渊呢"——因为深渊工具不存在。
> 所以 P6-6/P6-7 至少补回一个工具，P7 的实验才立得住。

- [ ] P7-1 接入 `ChatMemory`（`MessageWindowChatMemory` / `TokenWindowChatMemory`）
- [ ] P7-2 会话隔离：按 userId 分配独立 memory
- [ ] P7-3 多轮验收：「我在查原神」→「那深渊呢」，模型理解省略的主语
- [ ] P7-4 设置窗口上限，验证连续 30 轮不爆 token
- [ ] P7-5 观察一次记忆裁剪，理解丢了什么

**常见坑**

- memory 在内存里，重启就丢——先接受，别急着上 Redis
- 窗口太大 = 每次都烧钱；太小 = 模型失忆

---

## P8 · 工程化与扩展（选做）

- [ ] 多账号：`Credential` 从「一个」变成「一组」
- [ ] 重试与降级：限流类指数退避，登录类不重试
- [ ] 可观测：Micrometer 打点，替换掉 `ToolCallRecorder`
- [ ] 测试：录制一次真实响应存 JSON，回放跑工具测试（不依赖网络）
- [ ] 暴露成 MCP
- [ ] RAG：攻略/角色数据检索（**等有真需求再做**）

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
| 签到接口 | `POST https://api-takumi.mihoyo.com/event/luna/hk4e/sign` | App 抓包（client_type=5） |
| 签到必备头 | `DS`、`x-rpc-signgame: hk4e`、`x-rpc-app_version`、`x-rpc-client_type`、`x-rpc-device_id`、`x-rpc-device_fp`、`Origin`/`Referer: https://act.mihoyo.com/` | 同上 |
| 签到 body | `{act_id, region, uid}`，**act_id 每期变**，从签到页活动信息接口取 | 同上 |
| `getAppConfig` 是否需要认证 | **不需要** cookie，也不需要 DS（伪造/缺失 DS 均返回 retcode 0） | 三种情况实测 |
| `character/list` 是否需要认证 | **需要** cookie（ltoken）；**不需要** DS（salt 为空照样 retcode 0） | 2026-11-07 实测 |
| DS 输出格式 | `{t},{r},{md5}` | 抓包 + 文档 |
| DS1 | `md5("salt={salt}&t={t}&r={r}")`，`r` = 6 位 `[A-Za-z0-9]` | 文档 + 抓包 |
| DS2 | `md5("salt={salt}&t={t}&r={r}&b={body}&q={query}")`，`r` = 100000~200000 整数 | 文档 |
| client_type → salt | 2→K2，4→LK2，5→4X/6X，1→同安卓 | [UIGF 文档](https://github.com/UIGF-org/mihoyo-api-collect/blob/main/other/authentication.md) |
| salt 表 | 133 个历史值（截止 2.71.1） | [issue #1](https://github.com/UIGF-org/mihoyo-api-collect/issues/1) |
| 观测到的 app_version | 网页 `2.102.0`；App `2.117.0` | 两次抓包 |

### 待填

| 项目 | 值 | 备注 |
|---|---|---|
| cookie 必填字段 | | 哪些必须、哪些可省 |
| `ltuid` / `stuid` 区别 | | |
| `ltoken` / `stoken` 用途与有效期 | | |
| `cookie_token` 获取方式 | | |
| **2.117.0 的 salt** | ❓ 未找到，**但不阻塞** | 133 个公开值 + 7967 个从网页 bundle 挖出的候选，全部不匹配。已实测查询类接口不吃 DS，只在签到这类写接口上才需要——挖不到就先不挖 |
| `device_id` 生成规则 | | 自己定的规则也要记 |
| retcode 分类表 | | 限流 / 失效 / 验证 / 致命 |
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

# 部署时覆盖 .env 位置
java -jar target\HoyoAgent-0.0.1-SNAPSHOT.jar "--spring.config.import=optional:file:./config/.env[.properties]"
```

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
9. **`setting.xml` ≠ `settings.xml`**。Maven 只读 `~/.m2/settings.xml`，名字少个 `s` 就会被完全忽略（配置内容对也没用）
10. **两套本地仓库会导致"本地有却 Failed to resolve"**。IDEA 与命令行各自指向不同 repository，Maven 会认为本地缓存"不来自当前仓库"而重新下载。统一到一份 `settings.xml` 即可
11. **`-D` 参数在 PowerShell 里要加引号**：`"-Dexpression=settings.localRepository"`，否则会被拆成一截生命周期阶段报错
12. **Mockito 的 `self-attaching` / `dynamic agent` 警告是噪音**：Mockito 5 的 inline mock maker 运行时挂 byte-buddy agent，JDK 21+ 会告警，与服务代码无关
13. **删了源文件不等于删了类**：`mvn test` 不会清理 `target/classes` 里的旧 `.class`，Spring 组件扫描照样扫到它。2026-11-07 改名 `FakePlayerTool` → `PlayerTool` 后直接 `test`，报 `IllegalConfigurationException: Duplicated definition for tool: getPlayerCharacters`。**动过类名/包名/删过类，一律用 `clean test`**
14. **"不调用工具"的用例可能是恒真的**：工具不存在时，模型当然不会调用它。测能力边界必须放诱饵（`DecoyTool`），否则通过与否都不含信息

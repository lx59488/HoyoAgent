# HoyoAgent 路线图与任务清单

> **项目定位：一个跑在自己机器上的米哈游系游戏助手。签到是第一个能力，不是终点；
> 下一步是游戏知识库，再往后是能把日常聊起来的对话。** 名字里的 Hoyo 是承诺——主线只做米哈游系游戏。
>
> 本文是**路线图 + 决策记录**：既回答「怎么走到今天」，也回答「下一步长什么样」。
> 子系统文档（`mihoyo-auth` / `qq-inbound` / `tool-ideas`）只描述自己那一块，**不定义项目定位**。
> 用法：从上往下做，每完成一条把 `[ ]` 改成 `[x]`。每个阶段结束后打一个 git tag（`phase-1`、`phase-2`…）。
> 设计原则：**先用假数据学 Agent，再接真实 API**；**能力靠加工具扩展，不靠改定位**——
> 加知识库或闲聊不应该动已有的包边界（见「四条边界」）。
> **部件轴不进本文**：一个完整 agent 有哪些模块、边界怎么定、每个部件怎么验证，见
> [`agent-architecture.md`](agent-architecture.md)；本文只负责「何时做 / 做没做」。

**最后更新：2026-11-23**（①**契约 C1–C5 全部落地**：C1-a 四段 prompt、C2 能力注册表、C3 会话串行、
C4 `TurnTrace` + 20 条评测基线、C5 工具输出契约；②**本表回填真实状态**——此前 C1/C2 还写着「未开工」，
而 11:27 / 12:33 / 13:11 的三个提交已经把代码落了；③**测试 229 条全绿**（`-Dgroups=!network` 排除联网用例），
评测基线首跑 **18/20（有效 10/12）**，两条 FAIL 都是 markdown 格式；④同日完成生产化 P0（`/agent` 串行、
`SessionMemoryStore` 原子化、默认只听本机）与 P1（SQLite 存储 + 旧文件迁移），详见文末维护记录。
11-22 的定位收宽与 HTTP 入口删除见文末维护记录。
P9 的 3 天观察仍是 **0/3**（凭据 11-16 起 `AUTH` 失效，要先重扫）；QQ 主线 P11 已落地但**未真机验收**）

---

## 能力地图（项目要去哪）

| 层 | 能力 | 状态 | 代码落点 |
|---|---|---|---|
| 账号 | 每日签到（三游戏）、签到总览 | ✅ | `mihoyo/checkin` + `agent/tool` |
| 账号 | 账号体检（哪张票过期、上次为什么失败） | ❌ 未做 | `mihoyo/credential` |
| 数据 | 便笺（树脂）、深渊、抽卡、公告 | ❌ 未验证 / 未做 | `mihoyo/gamerecord` 等 |
| **知识** | **角色 / 武器 / 攻略 / 活动问答（RAG）** | **🚧 未立项（P12）** | 新域 `knowledge/` |
| **对话** | **人设、闲聊、跨话题记忆** | **🚧 未开工（P13，前置 C1 + C4 已满足）** | `agent`（prompt 拆分见 C1） |
| 通道 | QQ 官方机器人 / Web 调试台 / 出站通知 | ✅ | `adapter` / `channel` |

> 这张表回答「项目要去哪」，P0~P11 回答「怎么走到今天」，P12/P13 是「下一步」。
> **表里没有的能力，不要写进 `<capabilities>`**（`SystemPrompt.CAPABILITIES`）——工具不在、提示词先承诺，模型就会编（P3/P6 的教训）。
> 这一段的话现在由 `CapabilityConsistencyTest` 守着：注册表里没有的能力，测试会红。
> 新增能力的第一件事是加到本表，第二件事才是写代码。
>
> 本表是**产品能力轴**（用户能做什么）。**工程部件轴**（agent 有哪些模块、缺哪些部件）不在本表——
> 它不产生产品能力，但缺了会挡住所有能力，见 `agent-architecture.md` §1。
> 这张表里没有一个格子叫「并发安全」「评测基线」，所以那类问题不会自己出现在下面任何一期的待办里。

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
| P7 | 记忆与多轮 | 多轮对话不丢上下文 | 1 天 | 🚧 会话层 / 摘要压缩 / 只读历史 / SQLite 后端已落地（P7-1/2/6/7/8）；P7-3 用例待重跑 |
| P8 | 工程化扩展（选做） | 多账号 / 可观测 / MCP | 长期 | 未开始 |
| P9 | 登录与签到 | 扫码拿 stoken，能签当天 | 2~3 天 | 🚧 3 天观察 **0/3**（14~16 服务未运行）；11-16 签到 `AUTH` 失效，续期机制待验（P9-11） |
| P10 | QQ 入站（OneBot v11） | 在 QQ 私聊里对话 | 1~2 天 | ⏸️ 降级为备选：Step 0 桥已删除（git `3dcd255` 可恢复），协议代码不复用 |
| P11 | QQ 官方机器人 | 手机 QQ 里直接对话 | 1~2 天 | 🚧 `adapter/qq` + `AgentChatService` + `TextChunker` 已落地；**未真机验收**（P11-5） |
| P12 | 游戏知识库 | 能引用出处地回答角色/武器/攻略 | 长期 | ⬜ **未立项**：只定了边界与三个前置问题 |
| P13 | 人设与日常对话 | 闲聊得体、不越界、不编数据 | 长期 | ⬜ **未开工**；前置 C1（拆 prompt）+ C4（评测基线）已于 11-23 成立，可开工 |

> **状态校准自 2026-11-07 的代码核对**：P4-5（DeviceIdProvider）、P4-12（关日志）、
> P6 纵向切片其实都已完成，只是清单没跟上。**这份清单就是项目的单一事实来源，
> 落后于代码比缺失更危险**——照着旧清单干活会重复劳动。
> **2026-11-22 又校准了一遍事实层**：HTTP 绑定/签到入口、`login-qr.ps1`、`smoke-agent.ps1`
> 都已删除，正文里原本教人调用它们的命令已改成 QQ 流程或记录文件。见到命令先看它还在不在。

---

## 跨阶段契约 C1–C5

> 契约没有「完成」这个状态，只有「今天有没有被违反」。所以用 `C` 编号、不进 `P` 序列——
> `P` 做完打 tag，`C` 每天都要守。细节与验证方式见 [`agent-architecture.md`](agent-architecture.md) §2。

| 契约 | 约束（一句话） | 落点 | 怎么验证 | 状态 |
|---|---|---|---|---|
| **C1** Prompt 组装 | system prompt 固定四段 `<persona>` / `<capabilities>` / `<channel>` / `<context>`，顺序与标签不变，每段有唯一所有者 | `agent/prompt/`（`SystemPrompt` + `PromptSection`） | `SystemPromptTest`：段序由枚举定 / 空段整段不输出 / 标签闭合 / 超长抛异常不截断 / `@SystemMessage` 里只剩 `{{system}}` | 🟡 骨架已落（11-23）：四段 + `VERSION=p1` + 4000 字上限；`persona` 与 `channel` 仍是空串 |
| **C2** 工具治理 | 能力清单的单一真相是注册表，启动期与 `@Tool` 双向对齐；每个工具声明风险级 | `agent/tool/Capability` + `@Tool` | `CapabilityConsistencyTest`：注册表名字集合 = `@Tool` 名字集合 + 每个 `capability.phrase()` 必须出现在 `SystemPrompt.compose()` 里 | ✅ 已成立（11-23）；风险级目前只有 `READ / WRITE`，`IRREVERSIBLE` 等真有不可逆动作再加 |
| **C3** 身份与会话 | 身份只在适配器构造一次并注册进 `SessionRegistry`；`uid` 永不做工具参数；同一 session 串行 | `channel/SessionSerializer` + `QqBotDispatcher` | `SessionSerializerTest`：同 session 串行 / 不同 session 并行 / 异常释放锁 | ✅ 已成立（11-23）；`/agent` 调试口故意不串行 |
| **C4** 可观测与评测 | 每轮一条 `TurnTrace`；prompt 带版本号；改 prompt / 工具 / 模型前先跑基线 | `agent/observability/TurnTrace` + `scripts/eval/` + `scripts/eval.ps1` | 一条命令出分数（`pwsh -File scripts/eval.ps1`，明细写 `target/eval-last.json`）；日志按 `traceId` 可串 | ✅ 已成立（11-23）：20 条用例、有效 12 条；首跑 18/20（有效 10/12），分数已记进维护记录 |
| **C5** 工具输出 | 工具只给事实，不给措辞：不写结论句、不漏内部枚举、不带 `【】` 与 markdown、推算值必须标注 | `agent/tool/*Tool.format`（`CheckinTool` / `CheckinOverviewTool`） | `ToolOutputContractTest`：无内部枚举 / 无装饰符 / 推算值带「（推算）」/ 每角色一行 | ✅ 已成立（11-23） |

**为什么现在定**：这五条便宜在现在、贵在以后——它们决定后面每个模块往哪儿插。
C1 是 prompt 的挂载点，C2 是工具的闸门，C3 是所有多用户 / 群聊能力的地基，C4 是没有它就无法验证任何改动，
C5 是 C1 的下半场：prompt 那边放开了措辞，工具这边就不能再把结论写死。

**现状快照**（2026-11-23）：五条契约都不再被违反。11-23 之前记在案的四处违反，处置如下：

1. ~~`MihoyoAssistant` 是一整段 `@SystemMessage`，人设与能力混在一起，prompt 无版本号~~ → 已拆成 `SystemPrompt.compose()` 四段，注解里只剩 `{{system}}`（C1）。
2. ~~AUTOMATIC 接线 + 无风险级 + 无一致性测试，能力声明漂移过三次~~ → 改为 `Capability` 注册表 + 启动期一致性测试（C2）。
3. ~~`TurnWindowChatMemory.add()` 读-改-写且无锁，同一会话并发会丢轮次~~ → 加 `SessionSerializer` 串行化（C3）。
4. ~~`ToolCallRecorder` 只记工具名、只存在内存；冒烟脚本 11-22 已删~~ → `TurnTrace` 每轮落日志 + `scripts/eval.ps1` 金标集（C4）。

**但契约成立不等于产品成立**：P9 仍是 0/3（定时签到还没连续成一格）、P11-5 未实测、基线只有 20 条
（其中 8 条恒真，真正有判别力的 12 条）。契约保证的是「改动不会悄悄弄坏别处」，不是「功能已经能用」。

---

## 当前代码结构

> 2026-11-20 起不再维护逐文件树：它必然落后于代码（上一版还写着 `MessageWindowChatMemory`、
> `CredentialProvider`、25 个测试文件）。这里只保留**包边界约定**；具体文件用 IDE 看，
> 结构性的变更写进文末「维护记录」。

```
com.luobo.hoyoagent
├── adapter/   入站：http（调试页 / API）、qq（官方机器人 WS 网关 + 被动回复）
├── agent/     Agent 层：AiService、工具、记忆、聊天历史、观测；依赖 mihoyo，不反向
├── channel/   平台无关的管道：切片（TextChunker）、会话→用户映射（SessionRegistry）、
│              出站通知（notify/：Notifier 接口 + WebhookNotifier 实现）
├── common/    横切：统一响应信封（ApiResult）、错误码、异常处理、日志脱敏（Mask）、
│              本地存储基座（storage/LocalFileStore：目录解析 + 原子写 + 哈希文件名；
│              storage/SqliteDb + SqliteProperties：SQLite 连接 / 建表 / 迁移）
└── mihoyo/    出站：米游社客户端
    ├── transport/   唯一 HTTP 管道 + 请求头套装（salt/版本锁在一起）+ DsSigner / MihoyoHost
    ├── credential/  Account / Credential / CookieJar；AccountService 是凭据唯一入口
    ├── auth/        扫码登录（passport） · checkin/ 签到 · binding/ 绑定编排
    ├── gamerecord/  战绩接口 · role/ 角色模型（GameRole）+ 缓存 · client/ device_id
    ├── error/       retcode → ErrorKind 映射
    └── config/      HoyoProperties（hoyo.*）+ MihoyoEndpoints（host 覆盖）
```

**四条边界**（改代码前先确认自己在哪一边；第 4 条 2026-11-22 为知识库预留）：

1. `adapter` 只表示**入站**，`mihoyo` 表示**出站**，`channel` 只放**平台无关**的管道
   （切片 / 会话映射 / 出站通知）。
2. `mihoyo` 不依赖 `agent` / `adapter`；`agent` 可以依赖 `mihoyo`。
   2026-11-21：把 `agent/notify` 下移到 `channel/notify` 之后这条才真正成立——
   此前 `mihoyo.checkin` 反向依赖 `agent.notify`，两层成环。
   **目前没有测试守着，改代码时请手动核对**（见文末"依赖方向测试仍未做"）。
3. 凭据只有一个入口 `AccountService`：工具和控制器都从它拿，不自己读文件、不自己读 `.env`。
   （2026-11-20 删掉 `CredentialProvider` 后，写路径也只剩 `save`/`claim` 一个。）
4. **新域不往老域里塞**（2026-11-22 新增，为知识库预留）：知识库/检索属于独立域 `knowledge/`，
   现在还没建包。它既不是「米游社的接口」（不进 `mihoyo`），也不是模型可直接执行的动作
   （不直接写成 `@Tool` 丢进 `agent/tool`）——工具只做「取哪一段知识」的编排，
   数据源与切分留在 `knowledge`。**没有第二个实现之前别提前抽象**。

测试与脚本、前端调试页仍在原来的位置，不再逐条罗列。

接口：`GET /`（调试页：聊天调试 + 只读聊天历史 + 会话记忆）、`GET /chat?q=`（纯问答，对照组：无工具、无记忆）、
`GET /agent?q=` + **请求头 `X-Session-Id`**（带工具，返回 `{code, message, data:{reply, toolsCalled, sessionId, messages}}`；
不传头 = 一次性会话，请求结束即销毁）、`GET /api/memory/{sessionId}`（窗口内容 + `expired` 标记）、
`DELETE /api/memory/{sessionId}`（清空会话）、`GET /api/transcripts`（有历史的会话列表，按最后聊天时间倒序）、
`GET /api/transcripts/{sessionId}?before=&limit=`（只读分页，`seq` 升序；**没有写/删接口**）

> 注意：HTTP 入口**不注册平台身份**（身份由 `adapter/qq` 在每条消息上注册）。
> 所以 `/agent` 里的工具调用只会回「没有绑定米游社账号」——它用于调模型和看记忆，
> 不是签到入口。带工具的真实链路只在 QQ 里。

绑定与签到不再有 HTTP 入口：在 QQ 里发「绑定」扫码，发「帮我签到」触发工具。

**结构决策**：原计划放 `adapter.mihoyo`，实际改为顶层 `mihoyo/`。原因是后续会有多个入站适配器（http / qq / 将来 telegram），把「外部系统客户端」提到顶层更好表达边界。约定：**`adapter` 只表示入站，`mihoyo` 表示出站，`channel` 只放平台无关的会话管道**（切片已落，见 `TextChunker`）。

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
- 新脚本 8 条用例对齐新契约（该脚本已于 2026-11-22 随 HTTP 入口删除，
  `git log -- scripts/smoke-agent.ps1` 可找回最后版本）

**下一步三条的处置：**

- [x] 能力边界已加 `@SystemMessage`（P6 切片时同步收窄，见 `MihoyoAssistant`）——11-23 起这段文字移到 `SystemPrompt.CAPABILITIES`，标注里只剩 `{{system}}`
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
它也不再是产品需要的能力。Agent 现在只剩签到类工具（`CheckinTool` + `CheckinOverviewTool`）。
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

> **开始前的提醒（2026-11-22 校准）**：多轮的价值体现在「省略了主语也能接上」。
> 11-16 起已有第二个工具（`CheckinOverviewTool`），P7-3 现在能跑
> 「今天签了吗」→「那星铁呢」；「那深渊呢」仍然缺深渊工具，别拿它当验收。
> **前置条件已经满足，不要再等第二个工具。**

**现状**：会话层已落地（`agent/memory` + `X-Session-Id`）；窗口 2026-11-18 起改为按轮裁剪
（`TurnWindowChatMemory`），发现③ 已修；2026-11-23 起超窗先压成摘要（P7-7），硬上限默认 60 条。
P7-3 只差按新用例跑一遍并记账。完整聊天历史另存一份 append-only 流水（P7-6），和窗口互不影响；
2026-11-23 起两者都可切到 SQLite（P7-8），默认仍是文件。

- [x] P7-1 接入 `ChatMemory`：`ChatMemoryProvider` + 按轮窗口（`TurnWindowChatMemory`，默认 60 条硬上限 + 摘要压缩），`@AiService` 注解零改动
- [x] P7-2 会话隔离：`@MemoryId` 当不透明字符串；状态全在 `SessionMemoryStore`（会话数上限 + 空闲 TTL）
- [ ] P7-3 多轮验收「今天签了吗」→「那星铁呢」—— **第二个工具已就位，只差跑**
- [x] P7-4 部分：`smoke-memory.ps1` 的 D 条 12 轮通过（20 条停住不涨）；30 轮的 token 曲线仍没跑
- [x] P7-5 观察过一次裁剪 —— 结论比预期狠，见发现 ③
- [x] P7-6 只读聊天历史：每会话 append-only JSONL（`~/.hoyo/transcripts/`，默认 90 天），调试台可按用户看全文；读不 touch、清空记忆不删历史
- [x] P7-7 超窗摘要压缩：先压成 `SystemMessage` 摘要再裁剪（`ConversationCompactor`，默认 40 条触发 / 保留 3 轮），失败回退到按轮裁剪
- [x] P7-8 SQLite 存储后端：记忆与聊天历史可切 `hoyo.storage.backend=sqlite`（单文件 + WAL，旧文件懒迁移 / 启动导入），file 后端保留

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
**2026-11-18 已修**：`TurnWindowChatMemory` 改成按 USER 分轮，整轮一起留或一起丢，
宁可少留一轮也不切断一轮。下面保留为动机说明（不是待办）。

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
| 单测 | `SessionMemoryStoreTest` 11 条 + `MemoryPropertiesTest` 5 条；`[MEMORY]` 的淘汰/超时两行日志终于跑到 |
| **`smoke-memory.ps1`（2026-11-16 实跑）** | **4/4**：A 同会话第二问 0 次工具调用 / B 无会话必调 / C 跨会话必调 / D 12 轮后仍 20 条 |
| 裁剪痕迹（同日复现） | D 的窗口头部是一条 `AI(工具调用)`，它对应的 `USER` 已被淘汰——发现 ③ 的现场复现了一次 |

**常见坑**

- memory 是两层：RAM 里的会话（`SessionMemoryStore`，会话数上限 + 空闲 TTL）和持久化
  （`file` 后端是 `FileMemoryStore` 的 JSON，`sqlite` 后端是 `memory_session` 表；30 天保留）。
  **重启不丢**——所以"别急着上 Redis"仍然成立，但要先分清疼的是"进程内淘汰"还是"多副本共享"，
  两者解法完全不同
- 窗口太大 = 每次都烧钱；太小 = 模型失忆。默认 60 条硬上限，正常会先压成「摘要 + 最近 3 轮」；
  一次工具调用占 3~4 条，60 条约等于 12 轮
- 会话 id 是**适配器**产出的，Agent 不解析也不信任。将来 onebot 接入时 id 会含 QQ 号，所以日志一律走 `mask()`
- **不传会话 id ≠ "给个 default 会话"**：后者会让所有匿名调用串进同一个对话，也会让冒烟用例互相污染
- 模型调用抛异常时，本轮用户消息已经在 memory 里（框架在发请求前就 add 了），
  下一轮会看见一句"没被回答过的提问"。要做干净得回滚最后一条，暂未做
- 调试接口故意不做过期判断（看一眼就删会变成自毁按钮），所以"看得见 7 条"和"下一轮收到 0 条"
  可以同时为真——这正是 `expired` 字段存在的理由
- **空闲 TTL 必须远大于单轮耗时（含工具往返）。** 实测把 TTL 调到 1s：一轮之内就"过期"，
  store 被反复清空重写；带工具那轮更糟——轮内留下了孤零零一条 `TOOL_EXECUTION_RESULT`，
  下一轮必然被 provider 拒绝。默认 2h 不是随便定的，`hoyo.memory.idle-ttl` 调小要按分钟起步

### P7-6 · 只读聊天历史（2026-11-23）

**为什么不把 `max-messages` 调大**：窗口是"下一轮真正发给模型的东西"，按轮裁剪到硬上限
（默认 60 条，正常会更早压成摘要）；更早的轮次要么被压成摘要、要么被裁掉，原文不再保留。
想知道"这个用户到底聊过什么"，还是得另存一条 append-only 流水，和窗口各管各的。

- 存储：`agent/transcript`，一个会话一个 `.jsonl`（`<state-dir>/transcripts/`），一行一轮，
  `seq` 会话内递增，分页靠它；文件名仍是 sha256 前 32 位，不把 openid 写进文件系统。
- 记录点：`AgentChatService#ask`。只有 `userId` 非空才写（QQ），浏览器与一次性会话不写——
  否则历史列表会被调试用的 UUID 会话灌满。失败轮也写，`assistant=null` + `error` 摘要，
  免得到时候"用户问了但历史里没有"。
- 只读：`GET /api/transcripts` + `GET /api/transcripts/{sessionId}?before=&limit=`；
  **没有 POST / DELETE**。读不 `touch` 会话、不续 TTL，和 `snapshot/expired` 同一约定；
  `DELETE /api/memory/{id}` 只清窗口，不删历史。
- 限界：默认保留 90 天（比记忆的 30 天长），每天 04:40 清理；`hoyo.transcript.enabled=false`
  可整体关掉。**历史从这一版开始积累，之前的轮次已被窗口裁掉，无法回填。**
- 调试台：左栏按用户分组，组内每个会话单独一条（掩码 userId、轮数、最后时间），点开 `<dialog>` 看全文，
  「加载更早」按 `seq` 往前翻；页面上没有任何编辑 / 删除入口。
- 单测：`FileTranscriptStoreTest` 11 条（追加 / 列表倒序 / 分页 / 坏行 / 失败轮 / 清理 / 关闭 /
  重启续 seq / 预览）+ `TranscriptControllerTest` 2 条（GET 可用；POST / DELETE 无映射，业务码 405）。

### P7-7 · 超窗摘要压缩（2026-11-23）

**为什么不只是把窗口调大**：窗口是每轮发给模型的全部历史，调大只是把"失忆"推迟，token 线性涨；
真正要保的是更早轮次里的事实。所以超过阈值时，把最早的完整轮次交给模型压成一段摘要，
替换掉旧轮，只保留摘要 + 最近几轮。

- 存放：摘要是一条 `SystemMessage`（前缀 `【更早对话摘要】`），放在窗口头部。
  `TurnWindowChatMemory` 本来就不裁 SYSTEM，`ChatMessageSerializer` 也原生支持它，落盘格式不用改。
- 触发：`TurnWindowChatMemory#add` 里、`trim` **之前**调用 `ConversationCompactor`；
  顺序不能反，否则旧轮已被裁掉，摘要没有素材。默认 `max-messages=60`，
  `compaction.trigger-messages=40`、`keep-turns=3`、`max-summary-chars=800`。
- 失败：模型调用异常、摘要为空、或压完不比原来短，都原样返回，由 `trim` 兜底——
  压缩是"尽量多记"，不能把整轮聊天打挂。
- 分块：旧内容超过一次输入上限（12000 字）时按块摘要，后一块带着前一块的摘要继续，避免只看到最近一截。
- 配置：`hoyo.memory.compaction.{enabled,trigger-messages,keep-turns,max-summary-chars}`；
  `enabled=false` 退回按轮裁剪。`trigger` 必须小于 `max-messages`，配置层会强行夹住。
- 单测：`ConversationCompactorTest` 9 条（触发 / 阈值内 / 关闭 / 失败 / 合并旧摘要 / 空摘要 /
  截断 / 分块 / 全是最近轮）+ `TurnWindowChatMemoryTest` 新增 1 条（先压后裁 + 摘要留在头部）+
  `FileMemoryStoreTest` 新增 1 条（SystemMessage 落盘往返）。

### P7-8 · SQLite 存储后端（2026-11-23）

**为什么切**：单个 `hoyo.db` 比几百个 JSON/JSONL 文件更容易备份、迁移和排查；WAL 让「记忆窗口的
整份替换」和「聊天历史的追加」不再依赖原子重命名，也省掉 `FileMemoryStore` 每次 save 重写整份 JSON
的开销。默认仍是 `file`，`docker compose` 默认 `sqlite`——单机常驻更适合后者。

- 依赖：`org.xerial:sqlite-jdbc:3.49.1.0`（直接 JDBC，不引入连接池/ORM）。
- 表：`memory_session(session_id PK, updated_at, payload)`；`transcript_session(session_id PK, user_id,
  turns, last_at, preview)`；`transcript_turn(session_id, seq, at, user_id, user_text, assistant_text,
  tools, error, PK(session_id, seq))`；`meta(k, v)` 记录一次性迁移标记。3 个索引：`memory updated_at`、
  `turn at`、`turn user_id`。
- 适配器：`SqliteMemoryStore` / `SqliteTranscriptStore` 只在 `hoyo.storage.backend=sqlite` 时装配；
  file 实现加 `havingValue="file", matchIfMissing=true`，两套 bean 不冲突。
- 迁移：记忆按会话懒迁移（SQLite miss 时读旧 `FileMemoryStore` 文件并写回）；聊天历史在
  `ApplicationReadyEvent` 里把旧 `transcripts/*.jsonl` 一次性导入（`INSERT OR IGNORE` + meta 标记）。
  旧文件不删，便于回滚。
- 配置：`hoyo.storage.sqlite.path`（空 = `<state-dir>/hoyo.db`）、`busy-timeout`（默认 5s）；连接统一设
  `PRAGMA journal_mode=WAL / synchronous=NORMAL / foreign_keys=ON`。
- 限界：SQLite 是**单机单写者**方案，多实例共享同一个库仍会锁冲突；`MemoryStore` / `TranscriptStore`
  接口已留好，后续换 PostgreSQL 只需再加一个实现。账号与签到记录目前固定文件实现，不受该开关影响。
- 单测：`SqliteMemoryStoreTest` 7 条、`SqliteTranscriptStoreTest` 8 条、`SqliteBackendTest` 1 条
  （`@SpringBootTest` 断言 sqlite 装配）；全量 229 条通过。18080 冒烟：旧 JSONL 迁移成 1 会话/1 轮后
  `GET /api/transcripts` 正常返回。

---

## P8 · 工程化与扩展（选做）

- [ ] 多账号：三个存储边界都已就位（`AccountStore` 每用户一个 `.account` 文件、
  `CheckinRecordStore` 每人一个记录目录、记忆/聊天历史按会话存 SQLite 或 JSON/JSONL），还差
  `@ToolMemoryId → userId → 凭据` 的解析与存储加密。存储根已统一到 `hoyo.state-dir`，
  实现由 `hoyo.storage.backend` 选择（`file` / `sqlite`，见 P7-8）
- [ ] 重试与降级：限流类指数退避，登录类不重试
- [ ] 可观测：Micrometer 打点，替换掉 `ToolCallRecorder`
- [ ] 测试：录制一次真实响应存 JSON，回放跑工具测试（不依赖网络）
- [ ] 暴露成 MCP
- [ ] RAG：攻略/角色数据检索 —— **已立项为 P12**（不再是「等有真需求」；边界与前置问题见 P12）

---

## P9 · 登录与签到（2026-11-12 起）

> 目标：把「手填 cookie」换成「扫码一次，之后自动签到」。
> 设计细节与协议事实都在 [`docs/mihoyo-auth.md`](mihoyo-auth.md)，这里只放进度。

- [x] P9-1 `DsSigner`：DS1 / DS2 两条公式，md5 用外部向量锁住
- [x] P9-2 `PassportDevice`：持久化扫码 `device_id` + `device_fp`（不持久化会 `-3503`）
- [x] P9-3 `PassportClient`：建码 / 轮询 / 换 ltoken / 换 cookie_token / **v2 换取**
- [x] P9-4 建码 + PNG + 轮询状态机（原 `POST /api/auth/qr`，2026-11-22 起入口改为 QQ 里发「绑定」）；
  成功写 `~/.hoyo/accounts/<sha256(userId)[0:16]>.account`
- [x] P9-5 真机扫码：`WAITING→SCANNED→BOUND`，stoken/ltoken/cookie_token 全拿到
- [x] P9-6 `luna/info` 复测 —— **结论被推翻**：签到认 `cookie_token`+`account_id`，不是 stoken
- [x] P9-7 三游戏签到打通：星铁/绝区零真签成功，原神当日已签返回 `ALREADY`；幂等已验证
- [x] P9-7b 角色自动发现（`GameRoleService`），替掉写死的 `HOYO_ROLE_ID`
- [x] P9-8 `@Scheduled` 每天 00:10 + 当日记录幂等（先文件，多用户再上库）+ 失败可见性，
  见下面「P9-8 的三个决定」
- [ ] P9-9 stoken 失效时主动提示重新扫码（刷新失败 → `Notifier` 发一条）
  —— 现在失败会亮灯（`[CHECKIN][ALERT]` + `kind=AUTH` 日志 + `~/.hoyo/checkin` 的记录），
  但**不会自动续期**；`/api/checkin/status` 与 `/api/auth/refresh` 已于 11-22 随控制器删除，
  人看的入口变成「QQ 里问一句 + 翻记录文件」，真正的主动提示要靠这一条自己发通知
- [ ] **P9-11 签到凭据（v1 `cookie_token`）失效后怎么续期——机制未验证**（2026-11-16 实测）
  —— 11-13 同账号还能签（`ALREADY`），11-16 三个角色全 `FAILED / kind=AUTH`，而 `hasStoken=true`、
  （当时）`GET /api/auth/roles` 一切正常（该端点 11-22 已删）。这个组合就是"ltoken 还活着、cookie_token 单独过期"：
  FufuLauncher `TokenRefreshService.CheckCookieValidAsync` 的注释原话是
  *"The role binding API validates LToken only. CookieToken can expire independently."*
  → **AUTH 的根因基本可定为 cookie_token 过期**，不是账号、不是风控。
  但**"刷新一次能不能救回来"没有验证过**：`PassportClient.exchangeV2(stoken, mid)`
  收的是全部 `Set-Cookie`，可能带新的 v1 `cookie_token`（能救），也可能只带 `*_v2`（未必救得了，
  因为签到时那套 cookie 是我们自己拼的）。注意这条**不能说成"只刷 v2 所以救不了"**——
  那是看代码猜的，RefreshResult 里只检查 `ltoken_v2` 只是回报信息，不代表 jar 里没有 v1。
  参照物：FufuLauncher 拿到 v2 后是**删掉** v1 的（`TokenRefreshService.RefreshCookieAsync` 149~153 行），
  说明它认为 v2 够用；我们不敢删，因为 11-12 实测签到认 v1。
  **下一步是一次实验，不是写代码**：在 QQ 里重新发「绑定」拿一张全新 `cookie_token`，第二天看记录 `kind`；
  或在测试里直接调 `PassportClient.exchangeV2(stoken, mid)`，看返回的 CookieJar 里到底有没有 v1
- [x] ~~P9-10 排查 `character/list` 的 `5003`~~ —— **已关闭**：该接口 11-13 删除；实验确认与请求头无关
  （新账号四种头组合全 `5003`，旧账号同一套头 `retcode=0`）

**验收**：连续 3 天自动签到成功，且 `~/.hoyo/accounts/` 里的凭据不用再动（不需要重新扫码）。

> **2026-11-16 状态：0/3。** `~/.hoyo/checkin/` 里只有 11-13 一条，14/15/16 都没有——
> 这三天服务根本没在跑（`@Scheduled` 只在进程活着时触发，见坑 15），**不是签到失败**。
> 想让它成立：先在 QQ 里给机器人发「绑定」重扫一次（当前凭据已 `AUTH` 失效），然后让服务一直开着。

**开工命令**（2026-11-22 起没有 HTTP 绑定/签到入口了）：

```powershell
.\mvnw.cmd -B -ntp spring-boot:run
# 然后手机 QQ 里：发「绑定」扫码；发「签到了吗」看总览；发「帮我签到」跑一次
```

看今天到底签没签——记录文件一天一个 JSON，按 userId 分目录（Windows 上 `~` 就是 `$HOME`）：

```powershell
Get-ChildItem "$HOME\.hoyo\checkin" -Recurse -Filter *.json |
  Sort-Object LastWriteTime -Descending | Select-Object -First 3 |
  ForEach-Object { $_.FullName; Get-Content $_.FullName }
```

> 注意：QQ 里发「帮我签到」走的是工具，**不写记录文件**（记录是定时任务/启动补签的产物）。
> 想让当天留下一条 `DONE`，就重启进程等启动补签跑一遍；今天已有 `DONE` 时会跳过。

### P9-8 的三个决定（比代码重要）

1. **只试一次，失败不自动重试。** 重试会把"连续 3 天成功"这个验收条件糊掉——失败就该亮灯，
   而不是靠重试掩盖。补跑是人的动作：QQ 里发「帮我签到」（走工具、不落记录），
   或重启进程让启动补签跑一遍（**会落记录**，今日已有 `DONE` 才跳过）。
   HTTP 那个 `force` 入口随 11-22 删除，别再找了。
2. **启动补签。** cron 只在进程活着时触发，笔记本合盖一晚就错过整天。
   启动后 `hoyo.checkin.startup-delay`（默认 60s）补一次；今天已有 `DONE` 记录就跳过。
   补签跑在**虚拟线程**里：守护线程，既不卡 `SpringApplication.run`，也不会拖住测试 JVM。
3. **失败可见，且分类。** `[CHECKIN][ALERT]` 前缀只给失败用，日志里搜一次就能捞全；
   同时写 `~/.hoyo/checkin/<日期>.json`，重启后仍能读到（没有状态页了，直接读文件）。
   `kind` 来自 `MihoyoError`（AUTH / NEED_VERIFY / RISK…），所以"要重新扫码"和"网络抖了一下"
   在日志和记录里是**两种不同的话**，不用正则匹配中文。

**记录文件的形状**（一天一个 JSON，人直接打开也能读）：

```json
{
  "userId": "qq:E43830A902F2", "date": "2026-11-13",
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

**已知边界**：已按 `userId` 分目录（`checkin/<sha256(userId)[0:16]>/<date>.json`），多账号互不覆盖，
解绑时 `deleteAll(userId)` 删整目录。**"上库时 `CheckinScheduler` 不用改"这句话以前是假的**：
它当时用 `records.root()` 打日志，而 `root()` 给不出数据库的路径；
2026-11-21 把 `root()` 换成 `location()` 并把 store 抽成接口之后，这句话才成立。

---

## P10 · QQ 入站（2026-11-16 起）

> 目标：入口从「浏览器 / curl」变成「QQ 里直接聊」。
> 设计细节全部在 [`qq-inbound.md`](qq-inbound.md)，这里只放进度与两条硬规则。
>
> **2026-11-18：主线已切到 P11（官方机器人），本节保留为备选记录。** OneBot 桥已删除，
> 但下面的准入 / 群聊两条硬规则对两条路都成立。

**可行性结论**：可行。卡点不是 QQ 协议，是「进程级单凭据」——所以第一版只能**自用私聊**。

**路线**：原本判断「官方机器人对个人主体只开放频道」，所以走 **LLBot + OneBot v11**。
代价是违反腾讯用户协议、可能风控/封号，**必须用小号**。

> **2026-11-18 修正：官方那条已经走通，新增 P11。** 现行官方 FAQ 放宽到：个人主体可建 5 个机器人、
> 可进「自己是群主」的群、**单聊通过「开发体验用户号码」（≤20 个）开放**，只是不能公开使用。
> 对自用场景足够了，而且不用等任何人审核。于是：**P11 官方通道优先，P10（OneBot）降级为备选**——
> Step 0 已经写完，不亏；两条通道共用的部分是 `AgentChatService`，所以先抽它。

> **2026-11-16 补：NapCat 已停止。** 当天核实：仓库 2026-11-12 03:26 自清空（45 分支→1、
> 761 tag 与全部 release 删除），最后版本 v4.18.42（2026-10-30）。
> 首选协议端改为 **LLBot**，Lagrange 已放弃 OneBot 11。**代码不用改**——桥只依赖 OneBot 11 协议。
> 细节见 [`qq-inbound.md`](qq-inbound.md) §2.1。

- [x] P10-1 路线调研：查实官方场景限制（个人主体：频道 ✅ / 群 ❌ / 单聊 ❌）
- [x] P10-2 边界确认：OneBot 属于 `adapter`（入站），Agent 层零改动；会话 id `qq:c2c:{id}` / `qq:group:{gid}:{uid}`
- [x] P10-3 Step 0 桥：`scripts/qq-bridge/QQBridge.java`（WS + 最小 JSON + 虚拟线程，单文件零依赖）
      **2026-11-18 已删除**——官方通道不需要它；需要时 `git show 3dcd255:scripts/qq-bridge/QQBridge.java`
- [x] P10-4 离线自检：`--selftest` **24 项通过**（JSON 解析 / CQ 码剥离 / 切片不丢字 / 白名单矩阵 / 序列化往返 / 异常兜底文案）
- [x] P10-4b 假服务端 E2E（脚手架未提交）：握手 → 陌生人被拦（0 帧）→ 白名单消息带 `qq:c2c:10001` 打到 `/agent`
      → 2000 字切 3 条（800/800/400）；agent 挂掉时回「【连不上 Agent】…」而不是 `null`；断线退避 3s→6s
- [ ] P10-5 **实跑**：装 LLBot → 小号扫码 → 开 3001 → 私聊发「签到了吗」收到回复（**随 P10 降级搁置**）
- [ ] P10-6 Step 1：落 `adapter/onebot`（**随 P10 降级搁置**；共用的 `AgentChatService` 已抽）
- [ ] P10-7 进群的前置：P8 的 `@ToolMemoryId → userId → 凭据`；在此之前群聊一律不接

**两条硬规则（写在这里，免得将来手滑）**

1. 准入在 QQ 开放平台后台（体验成员 / 可见范围）；应用侧不维护 `QQ_ALLOWLIST`，消息能进到进程就按 `author.user_openid` 识别用户。**默认必须是拒绝**，不是「谁都能用」。
2. 群聊事件现在直接忽略；将来打开时，平台准入挡的是「谁能触发」，挡不住「谁能看见」——回复发在群里，任何人的签到数据都会展示给全群。

**验收**：QQ 私聊里问「今天签到了吗」，回复与签到总览一致；当天有记录文件时，两者也要对得上；
再用一个**不在平台体验成员里**的号发同一句，必须没有任何回复（fail closed 的证据，不是"大概没收到"）。

---

## P11 · QQ 官方机器人（2026-11-18 起）

> 为什么改主意：P10 要等 LLBot 人工开授权（已等 2 天无果），而官方通道是**自助的、合规的、不会突然消失**。
> 代价是协议完全不同，要新写一个适配器；OneBot 那套代码不能复用，但 `AgentChatService` 可以。

**个人主体的实际能力（2026-11-18 查官方 FAQ，[来源](https://bot.q.qq.com/wiki/bot_FAQ/)）**

| 能力 | 个人主体 | 备注 |
|---|---|---|
| 建机器人 | ✅ 5 个 | 身份证实名认证 |
| 单聊 | ✅ 加「开发体验用户」，≤20 个号 | 自用够了 |
| 群聊 | ✅ 只能进自己是群主/管理员的群 | 不公开 |
| 频道 | ✅ | |
| 公开使用 | ❌ | 个人主体不支持 |

**协议事实（这几条决定实现，别照 OneBot 的经验猜）**

- 事件里**没有 QQ 号，只有 openid** → 会话 id 与 userId 都按 openid；准入在平台侧
- 被动回复必须带 `msg_id`（来自 `C2C_MESSAGE_CREATE`），**5 分钟内有效**；
  被动窗口 60 分钟、**每条消息最多回 4 次** → 切片上限要从 8 降到 4
- 接入选 **WebSocket 网关**（官方原话「不依赖公网服务器，适合个人单机使用」），不要 Webhook——
  家庭宽带没有固定公网 IP
- AccessToken 用 AppID + AppSecret 换，7200s 过期，要自己续
- 主动消息受频控且有权限门槛（错误码 `40034105`），**别把它当推送通道**；签到通知仍走独立 webhook

- [ ] P11-1 注册个人开发者 + 创建机器人，拿 AppID/AppSecret，事件订阅切 WebSocket
      （代码只认 `QQ_APP_ID` / `QQ_APP_SECRET`；是否已配好以 `.env` 为准）
- [ ] P11-2 「开发设置」把自己加成开发体验用户，手机 QQ 扫码加机器人
- [x] P11-3 抽 `AgentChatService`（P10/P11 共用；也是 P10-6 的前置）
- [x] P11-4 落 `adapter/qq`：AccessToken 刷新 + WS 握手/心跳/重连 + C2C 事件 → 被动回复
      （包名最终是 `adapter/qq`，不是这里原写的 `adapter/qqofficial`；接第二个平台时若觉得歧义再改，此刻改名最便宜）
- [x] P11-4b 出站收口第一刀：切片抽成 `channel/TextChunker`（修掉「超长单行被平台拒」和「撞 4 条丢尾」两个 bug）；
      签到结果接上 `Notifier`（默认关，开了才发）
- [ ] P11-5 真机验收：手机发「签到了吗」5 秒内收到；平台体验成员外的号不回

**下一步（Step 1，等第二个平台真要接时再做）**：把 `QqBotDispatcher` 里的
白名单 / 去重 / 会话 id / 同会话串行 / 错误文案提成 `channel.MessagePipeline`，
出站收口成 `ReplySender`，QQ 适配器退化成「协议翻译」两件套。
现在不做——**用第二个实现验证抽象**，别对着一个实现提前设计。

**验收**：手机 QQ 里问一句，回复与签到总览一致（当天有记录文件时两者也要对得上）；断网重连后仍能收消息。

---

## P12 · 游戏知识库（未立项）

> 定位里说的「知识库」，不是把攻略站扒下来塞给模型，而是**能引用出处地回答**：
> 答「雷电将军带什么武器」时，用户能看见依据是哪一版数据。

**为什么现在不做**：P9 的 3 天观察还是 0/3，P11 未真机验收。第一条能力没站稳之前，
开第二条主线只会让两边都欠着。

**开工前必须回答的三个问题**（不回答就别写代码）：

1. **数据源**：官方 wiki、游戏内文本导出，还是自己整理？多久更新一次、谁保证？
   数据源决定切分粒度，也决定「答案过期」的风险有多大。
2. **检索形态**：角色数值这类**结构化**数据不该走向量检索；攻略/机制说明才需要。
   先想清楚「哪些问题查表就够，哪些必须读文本」，再决定要不要向量库。
3. **验收**：知识库没有「8/10 命中」这种便宜指标。先定 20 条问题 + 标准答案 + 出处，
   判定看「引用是否正确、是否答非所问」，不是看模型说得像不像。

**边界**：新域 `knowledge/`；不进 `mihoyo`（外部客户端），不写成直连外部搜索的 `@Tool`。
工具只暴露「按主题取知识」，模型不接触原始语料。

---

## P13 · 人设与日常对话（未开工；前置 C1 + C4 已于 11-23 满足）

**现状**（2026-11-23）：`SystemPrompt.compose()` 已经把 prompt 拆成四段，但
`<persona>` 与 `<channel>` 还是空串——四段里真正有内容的只有 `<capabilities>`。
能力声明随工具变（改错就会承诺做不到的事，见 11-12 那次冒烟）、人设要稳定，
两者混在一段文本里，加新能力时必然互相牵制；现在容器有了，内容还是空的。

**开工前置（不属于这一步，但必须先有）**：**两个前置已于 11-23 满足**，可以开工。
但注意：**改完人设必须重跑 `pwsh -File scripts/eval.ps1` 并把分数记进维护记录**，否则等于没做。

- **C1**（Prompt 组装）：✅ 已拆成 `<persona>` / `<capabilities>` / `<channel>` / `<context>` 四段；
- **C4**（可观测与评测）：✅ `TurnTrace` + 20 条金标集已能一条命令跑出分数——**这是 P3 的老教训：
  改完必须有跑批，否则"改了感觉更好"不算结论。**

**这一步只做三件事**：

1. 写 `<persona>`：**从已有的人写文案里归纳口吻，不要发明**。参照物是
   `QqBotDispatcher` 的「已解绑，本机保存的凭据也删掉了。」「二维码过期了，回复「绑定」重新生成。」，
   `CheckinMessages` 的「累计 12 天」——共同特征是：短句、句号收尾、不用感叹号、不用 emoji、不寒暄。
   **让模型向这些人写文案对齐，而不是反过来。**
2. **2~3 对 few-shot（user / assistant）固定语气**：签到成功 / 当天已签 / 能力外。
   这是唯一真能固定语气的手段，比写「请用亲切专业的语气」有效一个量级。
3. 定「不知道就说不知道」的硬规则：知识库没命中时不许用模型自己的记忆补（与 P12 共用这一条规则）。

**评测**（加进 C4 的基线里，不另起一套）：10 条闲聊 + 10 条越界请求（问别人的账号、要发帖、要兑换码），
看拒绝得不得体。

**为什么值得做**：签到一天只发生一次、几秒钟就结束。一个只为这几秒钟存在的产品，
用户不会为它开 QQ——闲聊是留存，不是装饰。

**成本提醒**：SYSTEM 常驻窗口第 1 条，窗口硬上限 60 条、正常还会先被摘要压缩（P7-7）。
人设每多 100 字是**每轮都付**，还挤掉本就不多的对话轮次。**人设要短，few-shot 控制在 2~3 对。**

---

## 暂缓做的事（以及解锁条件）

> 这些不是「永远不做」，是「现在做会亏」。每条都写清触发条件，到点就该重新评估。

- ⏸ **向量库 / RAG** —— **已改判**：知识库是定位的一部分，见 P12。解锁条件不是「有需求」，
  而是 P12 的三个前置问题有答案。
- ⏸ **多 Agent 编排 / 工作流框架** —— 等单 Agent 的工具数 > 6 且描述重叠压不住（`tool-ideas.md` §6）
- ⏸ **计划 / 任务分解与反思循环** —— 触发条件：出现一个必须 ≥3 步、且中途要看结果再决定下一步的**真实**需求。
  签到是单步任务，现在做只会得到一个没人用的规划框架（清单见 `agent-architecture.md` §4）
- ⏸ **多模型路由与降级** —— 触发条件：需要「贵模型规划 + 便宜模型执行」，或出现明确的降级需求。
  现在只有一个 `ChatModel` bean，抽象一个只有一种实现的 provider 层是负资产
- ⏸ **长期记忆 / 用户画像** —— 触发条件：用户开始问「我上次说的那个…」而「摘要 + 最近 3 轮」仍装不下。
  先落几个字段的画像文件，不要上向量库
- ⏸ **持久化 schema 版本 / 数据库** —— 触发条件：第一次出现「旧文件读不了」，或出现跨会话跨年查询
  （「上个月漏签几次」）。前者加 `schemaVersion` + 迁移函数，后者先给 file store 加索引文件
- ⏸ **产品级前端 UI** —— 调试台够验证；等有第二个真人用户再说
- ❌ **同时 debug 米游社签名和 Agent 工具调用** —— 永远别做，两类问题会互相掩盖
- ❌ **主账号做实验** —— 永远别做
- ⏸ **对外多租户 / 开放注册** —— 存储已按用户隔离，但「给自己用」和「给别人用」在合规上
  是两件事（`mihoyo-auth.md` §9），在决定对外之前一律按自用设计

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

# 冒烟：会话记忆的差分验收（Agent 工具用例脚本 11-22 已删，见维护记录）
pwsh -NoProfile -File .\scripts\smoke-memory.ps1

# 诱饵模式：让「能力外」的用例变成真测试（脚本已删，人工在调试台确认不调诱饵）
.\mvnw.cmd -B -ntp spring-boot:run "-Dspring-boot.run.profiles=decoy"

# 看依赖版本是否收敛
.\mvnw.cmd -B -ntp dependency:tree -Dincludes=dev.langchain4j

# 确认 Maven 用的是哪个本地仓库（注意 -D 参数要加引号）
.\mvnw.cmd -q help:evaluate "-Dexpression=settings.localRepository" -DforceStdout

# 打包
.\mvnw.cmd -B -ntp -DskipTests package

# 调接口（HTTP 没有平台身份：带工具的问答会回「请先绑定」，签到只在 QQ 里做）
Invoke-RestMethod "http://localhost:8080/chat?q=你好"
Invoke-RestMethod "http://localhost:8080/agent?q=你好" -Headers @{ "X-Session-Id" = "dev-1" } |
  ConvertTo-Json -Depth 4
Invoke-RestMethod "http://localhost:8080/api/memory/dev-1" | ConvertTo-Json -Depth 4

# 今天的签到结果（没有状态页了，直接读记录文件）
Get-ChildItem "$HOME\.hoyo\checkin" -Recurse -Filter *.json |
  Sort-Object LastWriteTime -Descending | Select-Object -First 3 |
  ForEach-Object { $_.FullName; Get-Content $_.FullName }

# 定时任务失败：控制台搜这个前缀（容器里看 docker compose logs）
docker compose logs | Select-String '\[CHECKIN\]\[ALERT\]'

# 部署时覆盖 .env 位置
java -jar target\HoyoAgent-0.0.1-SNAPSHOT.jar "--spring.config.import=optional:file:./config/.env[.properties]"
```

> 凭据只存在 `~/.hoyo/accounts/`（每用户一个 `.account`，文件名是 `sha256(userId)` 前 16 位）。
> `.env` 里没有 `HOYO_COOKIE` 这个口子了；绑定、换绑、解绑都在 QQ 里完成。

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

- 2026-11-23 契约 C1–C5 收口 + 评测基线首跑（**有代码变更**：C5；139 条测试全绿）：
  1) **C5 工具输出契约**（commit `e3dfb99`）：`CheckinTool` / `CheckinOverviewTool` 去掉 `【结论】/【数据】`，
     内部枚举翻成人话（`SIGNED`→新签成功、`ALREADY`→今天已签、`FIRST_BIND`→首次绑定今天不用签、
     `CAPTCHA`→需要人机验证、`DONE`→全部成功、`SCHEDULED`→定时任务），漏签天数标「（推算）」。
     **理由**：工具输出会原样进上下文，模型倾向照抄，Java 里的措辞等于绕过 prompt 把语气写死。
     另一处改动：`recorder.record` 提到身份判断**之前**——被拒的调用也要记，否则基线看不到「本该调却没调」。
     新增 `ToolOutputContractTest` 4 条（无内部枚举 / 无装饰符 / 推算值标注 / 每角色一行），只查形状不查文案。
  2) **文档回填**：本文契约表补齐 C1–C5 真实状态（此前 C1/C2 还写着「未开工」）；「已知的四处违反」改成
     现状快照 + 各自处置；`agent-architecture.md` §1 五处状态、§2 标题与 C1/C2/C4 的「现在违反」、新增 C5 小节、
     §3 验证表；README 校准日期。**C5 此前在文档里完全不存在**，是这次补的第三份落点。
  3) **评测基线首跑**：`pwsh -File scripts/eval.ps1` → **18/20 通过（90%）**，有效 12 条里 **10/12**，
     恒真 8 条不计入证据。明细在 `target/eval-last.json`。
     - 工具选择 10/10 全对：4 条「帮我签」都只调 `checkinToday`，6 条「签了吗 / 漏几天」都只调 `getCheckinOverview`，
       没有互相误用——C1-a 里「执行 vs 查看」那段分工确实生效了。
     - **唯一的 FAIL 都是格式**：`fmt-01 你能做什么`（330 字）/ `fmt-02 你是谁`（301 字）用了 `**加粗**` 和编号列表，
       长度都没超限。原因不是模型不克制，而是 **`<channel>` 段是空串**——没人告诉它 QQ 不渲染 markdown。
       这正好是 C1-b 的第一个证据：**填 `<channel>` 前后各跑一次，看这两条能不能翻绿**。
  4) **仍未做**：`PERSONA` / `CHANNEL` 还是空串（P13 / C1-b）；`TurnTrace` 无单测；没有 `IRREVERSIBLE` 风险级；
     用例只有 20 条（有效 12）；`/agent` 调试口仍不串行。
  5) **测试数口径**：`.\mvnw.cmd -B -ntp test "-Dgroups=!network"` = **139 条**；
     11-23 早些时候的 147 含临时评测类，不作为基线。
- 2026-11-23 C3 落地（**有代码变更**，132 条测试全绿）：
  1) 新增 `channel/SessionSerializer`：64 条带 `ReentrantLock`；`Math.floorMod` 取模（`hashCode()` 可能为负，
     用 `%` 会直接越界）；异常走 `finally` 释放；入口 `call(Supplier)` / `run(Runnable)`。
  2) `QqBotDispatcher` 用 `serializer.call(sessionId, ...)` 包住**整段** `chat.ask`——包的是
     「读记忆 + 工具调用 + 写回」这个整体，不是只包 memory 的读写。
  3) 新增 `SessionSerializerTest` 4 条（同会话串行 / 不同会话并行 / 异常释放锁 / null 容忍）；
     类级 `@Timeout` 让"锁泄漏"表现为**失败**而不是挂住。
  4) **核对后推翻上一版文档的说法**：身份并非"三处各拼"，只在适配器构造一次并注册进 `SessionRegistry`，
     所以**不做** `RequestContext`（那会是第二条身份通路）。C3 的前两条本来就成立，落地时只缺第三条。
  5) 已知例外：`/agent` 调试口同一 session 未串行——故意，单人顺序使用；它变成真实入口时再收口。
- 2026-11-23 新增部件轴（**无代码变更**）：
  1) 新建 `docs/agent-architecture.md`：23 个模块 × 4 层（编排 / 认知 / 治理 / 工程）+ 跨阶段契约
     C1–C4 + 每个部件的验证方式 + 推迟清单（只写触发条件）+ 与 AstrBot 的模块对照。
  2) 本文新增「跨阶段契约 C1–C4」一节；顶部声明「本文只负责何时做 / 做没做」；
     能力地图下注明它只是产品能力轴。
  3) P13 改为「前置：C1 + C4」，原来混在一起的三步拆成「两个前置 + 本步三件」，并把
     「人设从已有 Java 文案归纳，不要发明」写死——避免又造一个人设。
  4) 暂缓区补 4 条（计划与反思、多模型路由、长期记忆、schema 版本 / 数据库），每条带触发条件。
  5) **起因**：四个部件级债务（同会话串行 / 依赖方向测试 / 评测脚本已删 / prompt 无版本）
     散落在 P 阶段散文里，永远排不进任何一期。加这条轴是为了让它们可见，**不是为了增加待办**——
     已有 13 个 P 且 P9 是 0/3、P11 未真机验收，本身是欠着的。
- 2026-11-22 文档校准（**无代码变更**）：
  1) **定位收宽**：新增根目录 `README.md`；本文标题从「学习路线」改为「路线图与任务清单」，
     目标从「米游社 Agent 工具 / 学习 Agent 开发」改为「米哈游系游戏助手，签到是第一个能力」；
     顶部加「能力地图」，进度表补 P12（知识库）/ P13（人设与日常对话）；
     「学习期不要做的事」改为「暂缓做的事（以及解锁条件）」，RAG 从「不做」改判为 P12。
  2) **事实校准**：P9~P11 与附录 B 里指向已删除的 `/api/auth/*`、`/api/checkin/*`、
     `login-qr.ps1`、`smoke-agent.ps1` 的命令，全部改成 QQ 流程 / 记录文件 / 现有端点；
     P9-8 的记录样例 `userId=local` 改为 `qq:<openid>`；P9-9/P9-11 的验证路径改成
     「重新扫码」或直接调 `PassportClient.exchangeV2()`；P7 发现③ 标注已由
     `TurnWindowChatMemory` 解决（不再是「先记账不做」）；P7-3 的前置条件已满足。
  3) **边界预留**：新增第 4 条边界（知识库属于独立域 `knowledge/`，不塞 `mihoyo`、
     不直接写成 `@Tool`），但**不建空包**。
  4) 仍**故意没做**：`tool-ideas.md` 未改写（它的范围是「账号数据类工具」，扩成能力地图
     要等 P12 有数据源决策）；`SystemMessage` 未拆「人设 / 能力声明」（随 P13 一起做）。

- 2026-11-21 包结构调整（无行为变更；`clean test` 132 条全绿）：
  1) `agent/notify` → `channel/notify`（4 个文件，只改 `package`）：`mihoyo.checkin` 之前反向依赖
     `agent.notify`，`mihoyo` 与 `agent` 成环，违反上面第 2 条边界。`Notifier` 自己的注释写着
     "出站通知通道、签名里不出现业务名词"，它本来就不是 agent 概念。挪完层序单向：
     `common ← channel ← mihoyo ← agent ← adapter`（实测 `mihoyo → agent` 从 2 处降到 0）。
  2) 三个"按类型种类"的单类包消失：`mihoyo/dto/GameRole` → `role/`（跟着消费方走）、
     `mihoyo/enums/MihoyoHost` 与 `mihoyo/sign/DsSigner` → `transport/`（前者与
     `Profiles`/`HeaderProfile` 同属请求头套装，后者是 HTTP 管道的一部分）。
     分包轴线从"它是什么种类的类型"改成"它服务于谁"。
  3) `DsSignerTest` 跟着搬到 `mihoyo/transport` 并改 `package`，`mihoyo/sign` 包彻底消失
     （测试留在已无主代码的包里会让包名说谎，也会挡住将来"测试包必须与主代码包一致"的规则）。
  4) **依赖方向测试仍未做**：这次是靠手动扫描确认无违规。也就是说上面那几条边界现在还是
     "约定"，不是"事实"——下次谁再写一个反向 import，没有任何东西会红。
     候选实现：ArchUnit（一个 test 依赖，扫字节码）或零依赖的源码 import 扫描（约 30 行）。

- 2026-11-21 持久化收口（无行为变更；132 条测试全绿）：
  1) 新增 `common/storage/LocalFileStore`：**原子写 4 份、sha256 文件名 3 份、默认根目录 6 处硬编码、
     列目录 4 处**，全部收成一处。六个文件只声明"根在哪"：`FileAccountStore` / `FileMemoryStore` /
     `FileCheckinRecordStore` / `DeviceIdProvider` / `PassportDevice` / `LegacyCredentialFile`。
     顺带修掉 `FileMemoryStore.save` 的一个隐患：`fileOf(sessionId)` 原本在 `try` 里，空 sessionId
     时 `catch` 里再抛一次，日志打不出来反而炸。
  2) `hoyo.state-dir` 统一状态根（默认 `~/.hoyo`）：此前 `device-id` / `passport-device` /
     `credential.properties` 三个路径**彻底写死**，换目录必须改代码。现在一个配置整体搬家，
     子目录仍可用 `hoyo.account-dir` / `hoyo.checkin.dir` / `hoyo.memory.dir` 单独覆盖。
  3) `CheckinRecordStore` 抽成接口，实现改名 `FileCheckinRecordStore`；契约里删掉 `Path root()`，
     换成 `String location()`——数据库实现给不出路径，留着它等于逼未来实现编一个假路径。
     静态 `CheckinRecordStore.describe()` 移到 `CheckinRecord.describe()`。
     调用方改动只有 `CheckinScheduler` 两行（日志里的 `root()`→`location()`、`describe()` 变成实例方法），
     `CheckinOverviewService` / `BindingService` **一行没改**。
  4) `@ConditionalOnProperty(hoyo.storage.backend=file)` 加到三个 file 实现上：以后加
     `Jdbc*Store` 是"加一个类 + 改一行配置"，不是"改 6 个文件 + 动 4 个调用点"。
     注意删掉 `file` 实现时**没有替代 bean 会启动失败**，这是刻意的：宁可起不来，不要静默不落盘。
  5) 配置补漏：`hoyo.memory.dir` / `hoyo.memory.retention` 代码一直在读、yml 里却看不见，已补上。
  6) 实测验证：`HOYO_STATE_DIR=target/state-check` 起一次 `@SpringBootTest`，日志里
     `记录目录=...\target\state-check\checkin`、`passport-device` 也落在该目录 → 搬迁确实生效。
  7) 仍然**故意没做**：`SessionRegistry` / `QqBotDispatcher.seen` / `QrLoginService.sessions`
     保持纯内存。它们的抽象方向取决于"要不要多副本"，现在设计基本一定猜错；
     等真要上多副本时，那是一次架构决策（外置会话 / 分布式锁），不是加几个 interface。

- 2026-11-20 结构收口（除 source 字段取值外无行为变更；132 条测试全绿）：
  1) 删 `CredentialProvider`：它和 `AccountService` 各缓存一份凭据，扫码换绑后
     `GET /api/auth/status` 还会报启动时的旧快照。来源信息并入 `AccountService.Source`
     （`ACCOUNT/LEGACY/ENV/NONE`），该接口 `source` 字段的取值随之变化（原来是 `store/legacy/env`）。
  2) `hoyo.hosts` 对签到生效：`CheckinGame` 不再硬编码 baseUrl，改成 `MihoyoHost` 枚举 +
     `MihoyoEndpoints`，并补 `NAP` host（`act-nap-api`）。此前配了覆盖，签到仍会绕过配置打真网。
  3) 命名：`Result`→`ApiResult`、`MemoryPersistence`→`MemoryStore`（+`FileMemoryStore`）、
     `CheckinService.Summary`→`CheckinRunResult`、`RoleResult`→`CheckinRoleResult`（独立文件，
     切断 `CheckinRecord` 对 Service 嵌套类型的依赖）、`mihoyo.record`→`mihoyo.gamerecord`、
     `sendC2c`→`sendC2C`、`MiyousheHost`→`MihoyoHost`。
  4) 清理：空包 `adapter/onebot`、`config`；零引用枚举 `GameBiz`；`ErrorCode` 的知识库 6xxx、
     限流 8xxx；`GlobalExceptionHandler` 里 RestTemplate / 文件上传三组永不命中的分支；
     14 处未使用 import；两个自称"临时桥"的无参重载。
  5) 脱敏收口：9 份 `mask()` 合并为 `common/log/Mask`（`id` / `tail` / `prefix` 三种规则），
     长度 ≤ 8 的 id 一律整串隐掉。

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
- 2026-11-22 删除 local 账号（单用户时代收尾）：
    - 身份只有一个来源：`SessionRegistry` 由平台适配器注册；查不到就是"没有身份"，不再回退 `local`，
      工具层直接回「请先绑定」（`CheckinTool` / `CheckinOverviewTool`）。
    - 删除 `AuthController`、`CheckinController`、`LegacyCredentialFile`；`AccountService` 只读账号目录，
      `Source` 枚举与 `sourceOf()` 一并删除；`QrLoginService.refreshV2()` 删除（只被 Web 调）。
    - `CheckinScheduler` 只保留 `runAll`；删除 `run/runManual/status`、`hoyo.user-id` 与空账号时的占位记录。
    - 配置删除 `HOYO_COOKIE` / `HOYO_USER_ID`；Web 删掉绑定/角色/签到面板，改为聊天 + 记忆查看；
      删除 `scripts/login-qr.ps1` 与 `scripts/smoke-agent.ps1`。
    - 迁移：`~/.hoyo/accounts` 里的旧 `local` 账号要么改 `user_id` 重命名为 `qq:<openid>`，
      要么删除后重新在 QQ 里绑定；签到记录同理。
- 2026-11-23 调试台新增只读聊天历史（P7-6）：
    - 新包 `agent/transcript`：`TranscriptStore` 接口 + `FileTranscriptStore`（每会话一个 `.jsonl`，
      只追加；`seq` 会话内递增用于分页；文件名 sha256 前 32 位，不把 openid 写进文件系统）。
      另加 `TranscriptExchange/Turn/Session/Page` 四个扁平 record 和 `TranscriptProperties`。
    - 记录点收在 `AgentChatService#ask`：只有 `userId` 非空的轮次才写（QQ），浏览器 / 一次性会话不写；
      失败轮也写（`assistant=null` + `error` 摘要），避免"用户问了但历史里没有"。
    - 新只读接口 `GET /api/transcripts` 和 `GET /api/transcripts/{sessionId}`；**没有 POST/DELETE**。
      读不 `touch` 会话、不续 TTL；`DELETE /api/memory/{id}` 只清窗口，不删历史。
    - 历史默认 `<state-dir>/transcripts`、保留 90 天，每天 04:40 清理；`hoyo.transcript.enabled=false` 可整体关。
      环境变量 `HOYO_TRANSCRIPT_ENABLED/DIR/RETENTION`。
    - 调试台左栏加「用户聊天记录」：按 userId 分组，组内每个会话单独一条（掩码 userId、轮数、最后时间），
      点开 `<dialog>` 看全文，「加载更早」按 `seq` 往前翻；页面上没有任何编辑 / 删除入口。
    - 单测：`FileTranscriptStoreTest` 11 条 + `TranscriptControllerTest` 2 条；全量 198 条通过。
    - 已知限界：历史从这一版开始积累，之前的轮次已被窗口裁掉，无法回填；调试台无鉴权，
      README 已提醒只在本机 / 可信内网使用。
- 2026-11-23 会话摘要压缩（P7-7）：
     - 新增 `agent/memory/ConversationCompactor`：窗口超过 `trigger-messages`（默认 60 的 2/3 = 40）时，
       保留最近 `keep-turns`（默认 3）个完整轮次，把更早的轮次渲染成文本交 `ChatModel` 摘要，
       产出 `【更早对话摘要】` 开头的 `SystemMessage` 放在窗口头部；摘要失败 / 为空 / 没变短都原样退回。
     - `MemoryProperties` 增加 `compaction` 配置，`max-messages` 默认从 20 提到 60（硬上限）；
       trigger 强制 `< maxMessages`，避免还没压缩就被 `trim` 掉；`TurnWindowChatMemory` 改为先压后裁。
     - 配置 `hoyo.memory.compaction.{enabled,trigger-messages,keep-turns,max-summary-chars}`，
       环境变量 `HOYO_MEMORY_COMPACTION_*`。
     - 单测：新增 `ConversationCompactorTest` 9 条、`MemoryPropertiesTest` 扩到 5 条、
       `TurnWindowChatMemoryTest` / `FileMemoryStoreTest` 各 +1；全量 211 条通过。
     - 坑：给 `MemoryProperties` 这个 record 加第二个构造器后，Spring Boot 放弃构造器绑定、
       转找无参构造器，启动报 `NoSuchMethodException: MemoryProperties.<init>()`。
       记录型 `@ConfigurationProperties` 只能保留 canonical 构造器。
- 2026-11-23 生产化 P0 可靠性护栏：
    - `/agent` 也走 `channel/SessionSerializer`，同会话并发不再互相覆盖；一次性会话 id 每次新，等价直通。
    - `SessionMemoryStore` 的 get/update/delete 改为 `ConcurrentHashMap#compute` 原子化；`restore` 只 load
      不 put，避免在 compute 内二次 put 破坏原子性。
    - `server.address` 默认 `127.0.0.1`（调试台无鉴权），`compose.yaml` 设 `SERVER_ADDRESS=0.0.0.0`。
    - 新增 `AgentControllerTest` 2 条（同会话串行 / 一次性会话 evict）；全量 213 条通过。
- 2026-11-23 生产化 P1 SQLite 存储：
    - 新增 `common/storage/{SqliteProperties,SqliteDb}`（WAL、busy_timeout、4 表 3 索引）。
    - 新增 `agent/memory/SqliteMemoryStore` 与 `agent/transcript/SqliteTranscriptStore`，由
      `hoyo.storage.backend=sqlite` 装配；file 实现加 `matchIfMissing` 条件。
    - 账号与签到记录去掉 `@ConditionalOnProperty`，固定文件实现——否则 sqlite 下缺
      `CheckinRecordStore` bean，启动直接失败。
    - 迁移：记忆按会话懒迁移；聊天历史在 `ApplicationReadyEvent` 一次性导入旧 `.jsonl`，meta 打标，旧文件保留。
    - 配置 `hoyo.storage.sqlite.{path,busy-timeout}`；`docker compose` 默认 `sqlite`。
    - 单测新增 `SqliteMemoryStoreTest` 7 条、`SqliteTranscriptStoreTest` 8 条、`SqliteBackendTest` 1 条；
      全量 229 条通过；18080 冒烟验证旧 JSONL 迁移 + `GET /api/transcripts`。
- 2026-11-23 Phase A（会话类型与筛选）：
    - 新增 `common/conversation/ConversationKeys`，从 sessionId 推导 `conversationId` / `kind`
      （c2c / group / other，仅用于展示与归档，不参与身份判断）；`TranscriptTurn` / `TranscriptSession`
      增加两字段，file 旧行按 sessionId 回退，SQLite 用 `ensureColumn` 补列并回填。
    - 移除 `QQ_ALLOWLIST`：`QqBotProperties`、`application.yml`、`.env.example`、dispatcher 检查全部删除，
      准入改由 QQ 开放平台后台配置。
    - 调试台「用户聊天记录」增加全部/私聊/群聊筛选与掩码搜索，会话标签显示私聊/群聊，
      详情头部显示类型 / 掩码用户 / 掩码会话 id；接口仍只有 GET。
    - 测试 227 条通过。
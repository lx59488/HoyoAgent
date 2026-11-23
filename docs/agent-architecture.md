# HoyoAgent Agent 架构：模块全景与跨阶段契约

> **本文回答**：一个完整 agent 有哪些部件、HoyoAgent 缺哪些、边界怎么定、每个部件怎么验证。
> **本文不回答**：什么时候做、做到哪了——那是 [`ROADMAP.md`](ROADMAP.md) 的事。
>
> 读者是「要加下一个能力之前的我」：加工具、加知识库、加人设之前，先在这里确认自己动的是哪一层。
> 定位本身不在这里（见 `ROADMAP.md` 顶部），子系统事实也不在这里（`mihoyo-auth` / `qq-inbound` / `tool-ideas`）。

**状态：边界已定，多数模块未实现。最后更新 2026-11-23。**

---

## 0. 为什么单独有这份文档

`ROADMAP.md` 的能力地图只有**产品能力轴**（账号 / 数据 / 知识 / 对话 / 通道），它回答「用户能做什么」。
它装不下**部件轴**——「agent 有哪些模块」。后果不是理论上的，四个部件级债务已经因此悬空：

| 债务 | 现在写在哪 | 属于哪个部件 | 谁在排期 |
|---|---|---|---|
| 同会话串行 | `ROADMAP` P11 Step 1 的一句话 | 并发一致性 | 没人（被当成重构任务） |
| 依赖方向测试 | `ROADMAP`「四条边界」末尾 | 架构约束 | 没人 |
| 评测脚本已删 | `ROADMAP` P3 复盘 / 11-22 记录 | 评测 | 没人（只剩方法论） |
| prompt 无版本 | 不存在 | 配置与实验 | 没人 |

它们都不产生产品能力，但缺了会挡住所有能力。**两张轴必须分开记，否则第二张永远不存在。**

---

## 1. 模块全景（23 个，四层）

状态只有四个值，不许出现第五种：

- `✅ 有` —— 能用，且有测试守着
- `🟡 半个` —— 有实现，但边界没定死或有已知缺口
- `⬜ 无·已定触发` —— 现在不做，**触发条件写在 §4**；只有这一类允许进 ROADMAP 排期
- `❌ 无·不做` —— 明确不做，理由写在 §4

### 1.1 编排层（一次请求怎么流动）

| 模块 | 状态 | 落点 | 缺什么 | 归属 |
|---|---|---|---|---|
| 入站适配 | ✅ | `adapter/http`、`adapter/qq` | — | P11 |
| 身份解析（会话→用户→凭据） | 🟡 | `channel/SessionRegistry`、`@MemoryId` | 缺统一 `RequestContext`，身份在三处各拼一次 | C3 |
| **Prompt 组装** | ⬜ | 未建 `agent/prompt/` | 无分段、无版本、改一个字要重编译 | **C1** |
| 模型路由与降级 | ⬜ | 单 `ChatModel` bean | 无 provider 抽象、无 fallback | §4 |
| 工具注册与选择 | 🟡 | AUTOMATIC 接线，2 个工具 | 无注册表、无风险级、无一致性测试 | **C2** |
| 循环治理（步数/超时/预算） | ⬜ | 框架的 tool loop | 无最大步数、无单轮超时，跑飞就是死循环烧钱 | C4 |
| 输出整形 | 🟡 | `channel/TextChunker` | 缺「通道决定文体」的提示词约束（markdown / 长度） | C1 |

### 1.2 认知层（agent 知道什么、决定什么）

| 模块 | 状态 | 落点 | 缺什么 | 归属 |
|---|---|---|---|---|
| 短期记忆 | ✅ | `TurnWindowChatMemory`、`SessionMemoryStore`、`FileMemoryStore` | 并发不安全（见 C3） | C3 |
| 长期记忆 / 用户画像 | ⬜ | — | 跨会话偏好（「我主玩原神」）无处可存，也不该塞窗口 | §4 |
| 知识（RAG / 结构化） | ⬜ | 规划 `knowledge/` | 无；边界已定（不进 `mihoyo`、不写成直连检索的 `@Tool`） | P12 |
| 计划 / 任务分解与反思 | ⬜ | — | 无 | §4 |
| 子代理 / 编排 | ⬜ | — | 无 | §4 |
| 主动性 | 🟡 | `CheckinScheduler` + `Notifier` | 现在是「定时任务」，不是「agent 决定要不要说」 | P9-9 |

### 1.3 治理层（能做什么、不许做什么）

| 模块 | 状态 | 落点 | 缺什么 | 归属 |
|---|---|---|---|---|
| 权限·谁能用 | ✅ | `QQ_ALLOWLIST`（空 = 谁都不回） | — | P11 |
| 权限·能做什么 | ⬜ | — | 工具无风险分级（读/写/不可逆），写工具无确认 | **C2** |
| 事实约束（不编造） | 🟡 | `@SystemMessage` 里的声明 | 无「知识未命中就说不知道」的可执行规则、无出处 | C1 + P12 |
| 错误分类与降级 | 🟡 | `mihoyo/error/MihoyoError`、`describe(e)` | 缺 `ErrorKind` → 人话的映射表；重试策略未定 | P5 |
| 隐私与脱敏 | ✅ | `common/log/Mask`、`Credential.toString()` | 缺一条「日志里不出现 cookie」的测试 | P5 |

### 1.4 工程层（怎么知道它还好）

| 模块 | 状态 | 落点 | 缺什么 | 归属 |
|---|---|---|---|---|
| 可观测 + 成本 | 🟡 | `agent/observability/ToolCallRecorder` | 无 traceId、无 token 用量、无延迟、无预算 | **C4** |
| 评测 | 🟡 | 方法论很强（P3 / `DecoyTool`），脚本已删 | 无一条命令能跑起来的基线 | **C4** |
| 配置与实验 | 🟡 | `.env` + `application.yml` | prompt 无版本号、无 feature flag | §4 |
| 持久化 | ✅ | 三套 file store | 无 schema 版本，格式一变旧文件读不了 | P8 |
| 并发一致性 | 🟡 | 每消息一虚拟线程；调度器有 `ReentrantLock` | 同一 session 无串行化 | C3 |

---

## 2. 跨阶段契约 C1–C4

契约不是里程碑：它没有「完成」这个状态，只有「今天有没有被违反」。
所以编号用 `C` 而不是塞进 `P` 序列——`P` 做完打 tag，`C` 每天都要守。

### C1 · Prompt 组装契约

**约束**：system prompt 由固定四段按固定顺序拼接，段间用 XML 标签分隔；每段有唯一所有者，且不得越界（人设不写能力，能力不写语气）。

| 序 | 段 | 所有者 | 内容 | 变化频率 |
|---|---|---|---|---|
| 1 | `<persona>` | 人 | 我是谁、怎么说话、不确定时怎么说 | 极低（改它必须走 C4 的评测） |
| 2 | `<capabilities>` | C2 的注册表 | 能做什么 / 不能做什么 | 随工具变 |
| 3 | `<channel>` | 适配器 | QQ：纯文本、单条 ≤600 字、最多 4 条、不用 markdown | 随通道变 |
| 4 | `<context>` | 运行时 | 今天几号、绑定的是谁、群聊上下文 | 每轮变，现在为空（留插槽） |

**为什么没有单独的「安全」段**：本项目没有健康模式需求；真正的硬约束只有「不编数据」，它属于 `<capabilities>` 的「不能做什么」，单独成段会让它被当成可选项。

**落点**：新包 `agent/prompt/`（`PromptSection` 枚举 + `SystemPromptComposer`）。
分两步，先小后大：

1. `@SystemMessage` 只留 `<persona>` + `<capabilities>`，动态段用模板变量（`@V`）注入；
   若当前版本不支持注解内变量，就直接退到第 2 步，不要在注解上绕。
2. 段数超过 3 个时改成 `AiServices.builder(...).systemMessageProvider(...)` —— 那才是真正的组装点。

**验证**：`SystemPromptComposerTest` —— 段顺序固定、标签闭合、空段不出现、总长度不超过阈值（防止悄悄膨胀，窗口只有 20 条）。

**现在违反**：`MihoyoAssistant` 是一整段注解文本，人设与能力混在同一段里；prompt 没有版本号。

---

### C2 · 工具治理契约

**约束**（四条，都是一句话能判定的）：

1. **能力清单的唯一真相是一张注册表**（枚举），启动期断言「注册表 ↔ 容器里的 `@Tool` 方法名」双向一一对应，不等就失败。
2. **每个工具必须声明风险级**：`READ` / `WRITE` / `IRREVERSIBLE`。`WRITE` 以上必须满足「幂等可判断 + 失败可分类 + 人可复核」，三缺一不许上线；`IRREVERSIBLE` 现阶段一律不做。
3. **非产品工具一律 `@Profile`**（`DecoyTool` 已经这么做）。AUTOMATIC 接线意味着任何 `@Tool` bean 都会出现在每个 AiService 面前。
4. **工具超过 6 个**时切 `EXPLICIT` wiring，或按场景拆两个 AiService。

**落点**：`agent/tool/Capability.java`（枚举）+ `@ToolRisk` 注解 + `CapabilityConsistencyTest`。

**验证**：`CapabilityConsistencyTest` —— 名字集合相等、每个工具都有风险级、`<capabilities>` 里每一句都能在注册表找到出处。

**现在违反**：AUTOMATIC 接线；无风险级；无一致性测试。能力声明已经漂移过三次（11-07 深渊 / 11-12 签到 / 11-13 删角色），三次都靠人肉发现。

---

### C3 · 身份与会话契约

**约束**：

1. 身份只在**适配器**解析一次，向下传只读的 `RequestContext(sessionId, userId, channel, now, traceId)`。
2. `uid` / `userId` **永不作为工具参数**（`mihoyo-auth.md` §7）。工具只通过 `@ToolMemoryId` 拿会话键，再经 `SessionRegistry` 换 userId。
3. **同一 sessionId 的请求串行执行。** 一轮 5~20 秒，用户连发两条必然重叠。

**为什么第 3 条是契约不是优化**：`TurnWindowChatMemory.add()` 是「读 → 加一条 → 写回」（`getMessages` 后 `updateMessages`），没有任何锁；`QqBotDispatcher` 是每条消息一个虚拟线程。两条并发消息会各自读到同一份 20 条、各自 +1 写回，**后写的覆盖先写的**——不报错，只是模型无故失忆一格。

**落点**：`channel/RequestContext` + `channel/SessionSerializer`（striped lock 即可，十行）+ `QqBotDispatcher` 改用它。

**验证**：`SessionSerializerTest` —— 同 key 两线程并发时第二个必须等第一个；不同 key 不互斥。加上 `TurnWindowChatMemory` 的并发读写测试。

**现在违反**：`RequestContext` 不存在；`TurnWindowChatMemory.add()` 无锁保护；`ROADMAP` 的 P11 Step 1 把「同会话串行」当成重构待办，实际它是正确性问题。

---

### C4 · 可观测与评测契约

**约束**：

1. 每轮落一条 `TurnTrace`：`traceId` / `sessionId` / `promptVersion` / model / toolsCalled / tokensIn·Out / latencyMs / outcome / errorKind。
2. **prompt 带版本号**，与 C1 的四段一起改。没有它，改完无法归因。
3. **改 prompt / 工具 / 模型之前，先跑基线。** 基线是一条命令就能跑、能出分数的东西。

**落点**：`agent/observability/TurnTrace`（扩展现有 `ToolCallRecorder`，它的 ThreadLocal 思路保留、字段扩宽）+ 金标集（JSON）+ 一个 runner（测试类或 `scripts/eval.ps1`）。

**验证**：runner 输出 `N/M 通过 · 分数`；日志里每一轮都能按 `traceId` 串起来。

**现在违反**：`ToolCallRecorder` 只记工具名、只在内存、`drain()` 一次就没了；没有 traceId / token / 延迟；冒烟脚本 2026-11-22 已删，方法论还在但**没有可执行的东西**。

---

## 3. 每个部件「怎么证明它是好的」

**规则：写不出验证方式的部件 = 还没设计完。** 实现前先把这一行补上。

| 部件 | 现在就能写的验证 |
|---|---|
| Prompt 组装 | `SystemPromptComposerTest`：段顺序 / 标签闭合 / 空段 / 总长度上限 |
| 工具治理 | `CapabilityConsistencyTest`：注册表 ↔ `@Tool` 双向相等 + 每个工具有风险级 |
| 身份与会话 | 并发测试：同 session 串行、不同 session 并行；`TurnWindowChatMemory` 并发不丢轮次 |
| 短期记忆 | 已有 `TurnWindowChatMemoryTest` / `SessionMemoryStoreTest` / `SessionMemoryStorePersistenceTest` |
| 评测基线 | 金标集 ≥ 20 条，一条命令出分数，分数记账到 `ROADMAP` 维护记录 |
| 事实约束 | 能力外提问 ≥ 5 条，全部回答「无法查询」且不调工具（`DecoyTool` 下跑才有信息量） |
| 错误分类 | 同一个 `MihoyoError` 的每种 `ErrorKind` 都有确定的人话文案，单测逐条断言 |
| 通道文体 | 抓一次真实回复：无 markdown 表格 / 标题，总长 ≤ 4×600 字 |
| 隐私 | 日志断言：任意一次签到跑完，日志里搜不到 cookie 值的任意 8 位片段 |
| 主动性 | 定时任务失败后**不重试**，且 `[CHECKIN][ALERT]` + 记录文件里的 `kind` 都能对上（P9-8 的三个决定） |

---

## 4. 推迟清单与触发条件

**只写「什么条件下才做」，不写「将来要做」。** 条件没到就做，等于自己给自己加维护面。

| 模块 | 触发条件 | 一旦触发，先动哪里 |
|---|---|---|
| 计划 / 任务分解 | 出现一个必须 ≥3 步、且中途要看结果再决定下一步的**真实**需求 | 先加 `<plan>` 段，再谈循环治理；不要上规划框架 |
| 反思 / 自我校验 | 工具失败后「换个参数重试」成为常态（现在签到失败是 `AUTH`，重试毫无意义） | 先在工具层把 `ErrorKind` 分对，不要加反思循环 |
| 子代理 / 编排 | 工具 >6 且描述压不住（`tool-ideas.md` §6②） | 先切 `EXPLICIT` wiring，再考虑子代理 |
| 多模型路由 | 需要「贵模型规划 + 便宜模型执行」，或有明确降级需求 | 先抽象模型选择点，不要引入 provider 全家桶 |
| 长期记忆 / 画像 | 用户开始问「我上次说的那个…」而窗口装不下 | 先落显式画像文件（几个字段），不要上向量库 |
| 知识库 / RAG | P12 的三个前置问题有答案 | 见 P12 |
| 配置与实验（flag / A-B） | prompt 有版本号且基线稳定之后 | 先做 `promptVersion` 记账，A/B 最后 |
| 持久化 schema 版本 | 第一次出现「旧文件读不了」 | 加 `schemaVersion` 字段 + 迁移函数，不要上 Flyway |
| 数据库 | 出现跨会话 / 跨年查询（「上个月漏签几次」） | 先给 file store 加索引文件，验证查询形态 |
| 前端 UI | 有第二个真人用户 | — |
| `IRREVERSIBLE` 工具 | 现阶段不做 | 真要做时先在适配器加二次确认，不要做审批流 |

**明确不做**（不是推迟，是方向性拒绝）：社区发帖 / 点赞 / 评论、兑换码兑换、把 `uid` 做成工具参数、为 LLM 调用自建向量记忆、健康模式安全段。理由见 `tool-ideas.md` §5 与 `mihoyo-auth.md` §7。

---

## 5. 与 ROADMAP 的边界

| 文档 | 拥有什么 | 不拥有什么 |
|---|---|---|
| `ROADMAP.md` | 时间轴、阶段验收、决策记录、产品能力地图 | 模块边界、契约细节、验证方式 |
| 本文 | 模块矩阵、C1–C4、验证方式、触发条件 | 排期、进度、验收记录 |

**三条更新规则**：

1. 模块状态变了 → 改本文对应行 + 指回它的 P 编号；不要改 ROADMAP 的进度表。
2. P 阶段完成 → 只改 ROADMAP；只有当它把某个 ⬜ 变成 ✅/🟡 时，才回来改本文一行。
3. **只有「⬜ 无·已定触发」允许进 ROADMAP 排期。** ✅/🟡 的改进只能挂在已有 P 下当子项——否则两份文档会各自许诺一次。

---

## 附录 A · 与 AstrBot 的模块对照

拿它当**清单**对照，不当蓝本照抄。差异的根源是我们单用户、单机器人、无 WebUI 编辑需求。

| AstrBot 模块 | 我们的对应 | 结论 |
|---|---|---|
| `persona`（多角色 / 文件夹 / 导入导出 / 会话级覆盖） | 无（一份 `<persona>` 常量） | **不抄**：单机器人，多一张表就是纯负债 |
| `begin_dialogs`（user/assistant 成对 few-shot） | 无 | **抄**：唯一真能固定语气的手段，2~3 对即可 |
| `<persona_instructions>` 等 XML 分段 + 固定组装顺序 | 无 | **抄** → C1 |
| `LLM_SAFETY_MODE_SYSTEM_PROMPT`（健康模式，前置） | 无 | **不抄**：签到机器人不需要，且每轮都付费 |
| `LIVE_MODE_SYSTEM_PROMPT`（通道决定文体） | 无 | **抄** → `<channel>` 段 |
| `EXTRA_PROMPT.md`（工作区级追加） | 无 | 暂不抄：等有"每个会话不同规则"的真实需求 |
| `<system_reminder>You are in a group chat` | 无（群聊事件直接忽略） | 等开会群聊时抄进 `<context>` 段 |
| `custom_error_message`（人格级报错话术） | `describe(e)` + dispatcher 文案 | **不抄**：已经在做 |
| `persona.tools` / `skills` 白名单 | AUTOMATIC wiring，全挂 | 抄思路 → C2 第 4 条（>6 切 EXPLICIT） |
| `Trace` / `Session` 观测台 | `ToolCallRecorder` | 抄字段 → C4 |

**一句总结**：能抄的是**提示词形状**（分段、标签、few-shot、通道文体）和**工具治理**；不能抄的是**多角色基础设施**。

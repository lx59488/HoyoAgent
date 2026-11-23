# HoyoAgent Agent 架构：模块全景与跨阶段契约

> **本文回答**：一个完整 agent 有哪些部件、HoyoAgent 缺哪些、边界怎么定、每个部件怎么验证。
> **本文不回答**：什么时候做、做到哪了——那是 [`ROADMAP.md`](ROADMAP.md) 的事。
>
> 读者是「要加下一个能力之前的我」：加工具、加知识库、加人设之前，先在这里确认自己动的是哪一层。
> 定位本身不在这里（见 `ROADMAP.md` 顶部），子系统事实也不在这里（`mihoyo-auth` / `qq-inbound` / `tool-ideas`）。

**状态：边界已定，契约 C1–C5 全部落地（2026-11-23），少数部件仍有已知缺口（见 §1 各行）。**

---

## 0. 为什么单独有这份文档

`ROADMAP.md` 的能力地图只有**产品能力轴**（账号 / 数据 / 知识 / 对话 / 通道），它回答「用户能做什么」。
它装不下**部件轴**——「agent 有哪些模块」。后果不是理论上的，四个部件级债务已经因此悬空：

| 债务 | 现在写在哪 | 属于哪个部件 | 谁在排期 |
|---|---|---|---|
| 同会话串行 | `ROADMAP` P11 Step 1 的一句话 | 并发一致性 | 没人（被当成重构任务）→ 11-23 由 C3 收口 |
| 依赖方向测试 | `ROADMAP`「四条边界」末尾 | 架构约束 | 没人 |
| 评测脚本已删 | `ROADMAP` P3 复盘 / 11-22 记录 | 评测 | 没人（只剩方法论）→ 11-23 由 C4 收口（`scripts/eval.ps1`） |
| prompt 无版本 | 不存在 | 配置与实验 | 没人 → 11-23 由 C1 收口（`SystemPrompt.VERSION=p1`） |

它们都不产生产品能力，但缺了会挡住所有能力。**两张轴必须分开记，否则第二张永远不存在。**

**2026-11-23 回看**：四条里三条已经收口（见上表右列），只剩「依赖方向测试」还悬着——
它不影响产品能力，但会悄悄破坏 §1 的层次约定，`mihoyo` 反向依赖 `agent` 就是这么来的。

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
| 身份解析（会话→用户→凭据） | ✅ | `channel/SessionRegistry`（适配器注册，工具按 `@ToolMemoryId` 反查） | — 刻意不做 `RequestContext`：只有一个消费者时它是多余的中间层 | C3 |
| **Prompt 组装** | 🟡 | `agent/prompt/SystemPrompt` + `PromptSection`（四段按枚举顺序拼） | 骨架已落：`VERSION=p1`、空段整段不输出、总长 4000 字上限；`PERSONA` 与 `CHANNEL` 仍是空串 | **C1** |
| 模型路由与降级 | ⬜ | 单 `ChatModel` bean | 无 provider 抽象、无 fallback | §4 |
| 工具注册与选择 | ✅ | `agent/tool/Capability`（注册表）+ AUTOMATIC 接线，2 个工具 | 风险级只有 `READ / WRITE`；工具超 6 个时再切 `EXPLICIT` | **C2** |
| 循环治理（步数/超时/预算） | ⬜ | 框架的 tool loop | 无最大步数、无单轮超时，跑飞就是死循环烧钱 | C4 |
| 输出整形 | 🟡 | `channel/TextChunker` + 工具的 `format()` | 切片与「工具只给事实」已就位；缺「通道决定文体」的提示词约束（`<channel>` 段仍空） | C1 |

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
| 权限·能做什么 | 🟡 | `Capability.Risk`（`READ` / `WRITE` 挂在注册表上） | 已分级，但写工具仍无二次确认；`IRREVERSIBLE` 等真有不可逆动作再加 | **C2** |
| 事实约束（不编造） | 🟡 | `SystemPrompt.CAPABILITIES` 的「不能做什么」 | 能力外的硬规则已写进 `<capabilities>`；缺「知识未命中就说不知道」的可执行规则与出处 | C1 + P12 |
| 错误分类与降级 | 🟡 | `mihoyo/error/MihoyoError`、`describe(e)` | 缺 `ErrorKind` → 人话的映射表；重试策略未定 | P5 |
| 隐私与脱敏 | ✅ | `common/log/Mask`、`Credential.toString()` | 缺一条「日志里不出现 cookie」的测试 | P5 |

### 1.4 工程层（怎么知道它还好）

| 模块 | 状态 | 落点 | 缺什么 | 归属 |
|---|---|---|---|---|
| 可观测 + 成本 | 🟡 | `agent/observability/TurnTrace`（每轮一条 `log.info`） | 已有 traceId / promptVersion / token / 延迟 / outcome；缺预算与 `TurnTraceTest` | **C4** |
| 评测 | ✅ | `scripts/eval.ps1` + `scripts/eval/cases.jsonl`（20 条：有效 12、恒真 8） | 一条命令出分数并写 `target/eval-last.json`；用例偏少，P13 再加 20 条 | **C4** |
| 配置与实验 | 🟡 | `.env` + `application.yml` + `SystemPrompt.VERSION` | prompt 已有版本号；缺 feature flag 与 A/B | §4 |
| 持久化 | ✅ | 三套 file store | 无 schema 版本，格式一变旧文件读不了 | P8 |
| 并发一致性 | ✅ | `channel/SessionSerializer`（64 条带）+ `QqBotDispatcher` 包住 `chat.ask` | —；`/agent` 调试口故意不串行（单人顺序使用，不是用户入口） | C3 |

---

## 2. 跨阶段契约 C1–C5

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

**落点**（2026-11-23 已落）：`agent/prompt/SystemPrompt` + `PromptSection` 枚举。
`compose()` 按枚举声明顺序拼四段，空段整段跳过，总长超过 `MAX_CHARS = 4000` 直接抛（不截断）；
`MihoyoAssistant` 的 `@SystemMessage` 里只剩 `{{system}}` 一个占位符，由 `SystemPrompt` 注入。

**分工**：这里只管**容器**（段序、标签、上限、版本号）；段里的**文字**归各自的步骤——
`<persona>` 与 `<channel>` 是 P13 / C1-b 的事，`<capabilities>` 由 C2 的注册表驱动。

**验证**：`SystemPromptTest`（5 条）——段序由枚举定、空段整段不出现、标签闭合、超长抛异常、
`@SystemMessage` 里只能是 `{{system}}`。

**现状**（2026-11-23）：容器已成立，不再被违反。**内容还不完整**：`PERSONA` 与 `CHANNEL` 都是空串，
所以模型现在只看到能力声明，没被告知「QQ 是纯文本、单条 ≤600 字、别用 markdown」。

---

### C2 · 工具治理契约

**约束**（四条，都是一句话能判定的）：

1. **能力清单的唯一真相是一张注册表**（枚举），启动期断言「注册表 ↔ 容器里的 `@Tool` 方法名」双向一一对应，不等就失败。
2. **每个工具必须声明风险级**：`READ` / `WRITE` / `IRREVERSIBLE`。`WRITE` 以上必须满足「幂等可判断 + 失败可分类 + 人可复核」，三缺一不许上线；`IRREVERSIBLE` 现阶段一律不做。
3. **非产品工具一律 `@Profile`**（`DecoyTool` 已经这么做）。AUTOMATIC 接线意味着任何 `@Tool` bean 都会出现在每个 AiService 面前。
4. **工具超过 6 个**时切 `EXPLICIT` wiring，或按场景拆两个 AiService。

**落点**（2026-11-23 已落）：`agent/tool/Capability.java`（枚举，带 `Risk` 与一句 `phrase()`）+ `CapabilityConsistencyTest`。
风险级直接挂在枚举上，没有另加 `@ToolRisk` 注解——注册表已经是唯一真相，再加注解等于开第二份。

**验证**：`CapabilityConsistencyTest`（3 条）——注册表名字集合 = 容器里 `@Tool` 名字集合；
每个 `capability.phrase()` 必须出现在 `SystemPrompt.compose()` 的 `<capabilities>` 段里（防止能力声明漂移）。

**现状**（2026-11-23）：已成立，不再被违反。能力声明漂移过三次（11-07 深渊 / 11-12 签到 / 11-13 删角色），
三次都是人肉发现的——这条测试就是那次教训的产物。尚未做：写工具的二次确认、`IRREVERSIBLE` 级
（现阶段没有不可逆动作，先不加空级别）。

---

### C3 · 身份与会话契约

**约束**：

1. 身份只在**适配器**构造一次，注册进 `SessionRegistry`，其余各层按 `@ToolMemoryId` 反查
   （2026-11-23 核对：这一条**已成立**）。**刻意不做 `RequestContext`**：只有一个消费者时它只是多开一条
   身份通路，反而可能与注册表分叉；等第二个入站适配器出现再评估。
2. `uid` / `userId` **永不作为工具参数**（`mihoyo-auth.md` §7）。工具只通过 `@ToolMemoryId` 拿会话键，再经 `SessionRegistry` 换 userId。
3. **同一 sessionId 的请求串行执行。** 一轮 5~20 秒，用户连发两条必然重叠。

**为什么第 3 条是契约不是优化**：`TurnWindowChatMemory.add()` 是「读 → 加一条 → 写回」（`getMessages` 后 `updateMessages`），没有任何锁；`QqBotDispatcher` 是每条消息一个虚拟线程。两条并发消息会各自读到同一份 20 条、各自 +1 写回，**后写的覆盖先写的**——不报错，只是模型无故失忆一格。

**落点**：只差 `channel/SessionSerializer`（条带锁，约 70 行含注释）+ `QqBotDispatcher` 用它包住 `chat.ask`。
**不要**在这里顺手引入 `RequestContext`——那是第二条身份通路，不是这个问题的一部分。

**验证**：`SessionSerializerTest` —— 同 key 两线程并发时第二个必须等第一个；不同 key 不互斥。加上 `TurnWindowChatMemory` 的并发读写测试。

**现在违反**：无（2026-11-23 收口）——`SessionSerializer` 包住了整段 `chat.ask`，同会话并发不再互相覆盖。
唯一例外是 `/agent` 调试口：同一 session 并发仍不串行，**故意**不包（单人顺序使用；它变成真实入口时再收口）。

---

### C4 · 可观测与评测契约

**约束**：

1. 每轮落一条 `TurnTrace`：`traceId` / `sessionId` / `promptVersion` / model / toolsCalled / tokensIn·Out / latencyMs / outcome / errorKind。
2. **prompt 带版本号**，与 C1 的四段一起改。没有它，改完无法归因。
3. **改 prompt / 工具 / 模型之前，先跑基线。** 基线是一条命令就能跑、能出分数的东西。

**落点**（2026-11-23 已落）：`agent/observability/TurnTrace`（`traceId` / `sessionId` / `promptVersion` /
`inputTokens` / `outputTokens` / `latencyMs` / `outcome`，每轮一条 `log.info`，由 `AgentChatService` 发）
+ `scripts/eval/cases.jsonl`（20 条）+ `scripts/eval.ps1`（一条命令出分数，明细写 `target/eval-last.json`）。

**验证**：`pwsh -File scripts/eval.ps1` 输出 `N/M 通过 · 分数`；日志里每一轮都能按 `traceId` 串起来。
**分数必须记账**（写进 `ROADMAP.md` 维护记录），否则基线只证明了「脚本能跑」，没证明「这次改完是变好还是变坏」。

**现状**（2026-11-23）：已成立，不再被违反；首跑 **18/20（有效 10/12）**，明细见 `ROADMAP.md` 维护记录。
缺口：`TurnTrace` 没有单测；20 条用例里 8 条恒真（`tools=[]`，只有 2 个工具时模型没有别的工具可误调），
真正有判别力的是 12 条；token 预算还没做（等 P9 跑出真实量再说）。

---

### C5 · 工具输出契约

**约束**：工具输出会**原样进模型上下文**，模型又倾向于照抄，所以工具只给事实、不给措辞。四条「不许」：

| 不许 | 为什么 | 该怎么写 |
|---|---|---|
| 结论句（「签到成功，今天也别忘了」） | 语气是 `<persona>` 的职责；写在 Java 里等于绕过 prompt 把口吻写死 | 只报事实：`原神 已签 12 天` |
| 内部枚举（`SIGNED` / `ALREADY` / `DONE`） | 模型会照抄给用户；枚举是我们内部的词，不是人话 | 在 `format()` 里翻成「新签成功」「今天已签」 |
| `【】` / `**` / 表格 / `#` | QQ 不渲染 markdown，装饰符只会被复制进回复 | 纯文本 + `- ` 列表 |
| 未标注的推算值 | 推算值会被当成接口原值，用户拿去对账就对不上 | 推算出来的值后面标「（推算）」 |

**落点**：`agent/tool/CheckinTool.format()` 与 `agent/tool/CheckinOverviewTool.format()`——格式化只在这两个方法里；
工具方法本身只负责取数、判身份、记 `ToolCallRecorder`（记录提到身份判断**之前**：被拒绝的调用也要记，评测基线靠它）。

**验证**：`ToolOutputContractTest`（4 条）——两个工具的完整输出里不出现内部枚举、不出现装饰符、
推算值带「（推算）」、每个角色恰好一行（`- ` 开头）。只查形状不查文案：文案会变，形状不该变。

**与 C1 的分工**：C1 把措辞权交给 prompt，C5 把措辞权从 Java 里拿走；两边都不写死，语气才只有一个来源。
**代价**：删掉 `【结论】/【数据】` 之后，输出的可读形状完全交给模型——所以它必须和 C4 的基线一起用，
否则「改好了」和「改坏了」都看不出来。

---

## 3. 每个部件「怎么证明它是好的」

**规则：写不出验证方式的部件 = 还没设计完。** 实现前先把这一行补上。

| 部件 | 现在就能写的验证 |
|---|---|
| Prompt 组装 | `SystemPromptTest`（5 条，已落地）：段序由枚举定 / 标签闭合 / 空段不出现 / 超长抛异常 / `@SystemMessage` 只剩 `{{system}}` |
| 工具治理 | `CapabilityConsistencyTest`（3 条，已落地）：注册表 ↔ `@Tool` 双向相等 + `phrase()` 出现在 `<capabilities>` 里 |
| 工具输出 | `ToolOutputContractTest`（4 条，已落地）：无内部枚举 / 无装饰符 / 推算值带「（推算）」/ 每角色一行 |
| 身份与会话 | `SessionSerializerTest`（4 条，已落地）：同 session 串行 / 不同 session 并行 / 异常释放锁 / null 容忍 |
| 短期记忆 | 已有 `TurnWindowChatMemoryTest` / `SessionMemoryStoreTest` / `SessionMemoryStorePersistenceTest` |
| 评测基线 | `scripts/eval.ps1`：20 条用例（有效 12、恒真 8）一条命令出分数，明细写 `target/eval-last.json`，分数记账到 `ROADMAP` 维护记录 |
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
| 本文 | 模块矩阵、C1–C5、验证方式、触发条件 | 排期、进度、验收记录 |

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

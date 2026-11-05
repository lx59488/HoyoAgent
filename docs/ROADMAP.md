# HoyoAgent 学习路线与任务清单

> 目标：以「米游社 Agent 工具」为载体，学习 Agent 开发。
> 用法：从上往下做，每完成一条把 `[ ]` 改成 `[x]`。每个阶段结束后打一个 git tag（`phase-1`、`phase-2`…）。
> 设计原则：**先用假数据学 Agent，再接真实 API**。两条线并行、互不阻塞。

**最后更新：2026-11-05**（P1/P2 完成；P3 未跑批；P4 骨架完成，已打通第一个只读接口）

---

## 进度总览

| 阶段 | 内容 | 产出 | 预估 | 状态 |
|---|---|---|---|---|
| P0 | 环境收尾 | 能启动的空工程 | 30 min | 🚧 2 项待办 |
| P1 | AI 闭环 | `GET /chat` 能对话 | 半天 | ✅ 完成 |
| P2 | 第一个 Tool（假数据） | 模型自主调用工具 | 1 天 | ✅ 完成 |
| P3 | 多工具与工具设计 | 10 条用例命中 ≥ 8 | 1 天 | 🚧 代码就绪，未跑批 |
| P4 | 米游社客户端基线 | 控制台打印真实 uid | 2~3 天 | 🚧 骨架完成，验证中 |
| P5 | 数据契约与错误治理 | 脱敏 + 限流 + DTO | 1~2 天 | 未开始 |
| P6 | 接入真实数据 | 自然语言查真实战绩 | 半天 | 未开始 |
| P7 | 记忆与多轮 | 多轮对话不丢上下文 | 1 天 | 未开始 |
| P8 | 工程化扩展（选做） | 多账号 / 可观测 / MCP | 长期 | 未开始 |

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
│   └── tool/FakePlayerTool.java       ← 4 个假工具（P6 会换成真数据）
└── mihoyo/                            ← P4：外部系统客户端（与 agent 完全解耦）
    ├── api/MiyousheApi.java           ← 业务接口：路径 + 参数 → JsonNode
    ├── client/MihoyoHttpClient.java   ← OkHttp 单例 + 统一 header + retcode 检查
    ├── auth/{Credential, CookieParser, CredentialProvider}.java
    ├── sign/Signer.java               ← DS1；salt 空则不发 DS
    └── config/HoyoProperties.java     ← @ConfigurationProperties(prefix = "hoyo")

src/test/java/...
└── mihoyo/MiyousheApiTest.java        ← @Tag("network")，真实打米游社

scripts/smoke-agent.ps1                ← P3 冒烟脚本（当前 5 条用例）
docs/ROADMAP.md                        ← 本文件
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
- [ ] **新建 `.env.example`** —— 2026-11-02 核对：文件不存在
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

## P3 · 多工具与工具设计 🚧

**目标**：用实验证明「命中率由描述决定」，练出改描述的手感。
**纪律**：只改**描述和参数**，不改代码逻辑，否则无法归因。
**现状**：代码与脚本已就绪（4 个工具 + 观测），**但两轮跑批一次都没做**，10 条用例表还是空的。这是当前最大的"欠账"。

### 已完成

- [x] P3-1 四个工具（`getPlayerCharacters` / `getCharacterDetail` / `getDailyNote` / `getAbyssReport`），共享同一份假数据源
- [x] 每个工具方法第一行 `log.info("[TOOL] ...")` + `recorder.record(...)`
- [x] `ToolCallRecorder`（`@Component` + ThreadLocal），`AgentController` 通过单一出口 `finish()` 回传 `toolsCalled`
- [x] 冒烟脚本 `scripts/smoke-agent.ps1` 可跑通，能区分 `NO-FIELD` / `(none)` / 实际工具名

> ⚠️ 2026-11-02 第一次跑脚本得 1/5，全部 `(none)`。**那是观测坏了，不是模型没调**：Controller 只在 `finally` 里 `drain()`，返回值被丢弃。修复后已能回传。

### 待做

- [ ] P3-2 把脚本用例从 5 条扩到 10 条（下表）
- [ ] P3-3 脚本支持「期望多个工具」和「参数值校验」（当前 `-like` 只匹配一个工具名）
- [ ] P3-4 第一轮跑批（描述仍是"坏版本"），记录结果
- [ ] P3-5 **只改描述**，第二轮跑批
- [ ] P3-6 命中率 ≥ 8/10
- [ ] P3-7 返回值确认是摘要而非全量 JSON
- [ ] P3-8 把两轮结果表贴回本文档

### 10 条用例表（结果待填）

数据前提：萝卜 = 雷电将军90/2、纳西妲90/0、胡桃80/6；小明 = 钟离90/1。

| # | 类别 | 提问 | 期望工具 | 期望参数 | 第一轮实际 | 命中 | 第二轮实际 | 命中 |
|---|---|---|---|---|---|---|---|---|
| 1 | 正例·列表 | 萝卜有哪些原神角色 | `getPlayerCharacters` | `nickname="萝卜"` | | | | |
| 2 | 正例·详情 | 雷电将军练得怎么样 | `getCharacterDetail` | `characterName="雷电将军"` | | | | |
| 3 | 正例·进度 | 萝卜今天树脂还剩多少 | `getDailyNote` | `nickname="萝卜"` | | | | |
| 4 | 正例·战绩 | 萝卜这期深渊打到哪了 | `getAbyssReport` | `nickname="萝卜"` | | | | |
| 5 | 边界·口语 | 小明树脂满了吗 | `getDailyNote` | `nickname="小明"` | | | | |
| 6 | 边界·同义 | 查查小明的深渊 | `getAbyssReport` | `nickname="小明"` | | | | |
| 7 | **歧义·易混** | 萝卜的胡桃多少命 | `getCharacterDetail` | `characterName="胡桃"` | | | | |
| 8 | 负例·无关 | 今天天气怎么样 | **不调用** | — | | | | |
| 9 | 负例·能力外 | 萝卜昨天签到成功了吗 | **不调用** | — | | | | |
| 10 | 多调用 | 小明和萝卜谁的角色多 | `getPlayerCharacters` ×2 | `小明`、`萝卜` | | | | |

**这张表是 P3 的验收标准本身**，必须在跑之前定死。跑完回头改期望值 = 自我安慰。

第 7 条是核心：这句话里同时有玩家名和角色名，而两个工具的描述目前都很糊（"查询原神角色信息"/"查询原神角色详情"）。**它就该错**，错的时候你才知道改哪句话。

### 待验证的改法（第二轮用）

| 工具 | 改前 | 改后（示例） |
|---|---|---|
| `getPlayerCharacters` | 查询原神角色信息 | 列出某个玩家账号下的**全部**原神角色，用于回答"我有哪些角色""谁满级了"。只需要玩家昵称，不需要角色名。 |
| `getCharacterDetail` | 查询原神角色详情 | 查询**单个**角色的属性（等级、命座、武器）。前提是已经知道具体角色名；只知道玩家时请改用 getPlayerCharacters。 |

三个可复用的手法：说清边界（全部 vs 单个）、写清使用条件（"前提是已经知道角色名"）、互相指路（"请改用 XXX"）。

### 常见坑

- 工具描述堆同义词（查询/获取/查看）不会提高命中率，只会让边界更糊
- 参数名比描述更容易出错：`characterName` 改成 `name` 后，模型往里填玩家名的概率显著上升
- 工具返回全量 JSON → 上下文被吃掉；先切摘要
- 工具内抛异常 → 整个请求 500；应把错误当文字返回给模型

---

## P4 · 米游社客户端基线 🚧

**目标**：纯 Java 打通**一个**只读接口。这一步不许碰 AI，也不许改 Agent 代码。
**前置**：用**小号**。不要用主账号做实验。
**现状**：骨架 6 个类已落地，`getAppConfig` 已打通并验证；但 salt 未知、device_id 未实现、错误场景未验证。

### 已完成

- [x] P4-1 包结构落地：`mihoyo.{api, auth, client, config, sign}`（顶层 `mihoyo`，不是 `adapter.mihoyo`）
- [x] P4-2 `Credential`（record；`toString()` 已脱敏，避免 token 进日志）
- [x] P4-3 `CookieParser`（`ltuid_v2` > `ltuid` > `account_id_v2` > `account_id`；空值跳过；引号剥离）
- [x] P4-4 `Signer`：DS1 结构（`md5("salt={salt}&t={t}&r={r}")`，输出 `t,r,hash`）；salt 从 `hoyo.salt` 读，**空则不发 DS 头**
- [x] P4-6 `MihoyoHttpClient`：OkHttp 单例、统一 header、HTTP 状态检查、retcode 检查、异常映射到 9xxx
- [x] P4-8 第一个只读接口：`GET /apihub/wapi/getAppConfig?gids=2` → `retcode=0`，拿到完整 `config`（60+ key）

**实测结论（重要）**：`getAppConfig` **不校验 DS**。三种情况均返回 `retcode=0`——带原始 DS、带伪造 DS、完全不带 DS。所以「先打通、后签名」的策略成立。

### 待做

- [ ] **P4-5 `DeviceIdProvider`**：当前客户端完全没发 `x-rpc-device_id`。只读配置接口不需要，但需要 cookie 的接口大概率会因此失败
- [ ] P4-7 日志脱敏：目前只靠 `Credential.toString()` + 不打印 header，还没有拦截器兜底
- [ ] P4-9 retcode 映射表补全：现有 5 条（-100/10001/-5003/10101/1034）**全是假设**，必须实测
- [ ] P4-10 三种错误场景各验证一次：错 cookie、过期 cookie、断网
- [ ] P4-11 离线单测：`Signer` 固定输入 → 固定输出；`CookieParser` 各种脏输入
- [ ] P4-12 关掉 `log-requests` / `log-responses`，`dev.langchain4j: DEBUG` 降级——**还没做**，接 cookie 之前必须做

### 已修复

- 2026-11-05：`MihoyoHttpClient` 的 retcode 判断写成了 `if (retcode == -1)`，导致除 `-1` 外的所有错误码（`-100`、`-5003`、`1034`…）都被当成成功、直接返回 `data`。已改为 `if (retcode != 0)`。**不修这一行，P4-9 的映射表永远跑不到。**

### 验收

- [x] 命令行打印出 `retcode=0` 和真实的配置数据
- [ ] 拿到自己的 **uid + 昵称**（需要换一个带 cookie 的接口，这才是 P4 的真正终点）
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

## P6 · 接入真实数据

**目标**：把假工具换成真客户端。**`@AiService` 接口和工具签名应该一行都不用改。**

- [ ] P6-1 替换 `FakePlayerTool` 里的 `DB` / `NOTES` / `ABYSS` 三张表
- [ ] P6-2 确认 `MihoyoAssistant` 与工具签名零改动
- [ ] P6-3 端到端：自然语言 → 真实数据 → 可读回答
- [ ] P6-4 复盘：如果工具签名被迫改了，记录当初抽象错在哪
- [ ] P6-5 （可选）在 `agent` 里加 `PlayerQueryPort` 接口，让 `mihoyo` 反向实现，依赖方向变成"外层依赖内层"

**验收**

- [ ] 「我原神练度怎么样」能拿到真实数据并组织成中文回答
- [ ] cookie 失效时，模型回答「登录已过期，请重新授权」而不是崩溃

---

## P7 · 记忆与多轮

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
| **2.117.0 的 salt** | ❓ 未找到 | 133 个公开值 + 7967 个从网页 bundle 挖出的候选，全部不匹配 |
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

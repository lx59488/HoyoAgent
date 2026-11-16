# 新工具建议（对标 FufuLauncher）

> 状态：**建议稿，未落地**。本文不动 `ROADMAP.md` 的进度口径，只回答一个问题：
> 「除了签到，还该给 Agent 加什么工具？」
> 参考对象：[FufuLauncher](https://github.com/FufuLauncher/FufuLauncher)（MIT，C#，桌面启动器）。
> 事实核对基准：`master@bdfddaeb`（2026-11-16）。文中接口/头部/版本号均来自其源码，不是猜的。

---

## 0. 先说结论

**只推荐先做三个，且顺序不能换：**

| 顺序 | 工具 | 一句话 | 工作量 | 风险 |
|---|---|---|---|---|
| 1 | `getCheckinOverview()` | 签到总览：今天签没签、累计天数、本月约漏几天、最近 7 天 | 0.5 天 | 低（零新协议，只查不签） |
| 2 | `getAccountStatus()` | 账号体检：绑了谁、哪张票过期、上次为什么失败 | 0.5 天 | 低（几乎不发请求） |
| 3 | `getDailyNote()` | 原神实时便笺：树脂 / 委托 / 洞天宝钱 / 派遣 / 质变仪 | 1 天 + 0.5 天验证 | 中（战绩头 + 5003） |

**其余一律往后排；另有一批明确不做的，理由见 §5。**

**最重要的一句判断**：FufuLauncher 是个桌面启动器，它的能力里只有
「**米游社数据 + 每日仪式**」这一层能搬进聊天 Agent；下载、更新、注入、插件、连点器、
FPS 悬浮窗、截图相册都是本地桌面能力，搬过来只会得到一个四不像。

**第二重要的一句**：不要按「FufuLauncher 有 16 个功能，我们也做 16 个工具」的思路走。
它是给人点的，工具是给模型选的。**人点错菜单只是麻烦，模型选错工具会编数据。**
工具数量增加带来的主要成本不是代码，是**描述重叠**——P3 已经用 10 条用例证明过这件事。

---

## 1. 能力映射：哪些能搬，哪些不能

| FufuLauncher 功能 | 依赖 | 能搬吗 | 结论 |
|---|---|---|---|
| 多账号快速切换 | 账号体系 | ⚠️ 部分 | 项目已有 `AccountStore`，但多用户是 P8；现在做工具参数化身份 = 违反 `docs/mihoyo-auth.md` §7 的安全规则 |
| 游戏签到（luna） | cookie_token | ✅ 已有 | `CheckinTool` |
| 奖励日历 | luna/home | ✅ 可搬 | 已接 `home`；v1 先不用它（每游戏多一次请求），留给 v1.1 |
| **社区签到（米游币）** | stoken + BBS 头 | ✅ 可搬 | 新 Profile（DS2），写操作，排第二梯队 |
| 云游戏签到 | 云游戏 token | ✅ 可搬 | 收益低、协议独立，暂不做 |
| **实时便笺** | ltoken_v2 + 完整战绩头 | ✅ 可搬 | 最高价值，见 §3.3 |
| 深境螺旋 / 剧诗 / 危战 | 同上 | ✅ 可搬 | 同一套头，做完便签后顺手；受 5003 影响 |
| 抽卡分析 | stoken → authkey + 分页 | ✅ 但重 | 2~3 天，且要落库；放 P10 |
| 兑换码提醒 | 第三方 JSON | ⚠️ 只读 | 提醒可做；**兑换不做**（写 + 验证码 + 第三方） |
| 公告 / 版本 / 卡池 | 公开接口，无凭据 | ✅ 易 | 低成本高感知，但内容长，必须摘要 |
| 数据中心（使用率/排名/配队/预测） | 第三方 `lelaer` / `yshelper` | ❌ | 不是官方数据，不可控，**不做** |
| 成就 | 本地/第三方数据 | ❌ | 没有可直接复用的官方接口，**不做** |
| 背包仓库 | 游戏内数据 | ❌ | 官方不提供，**不做** |
| 启动 / 下载 / 更新 / 注入 / 插件 / 连点器 / FPS / 截图 | 本地 Windows | ❌ | 与聊天 Agent 无关，**不做** |
| 米游社社区浏览 | BBS API | ⚠️ 只读 | 只读浏览可做；发帖/点赞/评论 **不做** |

---

## 2. 候选清单（按「单位收益 / 风险」排）

| 档 | 工具 | 输入 | 数据源 | 为什么值得 | 主要风险 |
|---|---|---|---|---|---|
| P0 | `getCheckinOverview` | 无 | luna/info + 本地记录 | 零新协议；只读，不发写请求 | 无 |
| P0 | `getAccountStatus` | 无 | 本地凭据 + roles | 让失败可解释，直接服务 P9-9/P9-11 | 无 |
| P0 | `getDailyNote` | 无（先只做原神，游戏选择留给以后） | genshin dailyNote | 日频、只读、用户真正每天会问 | 5003 / 1034 |
| P1 | `checkinCommunity` | 无 | BBS signIn | 「米游币」是独立日常 | 风控、验证码、新 Profile |
| P1 | `getAbyssReport` | 无 | genshin spiralAbyss | 与便签共用一套头，边际成本低 | 5003、隐私设置 |
| P1 | `getAnnouncements` | 无 | hk4e ann-api（公开） | 防模型编活动时间 | 内容长，要摘要 + 缓存 |
| P2 | `getGachaSummary` | 无 | genAuthKey + gacha log | 玩家最想要的数据之一 | 工程量大、要持久化 |
| P2 | `listRedeemCodes` | 无 | 第三方 JSON | 提醒型，只读 | 数据源不可控；兑换绝不做 |
| ✗ | 社区写操作 / 兑换 / 成就 / 背包 / 数据中心 | — | — | — | 见 §5 |

---

## 3. 三个推荐工具的具体设计

### 3.1 `getCheckinOverview()` —— 先做这个

**为什么先做**：它是唯一一个**不需要任何新协议**的工具。`luna/info` 和
`CheckinRecordStore.recent(7)` 两份数据在仓库里都已经存在，只差拼起来。

**返回（固定顺序，一眼能读）**：

```
【结论】3 个角色：已签 2，未签 1
【今日】2026-11-16
  原神（100848866）：今天已签 · 累计 12 天 · 本月约漏 4 天
  崩坏：星穹铁道（103871907）：今天还没签 · 累计 11 天 · 本月约漏 5 天
【最近】
  2026-11-16 DONE（SCHEDULED）：3 个角色：新签 0，已签 3
  2026-11-15 DONE（SCHEDULED）：3 个角色：新签 3，已签 0
```

**签名**：`String getCheckinOverview()`，零参数，身份来自 cookie。

**关键设计取舍**：

- **只读，绝不签到。** 签到是 `checkinToday` 的事。两个工具职责必须切开，否则模型会用
  overview 顺手签一次，或者用 checkin 回答日历问题。
- **v1 不查 `luna/home`。** 每个游戏只打一次 `info`。`home` 只多给奖励名和日历长度，
  却让请求数从 3 变 6；奖励名已经由 `checkinToday` 报过。
- **补签卡 v1 不做。** `luna/resign_info` 只是 FufuLauncher 端点表里的一个地址，
  没找到可对照的实现；能不能用、返回什么都要先验。加它就把「零新协议」这个卖点弄丢了。
- **漏签天数是推算，不是官方数字。** 用「今天几号 − 累计天数」得到，前提是服务端
  `total_sign_day` 是本月累计。文案里必须写「约」，并且**不要列出具体哪几天漏了**——
  服务端不给这个信息，列出来就是编。
- **本地记录只用于「最近 7 天」**，报 `date` + `outcome` + `trigger` + `message`，
  不复述 `kind`（日志里已经有）。
- **一个游戏失败不影响其他游戏**：逐游戏 try/catch，失败那段写「登录状态失效」，
  其余照常返回。工具层永远返回文字，不抛异常（P3 的坑）。
- **本地记录按 `userId` 过滤**：文件名只有日期，多账号共用目录时不能把别人的记录
  当成自己的（和 `CheckinScheduler.todayRecord` 同一条规矩）。

**和 `checkinToday` 的描述必须互斥**（照抄可用）。边界是**陈述句 vs 疑问句**：
「帮我签」是执行，「签到了吗」是查看。

```java
@Tool("""
    查看当前账号的签到总览：今天原神/星穹铁道/绝区零各签没签、累计签到天数、
    本月大约漏签几天、最近 7 天的签到结果。纯查询，不会执行签到。
    用户问「这个月漏了几天」「今天签到什么情况」「最近签到正常吗」时调用。
    用户要求「帮我签到」「帮我领原石」时不要用本工具，改用 checkinToday。
    """)
```

```java
@Tool("""
    执行当前账号的每日签到（原神/星穹铁道/绝区零）。
    用户说「签到」「帮我领原石」「签一下」时调用；幂等，今天已签会如实返回「已经签过」。
    只是问「签到了吗 / 漏了几天」时不要用本工具，改用 getCheckinOverview。
    """)
```

> 这两段是 P3 方法论的直接复用：**说清边界、写清使用条件、互相指路**。
> 不写「互相指路」，5 个工具时必然出现「详情+列表」式双调。

---

### 3.2 `getAccountStatus()` —— 让失败变得可解释

**现状的痛点很具体**：11-16 三个角色全 `AUTH`，但 `hasStoken=true`、`/api/auth/roles` 正常。
模型只能说「签到失败」，用户无法判断该点刷新、重扫码，还是等一会儿。

**返回**（全部是布尔/枚举，不含任何 token 值）：

```
【账号】user=local · 来源=store · uid=75708228 · mid=***_mhy
【凭据】stoken=有 · ltoken=有 · ltoken_v2=有 · cookie_token=无
【角色】7 个（原神/星铁/绝区零/崩坏3×4）
【上次】11-16 00:10 SCHEDULED FAILED · kind=AUTH · 「cookie_token 缺失，签到会被拒」
```

**签名**：`String getAccountStatus()`，零参数。

**三条纪律**：

1. **只读，不刷新。** 刷新是服务层在失败时做的事（P9-9），不是模型能点的按钮。
   工具一旦能刷新，模型会在失败时循环调用。
2. **不返回凭据值，连尾号都不返回。** 只返回「有/无」。
3. **`kind` 直接复用 `ErrorKind`**，不新造词——这样模型说的话和状态页、日志是同一套，
   不需要正则匹配中文。

**它真正的价值**：把「凭据体检」变成模型可推理的输入。有了它，
「为什么签到老失败」才第一次有正确答案，而不是模型猜「可能是网络问题」。

---

### 3.3 `getDailyNote()` —— 价值最高，但要先验证

**为什么是它**：日频、只读、用户每天真的会问；而且它是 **P7-3 缺的那个第二工具**——
「我原神树脂多少」→「那星铁呢」必须靠记忆接住「原神」这个主语。

#### 先看事实（FufuLauncher 源码，已核对）

`Services/MiHoYo/DailyNote/DailyNoteService.cs` 的行为：

| 项 | 值 |
|---|---|
| 端点 | `GET https://api-takumi-record.mihoyo.com/game_record/app/genshin/api/dailyNote?role_id={uid}&server={region}` |
| 预热 | 先打一次 `/game_record/app/genshin/api/index?avatar_list_type=1&<同样的 query>`，**失败可忽略** |
| DS | DS1，salt **`xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs`** |
| `x-rpc-app_version` | **`2.112.0`**（不是项目 `RECORD` 里的 2.117.0） |
| 额外头 | `device_fp`、`sys_version`、`x-rpc-tool_version`（`v6.6.1-gr-cn`）、`page`（`v6.6.1-gr-cn_#/ys`） |
| **query 必须排序** | `SortedQuery = query 按 Ordinal 排序后用 & 拼`，否则 DS 对不上 |
| 失败兜底 | `5003` 或 `1034` → 改打 `/aapi/widget/v2?game_id=2`（另一套 DS1 salt `t0qEgfub6cvueAPgR5m9aQWWVciEer7v`） |
| 登录过期 | 刷新 cookie 后**只重试一次** |
| 1034 | 弹极验验证码（Geetest）；FufuLauncher 会尝试解，**我们不该解** |

**这解释了两件事**：

- 为什么 `RECORD` 现在这套头（2.117.0 + `DsMode.NONE` + 无 fp/page/排序）大概率拿不到便签。
  不是 cookie 不行，是**头部不完整**。ROADMAP 里「补齐 fp/sys/tool/page/DS+sorted query」
  说的就是这一格。
- 为什么之前 `character/list` 恒 `5003` 却不一定是账号问题——`5003` 在这个体系里是
  「需要验证 / 风控 / 权限」的混合码，FufuLauncher 的应对是**换 widget 通道**，不是放弃。

#### 所以第一步不是写工具，是一次 30 分钟的验证

```
1. 用现有 cookie 打一次 dailyNote，头部 = 现有 RECORD
   → 记录 retcode（大概率 5003 或 -100）
2. 换成 2.112.0 + DS1(CnX4) + device_fp + sys_version + tool_version + page + 排序 query
   → 记录 retcode
3. 若 5003/1034：改打 widget 通道，记录 retcode
4. 三种结果都写进 docs/ROADMAP.md 的附录 A，再决定要不要写工具
```

**不要**在这三步之前写 DTO、Service、Tool。这个项目已经吃过一次
「先写代码、后验协议」的亏（`character/list`）。

#### 如果验证通过，工具长这样

```java
@Tool("""
    查询当前账号原神的实时便笺：树脂、每日委托、洞天宝钱、探索派遣、参量质变仪。
    用户问「树脂还剩多少」「委托做了吗」「派遣好了没」时调用。
    只支持原神；星铁/绝区零的类似数据暂不支持，不要用本工具代答。
    """)
public String getDailyNote()
```

**返回示例**（摘要，不是 JSON）：

```
【原神】树脂 132/160（约 2 小时 18 分回满）· 委托 4/4 已领 · 洞天宝钱 1980/2400
       · 派遣 3/5 已完成 · 质变仪 可用
```

**必守的四条**：

1. **只做原神，先别做三个游戏。** FufuLauncher 的 `DailyNoteService` 只实现了原神；
   星铁 `/hkrpg/api/note`、绝区零 `/nap/api/note` 是另一套路径，需要单独验证。
   一次只上一个游戏，失败面小一半。
2. **1034 就返回「需要人机验证」**，不要试图解 Geetest。项目没有浏览器环境，
   硬解等于给自己埋一个长期维护点。`ErrorKind.NEED_VERIFY` 已经存在，直接用。
3. **60 秒缓存。** 便签是「看一眼」的数据，模型连问两次不该打两次米游社。
   缓存放 Service，不放 Tool。
4. **输出必须摘要。** 便签原始 JSON 有几十个字段，全塞进 20 条窗口的会话里，
   下一轮就开始丢上下文。

---

## 4. 第二梯队（做，但排在上面三个之后）

| 工具 | 为什么排后面 | 做之前要先有 |
|---|---|---|
| `checkinCommunity()` | 需要新 Profile（BBS + DS2）；写操作；有验证码路径 | P5 的限流 + `ErrorKind` 映射 |
| `getAbyssReport()` | 与便签共用战绩头，便签验通了它才便宜 | 便签的 spike 结果 |
| `getAnnouncements()` | 公开接口最容易，但内容长、时效强，必须摘要 + 缓存 | 摘要模板 |
| `getGachaSummary()` | 真正的高价值，但 = authkey 换取 + 分页 + 落库 + UIGF | 数据库/存储决策（P8） |

`checkinCommunity` 的协议事实（已核对）：`GET /apihub/api/querySignInStatus?gids=2` 查状态，
`POST /apihub/app/api/signIn` 执行；仍要处理 `1034` 验证码。**先查后签**，和游戏签到同构。

---

## 5. 明确不做（这部分比推荐更重要）

| 不做 | 理由 |
|---|---|
| **社区发帖 / 点赞 / 评论 / 回复** | 写操作 + 社区规则 + 封号风险；一旦模型说错话，责任在服务方。项目自己的 P3 就教过「能力边界」 |
| **兑换码兑换** | 写操作 + 验证码 + 第三方接口（FufuLauncher 走 Hutao API）。只做「提醒有哪些码」 |
| **成就 / 背包** | 没有可直接复用的官方接口，FufuLauncher 靠本地 + 第三方数据。搬过来只能造假数据 |
| **数据中心（使用率/排名/配队/预测）** | 全是第三方聚合数据，不可控、不可解释，且与「当前账号」无关 |
| **游戏启动 / 下载 / 更新 / 注入 / 插件 / 连点器** | 本地桌面能力，与聊天 Agent 的形态冲突；注入还涉及游戏 ToS |
| **把 uid / userId 做成工具参数** | 直接违反 `docs/mihoyo-auth.md` §7：身份只能来自 cookie / `@ToolMemoryId` |

**一条门槛**：凡是「会改变米游社侧状态」的工具，必须满足三件事才允许上线——
幂等可判断、失败可分类、人可复核。签到三件都满足，所以它是第一个写工具；
社区发帖三件都不满足，所以它永远不该是工具。

---

## 6. 工具变多之后的三个结构问题

### ① AUTOMATIC 接线 = 所有 `@Tool` 挂到所有 AiService

这是 P2 的结论，工具变多后从「方便」变成「风险」：
调试工具、诱饵工具、半成品工具会**同时**出现在模型面前。
纪律：**任何非产品工具一律 `@Profile`**（`DecoyTool` 已经这么做了）。
工具超过 ~6 个时，考虑切 `EXPLICIT` 或按场景拆两个 `@AiService`。

### ② 描述重叠是主要失败模式，不是模型笨

P3 的 7/10 → 8/10 只改了两段描述。工具到 5 个时，
「签到 / 签到总览 / 便签 / 账号状态」四个都和「今天」有关，重叠面比当年更大。
所以 §7 的用例表要在**写代码之前**定死。

### ③ 工具结果会进 20 条的记忆窗口

一次工具调用占 3~4 条消息。返回一大段 JSON，等于一轮吃掉半个窗口。
纪律：**工具返回摘要文本，不返回 JSON；原始数据留在日志里**。

### ④ 有些东西不该做成工具

| 内容 | 正确做法 | 为什么 |
|---|---|---|
| 当前账号是谁、绑了哪些角色 | SystemMessage / 启动时注入 | 每轮都调一次 = 白烧 token，且模型会用它凑答案 |
| 当前时间、今天日期 | 注入或模型自带 | 工具调用只为拿时间，纯浪费 |
| 角色/武器/攻略百科 | RAG（P8 之后） | 静态知识不是「查询当前账号」 |
| 凭据刷新 | 服务层自动做 | 做成工具 = 模型可以触发刷新循环 |

---

## 7. 建议的 10 条选择用例（写代码前定死）

判定口径沿用 P3：**实际工具集合与期望集合完全相等才算命中**，多调一个算 MISS。

| # | 类别 | 提问 | 期望工具 |
|---|---|---|---|
| 1 | 正例·写 | 帮我签个到 | `checkinToday` |
| 2 | 正例·读 | 今天三个游戏签到了吗 | `getCheckinOverview` |
| 3 | 正例·日历 | 这个月漏签了几天 | `getCheckinOverview` |
| 4 | 正例·复盘 | 我最近签到正常吗 | `getCheckinOverview` |
| 5 | 正例·便签 | 原神树脂还有多少 | `getDailyNote` |
| 6 | 边界·省略 | （上一问查原神）那委托做了吗 | `getDailyNote`（吃记忆，不重复问游戏） |
| 7 | 正例·账号 | 我绑了哪些游戏角色 | `getAccountStatus` |
| 8 | 正例·诊断 | 签到为什么老失败 | `getAccountStatus` |
| 9 | 负例·能力外 | 帮我发个帖子说今天出货了 | 不调用 |
| 10 | 多调用 | 原神和星铁今天签了没 | `getCheckinOverview`（一次覆盖全部游戏，**不是** `checkinToday` ×2） |

第 1 与第 2 是刻意设计的一对：**陈述句「帮我签」走 checkin，疑问句「签了吗」走 overview**。
如果模型分不清，就先改描述，不要先改代码——这是 P3 唯一可复用的手法。

第 6 条是 P7-3 的正式验收：它以「上一问的主语被省略」为成立条件，
而当前只有无参签到工具时，这个实验根本立不住。

---

## 8. 实施顺序与验收

| 步骤 | 内容 | 预估 | 验收 |
|---|---|---|---|
| 0 | **便签协议 spike**（不写产品代码） | 0.5 h | 三种头组合的 retcode 记进 ROADMAP 附录 A |
| 1 | `getCheckinOverview` | 0.5 天 | 真机结果与 `/api/checkin/status` 的今日结果一致；单测锁住漏签推算与文案 |
| 2 | `getAccountStatus` | 0.5 天 | 只返回有无；断言输出里不含 token 字符串 |
| 3 | `getDailyNote`（仅原神） | 1 天 | 真机 retcode=0；1034 → 「需人机验证」；60s 内二次调用不打网络 |
| 4 | 10 条用例跑批 | 0.5 天 | ≥ 8/10；不达标只改描述，记录两轮结果 |
| 5 | 决定第四个工具 | — | 看 §7 哪类问题仍然 MISS，不要凭感觉 |

**每步一个 commit**，第 3 步完成后打 `phase-tools` tag。

**唯一必须先做的工程债**：P5 的限流 + 头部治理。现在加 3 个读工具，
请求量会从「每天 3 次」变成「每次对话 1~3 次」。没有 1 req/s 的节流和 60s 缓存，
就是在主动往风控上撞。

---

## 9. 对 ROADMAP 的影响（等确认后再改）

- **P7-3 解锁条件**：`getDailyNote` + `getCheckinOverview` 同时在线，
  「原神树脂」→「那委托呢」才成立。
- **P6-7（dailyNote 二选一）**：本方案的答案就是「做 dailyNote」，可以关掉这一条。
- **P5 从「未开始」升级为「前置」**：不是因为理论重要，是因为这次要加三个读工具。
- **P3 的旧 10 条用例**仍然作废，但**方法论**（描述工程 + 集合完全相等）直接复用。

本文只是建议稿，ROADMAP 的进度口径没有被改动——**清单落后于代码比缺失更危险**，
所以要不要把上面这些写进 ROADMAP，等你拍板。

---

## 10. 参考（FufuLauncher 源码，MIT）

| 文件 | 看什么 |
|---|---|
| [`Services/MiHoYo/DailyNote/DailyNoteService.cs`](https://github.com/FufuLauncher/FufuLauncher/blob/master/FufuLauncher/Services/MiHoYo/DailyNote/DailyNoteService.cs) | 便签端点、DS 盐、app_version、page/tool_version、sorted query、5003/1034 的 widget 兜底、刷新后只重试一次 |
| [`Constants/MiHoYo/HeaderSalts.cs`](https://github.com/FufuLauncher/FufuLauncher/blob/master/FufuLauncher/Constants/MiHoYo/HeaderSalts.cs) | `CnX4`、`CnX6`、`WebMihoyo` 等盐，与版本成组 |
| [`Constants/MiHoYo/HeaderVersions.cs`](https://github.com/FufuLauncher/FufuLauncher/blob/master/FufuLauncher/Constants/MiHoYo/HeaderVersions.cs) | `MobileCnLogin = 2.112.0`、`ToolVersionCn = v6.6.1-gr-cn` |
| [`Constants/ApiEndpoints.cs`](https://github.com/FufuLauncher/FufuLauncher/blob/master/FufuLauncher/Constants/ApiEndpoints.cs) | 端点总表；含 `luna/resign`（补签）、社区签到、公告、抽卡、genAuthKey |
| [`Services/Checkin/CommunityCheckinService.cs`](https://github.com/FufuLauncher/FufuLauncher/blob/master/FufuLauncher/Services/Checkin/CommunityCheckinService.cs) | 社区签到的先查后签与验证码处理 |
| [`Services/Checkin/MiyousheRequestPacer.cs`](https://github.com/FufuLauncher/FufuLauncher/blob/master/FufuLauncher/Services/Checkin/MiyousheRequestPacer.cs) | 请求节流的实际参数，可对照 P5 的限流设计 |

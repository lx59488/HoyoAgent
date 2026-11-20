# QQ 入站（P10 / P11）· 设计与现状

> 目标：入口从「浏览器 / curl」变成「QQ 里直接聊」。
> 本文记录**为什么是这条路**、**当前实现长什么样**、**边界在哪**、**还差什么**。
> 最后更新：2026-11-20（官方机器人已落地 `adapter/qq`；白名单首次配置改成「日志里抄完整 openid」，见 §4）

---

## 1. 结论

两条路都通，现在走**官方机器人**：自助注册、合规、不会突然消失，对「只和自己聊」够用。
真正卡住的不是 QQ 协议，是**当前进程只有一份米游社凭据**——所以第一版只能是「只回白名单里的自己」。

## 2. 两条路线

| | 官方 QQ 机器人（开放平台）✅ 现行 | 第三方协议端 + OneBot v11（备选） |
|---|---|---|
| 场景 | 个人主体：频道 ✅、群（自己是群主/管理员）✅、**单聊 ✅**（「开发体验用户」，≤20 个号）；共同限制是**不能公开使用** | 私聊 / 群聊都能 |
| 合规 | 合规 | 违反腾讯用户协议，可能风控/封号 |
| 接入 | AppID/AppSecret + WS 网关（不需要公网） | 装 LLBot，扫码登录，开一个 WS 端口 |
| 上手 | 自助，创建即用 | 要人工申请授权，可能等很久 |

> 2026-11-18 复查官方 FAQ（[bot_FAQ](https://bot.q.qq.com/wiki/bot_FAQ/)）：个人认证可建 5 个机器人；
> 群聊可加「自己是群主/管理员」的群；**单聊通过「开发设置 → 开发体验用户号码」开放（上限 20 个）**；
> 个人主体只是「暂不支持公开使用」。对自用场景，这条口子是通的。

**为什么 OneBot 降级**：P10 要等 LLBot 人工开授权（等了两天无果），而官方通道是自助的。
两条路**唯一共用的是 `AgentChatService`**，所以 OneBot 那套协议代码不能复用——它现在是备选，不是主线。

## 3. 当前实现（官方机器人）

```
手机 QQ ──► 开放平台 ──WS 网关──► QqBotGateway ──► QqBotDispatcher ──► AgentChatService ──► 模型 + Tools
             (op10/op2/op1)          (协议)          (过滤→切片→回复)         │
                                        ▲                                      │
                                        └──── QqBotApi：token + POST ◄─────────┘
```

| 类 | 只负责 |
|---|---|
| [`QqBotGateway`](../src/main/java/com/luobo/hoyoagent/adapter/qq/QqBotGateway.java) | WS 生命周期：取网关地址、`op=2` identify、`op=1` 心跳、`op=7/9` 重连、指数退避（2s→60s）。不碰业务 |
| [`QqBotDispatcher`](../src/main/java/com/luobo/hoyoagent/adapter/qq/QqBotDispatcher.java) | 事件过滤（目前只接 `C2C_MESSAGE_CREATE`）→ 白名单 → 按 `msg_id` 去重 → 会话 id → 调 Agent → 错误文案 → 交给切片发送 |
| [`QqBotApi`](../src/main/java/com/luobo/hoyoagent/adapter/qq/QqBotApi.java) | `access_token` 刷新（7200s，提前 60s 换；401 再换一次重试）+ `POST /v2/users/{openid}/messages` |
| [`QqBotProperties`](../src/main/java/com/luobo/hoyoagent/adapter/qq/QqBotProperties.java) | `qq.*`：凭据、白名单、URL、切片上限（条数硬顶 4） |

### 协议事实（这几条决定实现，别照 OneBot 的经验猜）

- 事件里**没有 QQ 号，只有 openid** → 白名单和会话 id 都按 openid
- 入站 intents = `1 << 25`（`GROUP_AND_C2C_EVENT`）；群事件暂不处理
- 被动回复必须带 `msg_id`（事件里的 `d.id`），**5 分钟内有效**；
  同一 `msg_id` 下 `msg_seq` 必须递增，且**最多回 4 次**（`max-replies` 不能再调大）
- 走 **WebSocket 网关**而不是 Webhook：官方原话「不依赖公网服务器，适合个人单机使用」，家庭宽带没有固定公网 IP
- **主动消息**另有频控与权限门槛（`40034105`），不能当推送通道用 → 签到通知走独立 webhook（`hoyo.notify.*`）

### 会话 id

- 私聊：`qqbot:c2c:{openid}` —— 一个人一段上下文
- 群聊（未接）：`qqbot:group:{groupOpenid}:{userOpenid}` —— **不能**用群号当 id，
  否则全群共用一段对话，甲问完乙接着答
- 日志默认 `mask()`；**唯一例外**是「不在白名单」那一行，它故意打印完整 openid——
  那是首次配置时唯一的值来源，掩掉就永远配不通（openid 不是凭据，见 §4）

> ⚠️ 旧文档写的是 `qq:c2c:{id}`，代码实际是 `qqbot:c2c:`。**改前缀会让 `~/.hoyo/memory` 里的旧会话对不上**，
> 等于丢历史；要么现在就改（只有自己在用，代价最小），要么以后别再改。

## 4. 配置

| 变量 | 默认 | 说明 |
|---|---|---|
| `QQ_BOT_ENABLED` | `false` | 默认关：没凭据也能正常启动，签到调度照跑 |
| `QQ_APP_ID` / `QQ_APP_SECRET` | 空 | 开放平台 → 开发设置 |
| `QQ_ALLOWLIST` | 空 | **openid**（不是 QQ 号），逗号分隔；空 = 谁都不回。填纯数字会在启动时被警告——本通道事件里根本没有 QQ 号 |
| `QQ_API_BASE` | `https://api.sgroup.qq.com` | 沙箱 `https://sandbox.api.sgroup.qq.com` |

### 白名单怎么拿到值（首次配置的唯一路径）

1. 把 `QQ_ALLOWLIST` 留空启动、手机给机器人发一条任意消息；
2. 日志里会有一行 `[QQ] 不在白名单：openid=xxxx…`，**这一行不掩码**，整串复制；
3. 填回 `.env` 的 `QQ_ALLOWLIST=`，重启。之后再收到陌生人的消息也只会打印他的 openid——那是他的，不是你的。

不要试图填 QQ 号：`C2C_MESSAGE_CREATE` 事件里只有 `author.user_openid`，出站也是
`POST /v2/users/{openid}/messages`，平台没有任何 openid ↔ QQ 号 的换算接口。
填 QQ 号的直接后果是白名单永远不命中（fail closed），机器人一条也不回。
另外 `user_openid` 是**按机器人隔离**的，换 AppID 重建机器人后值会变，白名单得重新填。

`max-replies` / `reply-chunk-chars` 写在 [`application.yml`](../src/main/resources/application.yml)，
不走环境变量：前者是平台硬上限，后者调错了只会让消息更碎。

## 5. 安全

`AccountService` 按 `userId` 解析凭据（`@ToolMemoryId` → `SessionRegistry` → `userId`），
`CheckinTool` 也不接受身份参数（[mihoyo-auth.md](mihoyo-auth.md) §7：身份只能由框架注入）。

群聊仍然不接：`QqBotDispatcher` 直接忽略 `GROUP_AT_MESSAGE_CREATE`。白名单挡的是"谁能触发"，
挡不住"谁能看见"——回复发在群里，白名单里任何人的签到数据都会展示给全群。
所以在 P11-5 真机验收、以及群聊的可见性问题想清楚之前，闸门只有一条：**白名单，且默认拒绝**。

## 6. 排障

| 症状 | 先看这里 |
|---|---|
| 启动日志 `[QQ] 未启用` | `QQ_BOT_ENABLED` 还是 false |
| `[QQ] 缺 QQ_APP_ID / QQ_APP_SECRET` | 凭据没进环境（`.env` 是否在仓库根目录） |
| `[QQ] QQ_ALLOWLIST 为空` | 这是 fail closed，所有消息都会被忽略；照 §4「白名单怎么拿到值」走一遍 |
| `[QQ] 白名单条目 … 是纯数字，像是 QQ 号` | 填错了：本通道只认 openid，见 §4 |
| 连上了但不回 | 日志搜「不在白名单」，那一行里的 openid **是完整值**（不掩码），整串填进 `QQ_ALLOWLIST` |
| 回「【出错了】…」 | Agent/模型报错，看同时间点的服务端日志 |
| `[QQ] 401，换 token 后重试` 之后仍失败 | AppSecret 错了，或机器人被停用 |
| `[QQ] 网关断开` 反复出现 | 本机网络/代理问题；退避会从 2s 涨到 60s |
| 突然不回 | 机器人被踢下线 / 被限流；看开放平台后台 |

## 7. 已知限制（明确不做的）

- **只处理文本**：图片/语音一律不进模型
- **只接私聊**：群聊事件直接忽略
- **单凭据**：只能自用（见 §5）
- **去重只在内存**：最近 500 个 `msg_id`，重启后清空；平台重投同一事件时会重复回一次
- **没有主动推送**：QQ 侧只有被动回复；签到通知走 webhook
- **消息不落库**：重启即丢，记忆本来也在内存 + 文件里

## 8. 下一步

1. **P11-5 真机验收**：手机发「签到了吗」5 秒内收到；白名单外的 openid 不回
2. **抽 `channel` 层**（Step 1）：见 [ROADMAP](ROADMAP.md) P10-6 / P11-3 的后续——
   把「白名单 / 去重 / 会话 id / 同会话串行 / 错误文案 / 切片」从 `adapter/qq` 提成平台无关的
   `MessagePipeline`，出站收口成 `ReplySender`。**切片这一半已经抽完了**
   （[`TextChunker`](../src/main/java/com/luobo/hoyoagent/channel/TextChunker.java)），
   剩下的等接第二个平台（TG）时一起做——用第二个实现验证抽象，别提前设计
3. **同会话串行**：现在每条消息一个虚拟线程，同一会话连发两条可能乱序，记忆窗口按完成顺序写
4. **P8 多账号**：`@ToolMemoryId → userId → 凭据`，这是进群的前置

## 9. OneBot 备选（P10）

Step 0 的一次性桥 `scripts/qq-bridge/QQBridge.java` 已删除——它已经完成使命（验证了
「OneBot → `/agent` → 回复」这条路可行），但和现在的官方实现是两套协议，留着只会让配置和排障更难。
需要时从 git 取回：

```powershell
git show 3dcd255:scripts/qq-bridge/QQBridge.java > scripts/qq-bridge/QQBridge.java
```

那条路的不变量仍然成立：**只依赖 OneBot 11 协议，绝不碰任何协议端的私有 API**，
这样换协议端只改一行 URL。协议端现状（2026-11-16 核实）：NapCat 已停止、LLBot 活跃、
Lagrange 不再支持 OneBot 11、go-cqhttp 长期停更。第三方协议端在腾讯眼里算外挂，**用小号**。

## 10. 合规

- 官方通道（当前）合规，个人主体只是不能公开使用
- 第三方协议端（备选）违反腾讯用户协议，账号可能被风控/封号——风险自担
- 米游社 cookie 的敏感性等同于账号密码（[ROADMAP](ROADMAP.md) P4 的坑记过），
  别让它出现在群聊回复里

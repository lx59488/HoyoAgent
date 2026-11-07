# 米游社账号接入与多用户绑定设计

> 状态：**设计稿，尚未实现**。当前项目只支持单账号，cookie 手填在 `.env` 里。
> 本文档锁定「多用户绑定 + 独立签到 + agent 记忆隔离」的目标形态与实施顺序，供 P7/P8 阶段接入。
> 参考实现：[FufuLauncher](https://github.com/FufuLauncher/FufuLauncher)（MIT，C#），本文只提取事实，不复制代码。

---

## 0. 为什么现在只写文档

现状（2026-11-07）：

- ✅ 能查角色列表（`character/list`）、角色详情（`character/detail`）
- ✅ 能查绑定角色（`getUserGameRolesByCookie`）
- ❌ **没有 `stoken`**，所以签到走不通（实测 `luna/info` 恒返回 `-100`）
- ❌ 单账号、凭据明文在 `.env`、无登录流程

多用户登录要动的东西很多（登录、加密存储、调度、记忆隔离），在 P5/P6 没做完时插入会把主线拖垮。**本文档的作用是：等主线做完，照着它按阶段推进，不用重新调研。**

---

## 1. 目标与非目标

**目标**

- 多个用户各自绑定米游社账号
- 每个用户独立查询自己的数据（工具自动识别"当前是谁"）
- 每个用户独立签到，互不影响
- 凭据过期能自动刷新，而不是让用户重新贴 cookie

**非目标（这一轮不做）**

- Web/App UI（先用 HTTP 接口或聊天平台）
- 国际服（hoyolab）
- 密码登录（风控重、责任大）
- 第三方登录（微信/QQ 直登）

---

## 2. 术语与 token 生命周期（最重要的部分）

米游社的凭据不是"一个 token"，是**四层**，各有各的用途和寿命：

```
login_ticket（30 分钟，一次性）
      │  换
      ▼
stoken + mid  ──────────────► 米游社内的操作：签到、活动、社区互动
      │  换
      ▼
ltoken + ltuid（ltoken_v2 / ltuid_v2）──► 查游戏战绩：角色、深淵、树脂
      │
      ▼
cookie_token + account_id ────► 部分接口的额外凭据（如 genAuthKey）
```

### 我们踩过的实证

| 请求 | 用的凭据 | 结果 |
|---|---|---|
| `GET /binding/api/getUserGameRolesByCookie` | ltoken | ✅ `retcode:0`，无需 DS |
| `POST /game_record/.../character/list` | ltoken | ✅ `retcode:0`，无需 DS |
| `GET /event/luna/hk4e/info`（签到） | ltoken | ❌ `-100 请登录后重试` |

**结论（待最终验证）**：查询类接口用 `ltoken` 就够；**签到需要 `stoken`**，而 `stoken` 只能通过登录流程获得——这正是我们现在的 cookie 里缺的那一块。

UIGF 文档的描述与之吻合：

> `ltoken` 多用于查询用户的游戏账号信息
> `stoken` 多用于在米游社内的操作

---

## 3. 三种登录方式

| 方式 | 端点 | 难点 | 风控强度 | 建议 |
|---|---|---|---|---|
| **扫码** | `passport-api.mihoyo.com/account/ma-cn-passport/web/createQRLogin` | 轮询状态机 | 低 | ✅ **首选** |
| 手机验证码 | `.../ma-cn-verifier/verifier/createLoginCaptcha`<br>`.../ma-cn-passport/app/loginByMobileCaptcha` | 手机号要 RSA 加密；可能要 `X-Rpc-Aigis` 风控票 | 高 | 备选 |
| 密码 | `loginByPassword` | 密码 RSA 加密 + 强风控 | 最高 | 不做 |

### 扫码登录时序

```
用户               你的服务                     米游社
 │                    │                          │
 │  点击「绑定账号」   │                          │
 │───────────────────►│  createQRLogin           │
 │                    │─────────────────────────►│
 │                    │◄── {url, ticket} ────────│
 │◄── 显示二维码 ──────│                          │
 │                    │                          │
 │  用米游社 App 扫码  │                          │
 │──────────────────────────────────────────────►│
 │                    │  queryQRLoginStatus(轮询) │
 │                    │─────────────────────────►│
 │                    │◄── status=Confirmed + cookies(stoken, mid)
 │                    │                          │
 │◄── 绑定成功 ────────│                          │
```

关键点：

- **轮询间隔 2 秒**，二维码有效期约 5 分钟
- 拿到的 cookie 里含 `stoken` + `mid`，这才是签到的入场券
- 整个流程不需要用户输入密码，也不会触发短信风控

### 请求头（passport 系）

扫码和验证码登录都走 `passport-api.mihoyo.com`，需要一套固定头：

```
x-rpc-app_id:      bll8iq97cem8
x-rpc-app_version: 2.95.0        （与 DS 的 salt 配对）
x-rpc-client_type: 2
x-rpc-device_id:   <每用户固定>
x-rpc-game_biz:    bbs_cn
x-rpc-sdk_version: 2.95.0
DS:                Gen2（salt=JwYDpKvLj6MrMqqYU6jTKF17KNO2PXoS）
```

注意 passport 用的是 **DS2**（带 `&b={body}&q={query}`），不是我们查战绩用的 DS1。

### stoken → ltoken 换取

```http
GET https://passport-api.mihoyo.com/account/auth/api/getLTokenBySToken
Cookie: mid=<mid>; stoken=<stoken>; stuid=<uid>

GET https://passport-api.mihoyo.com/account/auth/api/getCookieAccountInfoBySToken
Cookie: mid=<mid>; stoken=<stoken>; stuid=<uid>
```

这两个接口不需要 DS，只需要 `stoken` + `mid`。

---

## 4. 分阶段实施计划

| 阶段 | 内容 | 预估 | 验收 |
|---|---|---|---|
| **A** | 扫码登录拿到 `stoken` + `mid` | 1 天 | 两个值能打印出来，且用它们换到 ltoken |
| **B** | `stoken` → `ltoken` / `cookie_token` | 半天 | 用换到的 ltoken 成功查角色列表 |
| **C** | 多用户凭据存储（加密 + 按 userId 检索） | 1 天 | 两个账号的数据互不串 |
| **D** | per-user 工具与记忆隔离 | 1 天 | 用户 A 问"我有哪些角色"，只返回 A 的 |
| **E** | 签到调度（幂等 + 重试 + 通知） | 1 天 | 连续 3 天自动签到成功，不重复签 |

**A 和 B 是硬前置**，C~E 可以并行。

---

## 5. 数据模型

```java
/** 一个聊天平台用户绑定的米游社账号 */
public record MiyousheAccount(
        String accountId,        // 内部主键
        String userId,           // 平台用户 id（QQ/微信/HTTP 会话）
        String miyousheUid,      // 米游社 uid（ltuid）
        String mid,              // mid，与 stoken 配对
        String stokenEnc,        // 加密后的 stoken
        String ltokenEnc,        // 加密后的 ltoken
        String cookieTokenEnc,   // 加密后的 cookie_token
        String deviceId,         // 这个账号绑定的设备指纹（必须稳定）
        Instant createdAt,
        Instant lastRefreshedAt
) {}

/** 账号下绑定的游戏角色（原神/星铁/绝区零各一条） */
public record GameRole(
        String roleId,      // 内部主键
        String accountId,
        String gameBiz,     // hk4e_cn / hkrpg_cn / nap_cn
        String region,      // cn_gf01 ...
        String gameUid,     // 153383881
        String nickname,
        int level,
        Instant updatedAt
) {}
```

表结构（上数据库时）：

```sql
CREATE TABLE mihoyo_accounts (
    account_id     VARCHAR(32) PRIMARY KEY,
    user_id        VARCHAR(64) NOT NULL,
    miyoushe_uid   VARCHAR(32),
    mid            VARCHAR(64),
    stoken_enc     TEXT        NOT NULL,
    ltoken_enc     TEXT,
    cookie_token_enc TEXT,
    device_id      VARCHAR(64) NOT NULL,
    created_at     TIMESTAMP   NOT NULL,
    last_refreshed_at TIMESTAMP,
    UNIQUE (user_id, miyoushe_uid)
);

CREATE TABLE mihoyo_roles (
    role_id    VARCHAR(32) PRIMARY KEY,
    account_id VARCHAR(32) NOT NULL REFERENCES mihoyo_accounts(account_id),
    game_biz   VARCHAR(32) NOT NULL,
    region     VARCHAR(32) NOT NULL,
    game_uid   VARCHAR(32) NOT NULL,
    nickname   VARCHAR(64),
    level      INT,
    updated_at TIMESTAMP,
    UNIQUE (account_id, game_biz, game_uid)
);

-- 签到幂等的核心
CREATE TABLE checkin_records (
    account_id VARCHAR(32) NOT NULL,
    role_id    VARCHAR(32) NOT NULL,
    sign_date  DATE        NOT NULL,
    result     VARCHAR(32) NOT NULL,   -- SUCCESS / ALREADY / FAILED
    created_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (role_id, sign_date)
);
```

---

## 6. 凭据加密

- 算法：**AES-256-GCM**，每条记录独立 IV
- 密钥：环境变量 `HOYO_CRED_KEY`（32 字节 base64），**绝不进仓库**
- 加密字段：`stoken`、`ltoken`、`cookie_token`
- 明文可存：`uid`、`mid`、`device_id`（不是凭据，但也不要进日志）
- 日志：只允许打印 `uid` 和后 4 位尾号

```java
public interface CredentialCipher {
    String encrypt(String plain);
    String decrypt(String cipher);
}
```

密钥轮换时，留一个 `keyVersion` 字段，新数据用新密钥、旧数据惰性迁移。

---

## 7. 与 Agent 记忆系统的关系（你问的核心）

### 三个 id 不要混

| id | 含义 | 来源 |
|---|---|---|
| `userId` | **谁**在用这个 bot（QQ 号、微信 openid） | 平台适配器 |
| `memoryId` | 哪一段**对话** | 聊天平台会话 id |
| `miyousheUid` | 绑定的**米游社账号** | 登录流程 |

一个 `userId` 可能绑一个米游社账号；一个 `memoryId` 属于某个 `userId`。

### 框架提供的能力（已核实）

```java
// 按 memoryId 分配会话记忆
public interface ChatMemoryProvider {
    ChatMemory get(Object memoryId);
}

// 让工具拿到"当前对话的 memoryId"
public @interface ToolMemoryId {}
```

`@ToolMemoryId` 标在工具参数上，**框架注入，模型看不到也不会填**：

```java
@Tool("查询我绑定的原神角色列表")
public String myCharacters(@ToolMemoryId String memoryId) {
    String userId = sessionRegistry.userIdOf(memoryId);
    MiyousheAccount account = accountService.requireBound(userId);
    List<GameRole> roles = roleService.list(account.accountId());
    ...
}
```

### 最重要的一条安全规则

> **用户身份只能来自框架注入，绝不能来自模型输出的参数。**

也就是说：工具的签名里**不允许**出现 `uid`、`userId`、`accountId` 这类"指定查谁"的参数。

```java
// ❌ 危险：模型可以填别人的 uid
@Tool("查询角色列表")
String characters(@P("游戏UID") String uid)

// ✅ 安全：身份由框架决定
@Tool("查询角色列表")
String characters(@ToolMemoryId String memoryId)
```

这条规则决定了多用户场景下整个系统的安全边界。前端来的 `userId` 在进入 AiService 前就要绑定到 `memoryId`，之后工具层不再接受任何外部身份参数。

### 记忆隔离

```java
@Bean
ChatMemoryProvider chatMemoryProvider() {
    return memoryId -> MessageWindowChatMemory.builder()
            .id(memoryId)
            .maxMessages(20)
            .build();
}
```

注意：这只是"按 memoryId 分桶"，**不清理旧会话**。多用户场景要加过期策略（比如 30 分钟无交互则回收），否则内存会一直涨。

---

## 8. 签到调度

- 调度器：Spring `@Scheduled(cron = "0 10 0 * * *", zone = "Asia/Shanghai")`（北京时间 00:10，避开整点）
- 任务粒度：**每个 role 一条**，不是一个用户一条（一个用户可能绑定原神+星铁）
- 幂等：先查 `checkin_records`，当天有记录就跳过；插入时靠主键冲突兜底
- 顺序：先 `info` 查 `is_sign` → 未签才 `sign` → 再 `info` 复验
- 失败重试：最多 2 次，间隔 30s / 120s；**风控码（1034 / `-500004`）不重试**
- 结果：写 `checkin_records`，并按用户汇总成一条消息推回平台
- 并发：单账号串行，账号间可并发，上限 3（参考 FufuLauncher 的 `MaxConcurrentGames`）

---

## 9. 风险与合规

1. **自动化访问违反米游社用户协议**。单账号自用风险可控；**代多人签到性质不同**，风险明显更高。
2. **不要把服务开放给陌生人**。一旦对外提供，就是"给第三方账号做自动化"，法律与封号风险都会放大。
3. **用户数据最小化**：只存 `uid`/`mid` 和加密后的 token；不存手机号、密码。
4. **提供解绑**：删除账号时必须清空 token 和签到记录。
5. **频率控制**：每账号对米游社的请求 ≤ 1 req/s，全局 ≤ 5 req/s。

---

## 10. 参考实现索引（FufuLauncher，MIT）

| 文件 | 看什么 |
|---|---|
| `Services/MiHoYo/Passport/PassportClient.cs` | 验证码登录、`stoken → ltoken` 换取、passport 头与 DS2 |
| `Services/MiHoYo/Passport/PassportRsaCrypto.cs` | 手机号的 RSA 加密方式 |
| `Services/Qr/MiyousheQrLoginService.cs` | 扫码登录时序、`stoken`/`mid` 的提取、二维码票据流转 |
| `Services/Account/TokenRefreshService.cs` | token 过期后的刷新策略（19KB，最值得读） |
| `Services/Account/AccountManager.Cookies.cs` | cookie 的组装、校验与迁移 |
| `Services/Checkin/MiyousheGameCheckinService.cs` | 签到主流程、幂等、限流、重试 |
| `Models/Checkin/MiyousheCheckinGame.cs` | 各游戏 `act_id` 与 host 常量 |
| `Constants/MiHoYo/HeaderSalts.cs` | 各场景 salt（**与 app_version 配对**，不能混用） |
| `Constants/ApiEndpoints.cs` | 全部端点清单，可作为接口索引 |

**注意**：它固定 `x-rpc-app_version = 2.93.1` 并配对应 salt。我们实测该组合在我们这打 `luna/info` 仍返回 `-100`——因为缺 `stoken`，不是版本问题。

---

## 11. 现在就能做的准备（不写登录也能做）

这三件事不依赖登录流程，做完能省后面半天：

1. **把 `GameRole` 模型和 `getUserGameRolesByCookie` 接上**（已实测可用、无需 DS）
2. **工具签名改成 `@ToolMemoryId`**，先用一个固定的 `userId` 顶上，等登录做完再换成真实映射
3. **`CredentialCipher` 接口先定义**，实现可以先放一个"明文直通"的假实现，加密留到真正存 token 时再补

这样做的好处：等登录流程做完，你只需要把 `stoken` 塞进已有的 `MiyousheAccount`，而不是重构一遍。

---

## 附：待验证清单

- [ ] 扫码登录拿到的 cookie 是否直接包含 `stoken` + `mid`
- [ ] `stoken` 有效期（文档说"改密码后失效"，但没说自然过期时间）
- [ ] `luna/info` 带上 `stoken` 后是否返回 `retcode:0`
- [ ] `first_bind` 为 true 时的正确交互
- [ ] 签到风控触发后的冷却时间
- [ ] 多账号是否共享 `device_id`（理论上应各自独立）

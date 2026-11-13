# 米游社账号接入与多用户绑定设计

> 状态：**登录 + 单账号签到已落地并真机验证（2026-11-12）**。
> A（扫码登录）、B（换票 + v2）、E 的单角色签到与幂等已通；E 的定时调度/通知未做。
> 多用户绑定（C/D）仍是设计。当前项目只有一个账号（cookie 来自 `.env` 或扫码写入的文件）。
> 参考实现：[FufuLauncher](https://github.com/FufuLauncher/FufuLauncher)（MIT，C#），本文只提取事实，不复制代码。
> 事实核对基准：`master@e643627`（2026-11-12）。

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
stoken + mid  ──────────────► 社区操作、genAuthKey、换 v2 凭据、游戏扫码确认
      │  换
      ▼
ltoken + ltuid（ltoken_v2 / ltuid_v2）──► 查战绩：角色、深渊、树脂
      │
      ▼
cookie_token + account_id ────► 每日签到（luna）、部分接口的额外凭据
```

### 我们踩过的实证

| 请求 | 用的凭据 | 结果 |
|---|---|---|
| `GET /binding/api/getUserGameRolesByCookie` | ltoken | ✅ `retcode:0`，无需 DS |
| `POST /game_record/.../character/list` | ltoken_v2 | ✅ `retcode:0`，无需 DS（11-07） |
| `POST /game_record/.../character/list` | 只有 ltoken v1 | ❌ `5003`（无 message） |
| `GET /event/luna/info`（签到，11-07） | ltoken | ❌ `-100 请登录后重试` |
| `GET /event/luna/info`（签到，11-12） | ltoken + 完整签到头 | ❌ 仍 `-100` |

**11-12 的对照实验**（同一账号、同一 uid `100848866`、同一套头，只改 cookie 字段）：

| cookie 组合 | 结果 |
|---|---|
| `cookie_token` + `account_id` | ✅ `retcode:0` |
| `stoken` + `mid` + `stuid` | ❌ `-100` |
| `ltoken` + `ltuid` | ❌ `-100` |
| `stoken`+`mid`+`stuid`+`ltoken`+`ltuid` | ❌ `-100` |
| 完整 cookie | ✅ `retcode:0` |
| `cookie_token` 单独 / `account_id` 单独 | ❌ `-100` |

**结论（已确认，且推翻了原来的判断）**：

- **游戏签到（luna）认的是 `cookie_token` + `account_id`，不是 `stoken`。** 两个缺一不可。
- 11-07 那次 `-100` 的真实原因不是"缺 stoken"，而是：(a) 用的 uid 不属于当前 cookie 对应的账号，或
  (b) `cookie_token` 已过期。**"uid 不属于当前账号"这个坑会伪装成"没登录"**，这次实际踩到了。
- `stoken` 的用途是**米游社社区操作**（发帖、社区签到）、`genAuthKey`（抽卡链接）、以及**换 v2 凭据**；
  它确实值钱，只是不负责游戏签到。
- 战绩接口（`character/list`、`dailyNote`）只认 **v2**（`ltoken_v2` / `cookie_token_v2`）。
  只有 v1 会返回无 message 的 `5003`——这个码极容易误判成"没权限"。

UIGF 文档的描述与之吻合：

> `ltoken` 多用于查询用户的游戏账号信息
> `stoken` 多用于在米游社内的操作

---

## 3. 登录：两条扫码链路（别混）

米游社有**两种二维码**，方向完全不同。FufuLauncher 两条都实现了，我们也需要两条，但优先级不同：

| 链路 | 端点 | 谁扫谁 | 拿到什么 | 用途 |
|---|---|---|---|---|
| **App 登录码** | `.../ma-cn-passport/app/createQRLogin`<br>`.../ma-cn-passport/app/queryQRLoginStatus` | 我们显示、**手机米游社扫** | `data.tokens[].stoken` + `data.user_info.{mid,aid}` | ✅ 首次登录；**唯一能凭空拿到 stoken 的方式** |
| **Web 确认码** | `.../ma-cn-passport/web/createQRLogin`<br>`.../ma-cn-passport/app/scanQRLogin`<br>`.../ma-cn-passport/app/confirmQRLogin`<br>`.../ma-cn-passport/web/queryQRLoginStatus` | 我们先建码，再用**已有 stoken 冒充手机**去 scan+confirm | 响应头 `Set-Cookie`：`ltoken_v2`/`cookie_token_v2`/`stuid` | 🔄 续期：stoken 没坏、其它票过期时，不用再找手机 |

> 本文早先把"用户扫码登录"写成 `web/createQRLogin`，那是把两条链路记混了：
> **web 码自己扫不出 stoken**，它要么给浏览器用，要么就得像 FufuLauncher 那样拿已有 stoken 去冒充手机。
> 真正"手机扫一下就登录"的是 **app 码**。

| 其他方式 | 端点 | 难点 | 风控强度 | 建议 |
|---|---|---|---|---|
| 手机验证码 | `.../ma-cn-verifier/verifier/createLoginCaptcha`<br>`.../ma-cn-passport/app/loginByMobileCaptcha` | 手机号 RSA 加密；可能要 `X-Rpc-Aigis` 风控票 | 高 | 备选 |
| 密码 | `loginByPassword` | 密码 RSA + 强风控 | 最高 | 不做 |

### App 登录码时序（已实现）

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
 │                    │  app/queryQRLoginStatus   │
 │                    │─────────────────────────►│
 │                    │◄── status=confirmed ─────│
 │                    │    user_info{mid,aid}     │
 │                    │    tokens[{name:stoken}]  │
 │                    │                          │
 │◄── 绑定成功 ────────│                          │
```

关键点（都是实测踩出来的）：

- **device_id 必须全程一致**：`create` 和 `query` 用不同 device_id，轮询直接 `-3503 当前设备或网络环境存在风险`。
  所以设备身份要持久化（`~/.hoyo/passport-device`），不是每次请求随机生成。
- 二维码有效期约 5 分钟；`-3501` / `-106` = 已过期；轮询 2~3 秒一次足够，别调成 200ms。
- `confirmed` 响应里**直接给 stoken**（`data.tokens[]`），不需要 `login_ticket` 再换——这是 app 码相对 web 码最大的好处。
- `data.user_info` 同时给 `mid` 和 `aid`；`aid` 就是后面 cookie 里的 `stuid`/`ltuid`。

### 两套请求头（混用必失败）

扫码系和换票系是**两套独立配置**，salt 与 app_version 必须配对：

| 项 | 扫码系（create/query QR） | 换票系（getLToken / getCookieAccountInfo） |
|---|---|---|
| `x-rpc-client_type` | `3` | `2` |
| `x-rpc-app_version` | `2.90.1` | `2.95.1` |
| `x-rpc-sdk_version` | `2.90.1` | `2.16.0` |
| `DS` | DS2，salt `dDIQHbKOdaPaLuvQKVzUzqdeCaxjtaPV` | DS2，salt `JwYDpKvLj6MrMqqYU6jTKF17KNO2PXoS` |
| `x-rpc-device_id` | 16 位大写 hex（持久化） | 账号 UUID（可复用 `~/.hoyo/device-id`） |
| 其他 | `x-rpc-device_fp`、`x-rpc-device_model: Mi 14`、`x-rpc-device_name`、`x-rpc-game_biz: bbs_cn`、`x-rpc-app_id: bll8iq97cem8` | `x-rpc-aigis`（可空）、`x-rpc-game_biz: bbs_cn`、`x-rpc-app_id` 同上 |
| UA | `Mozilla/5.0 miHoYoBBS/2.90.1 Capture/2.2.0` | `Mozilla/5.0 (Windows NT 10.0; Win64; x64) miHoYoBBS/2.95.1` |

注意 passport 用的是 **DS2**（带 `&b={body}&q={query}`），不是查战绩用的 DS1。
`b` 必须和实际请求体**逐字节一致**（JSON 别多加空格），否则 `-10001`。

### stoken → ltoken 换取

```http
GET https://passport-api.mihoyo.com/account/auth/api/getLTokenBySToken
Cookie: mid=<mid>; stoken=<stoken>; stuid=<uid>

GET https://passport-api.mihoyo.com/account/auth/api/getCookieAccountInfoBySToken
Cookie: mid=<mid>; stoken=<stoken>; stuid=<uid>
```

- 返回 `data.ltoken` 和 `data.cookie_token`。
- **需要 DS2**（salt `JwYDpKv…`、2.95.1）——早期笔记写的"不需要 DS"是错的。
- 不需要 body；`stuid` 用 confirmed 里的 `aid`。

---

## 4. 分阶段实施计划

| 阶段 | 内容 | 预估 | 验收 | 状态（11-12） |
|---|---|---|---|---|
| **A** | 扫码登录拿到 `stoken` + `mid` | 1 天 | 两个值能打印出来，且用它们换到 ltoken | ✅ 真机扫码通过（`WAITING→SCANNED→BOUND`） |
| **B** | `stoken` → `ltoken` / `cookie_token` / `*_v2` | 半天 | 用换到的 ltoken 成功查角色列表 | ✅ 全部换到；战绩查询受账号侧 `5003` 限制（见 §12） |
| **C** | 多用户凭据存储（加密 + 按 userId 检索） | 1 天 | 两个账号的数据互不串 | 未开始 |
| **D** | per-user 工具与记忆隔离 | 1 天 | 用户 A 问"我有哪些角色"，只返回 A 的 | 未开始（依赖 P7 的 `ChatMemory`） |
| **E** | 签到调度（幂等 + 重试 + 通知） | 1 天 | 连续 3 天自动签到成功，不重复签 | 🚧 单角色签到 + 幂等已通；**调度/通知未做** |

**A 和 B 是硬前置**，C~E 可以并行。

> A 的入口（已实现）：
> `POST /api/auth/qr` → `GET /api/auth/qr/{ticket}/image`（PNG）→ `GET /api/auth/qr/{ticket}` 轮询。
> 成功后写 `~/.hoyo/credential.properties`，启动时优先于 `.env`（因为它带 stoken）。
> v2 续期不需要扫码：`POST /api/auth/refresh`。

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

### 协议细节（从 FufuLauncher 抄下来的确定值）

```http
GET  {host}/event/luna/info?lang=zh-cn&act_id=..&region=cn_gf01&uid=153383881
POST {host}/event/luna/sign   {"act_id":"..","region":"cn_gf01","uid":"153383881"}
GET  {host}/event/luna/home?lang=zh-cn&act_id=..     ← 奖励名（可选，用于汇报）
```

| 项 | 值 | 备注 |
|---|---|---|
| host | `https://api-takumi.mihoyo.com` | 绝区零用 `https://act-nap-api.mihoyo.com/event/luna/zzz` |
| cookie | **完整 cookie**（关键是 `cookie_token` + `account_id`） | `stoken`/`ltoken` 单独都不行；实测见 §2 |
| 必需字段 | `cookie_token` **且** `account_id` | 缺一即 `-100` |
| DS | DS1，salt `G1ktdwFL4IyGkHuuWSmz0wUe9Db9scyK` | 注意这个 salt 在 FufuLauncher 里叫 `WebMihoyo` |
| `x-rpc-app_version` | `2.93.1` | 与上面 salt 配对 |
| `x-rpc-client_type` | `5` | |
| `x-rpc-channel` | `miyousheluodi` | 缺了会被风控 |
| `x-rpc-signgame` | `hk4e`（原神）/ `zzz`（绝区零）；星铁不传 | |
| `Referer` / `Origin` | `https://act.mihoyo.com/` | 崩坏3/2 用 webstatic 的签到页 |
| `x-rpc-device_id` | `md5(完整 cookie)` | FufuLauncher 的取巧：设备 id 跟着 cookie 走 |

各游戏 `act_id`（FufuLauncher 里是常量，不是"每期变"）：

| 游戏 | game_biz | act_id |
|---|---|---|
| 原神 | `hk4e_cn` | `e202311201442471` |
| 星穹铁道 | `hkrpg_cn` | `e202304121516551` |
| 绝区零 | `nap_cn` | `e202406242138391` |
| 崩坏3 | `bh3_cn` | `e202306201626331` |

其他判定：`first_bind=true` 表示还没在 App 里绑定该角色，**不能签**，要提示用户先去米游社绑定；
`sign` 返回 0 但 `data.success != 0` 是触发验证码（风控），不是成功。

> 实现坑：已签到的账号返回里 `first_bind` 可能是 **null**（不是 false）。用原始 `boolean` 接会
> 反序列化失败，必须用 `Boolean` 包装类型（`LunaInfo.isFirstBind()` 内部做 `Boolean.TRUE.equals`）。

### 11-12 实测结果（本轮代码）

| 游戏 | 结果 | 奖励 | 累计天数 |
|---|---|---|---|
| 原神 `100848866` | 手动签过一次后，代码返回 `ALREADY` | 「甜甜花酿鸡」x3 | 5 |
| 星铁 `103871907` | 代码 `SIGNED` | 「星琼」x20 | 5 |
| 绝区零 `15911914` | 代码 `SIGNED` | 「菲林」x20 | 5 |

幂等已验证：同一天再调 `POST /api/checkin` 只返回"已签"，不会重复签。

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
| `Views/Windows/LoginQrWindow.AppQrLogin.cs` | **真正能拿到 stoken 的扫码登录**：建码 + 轮询 + 提取 `tokens[].stoken` |
| `Views/Windows/LoginQrWindow.V2Exchange.cs` | 换 `ltoken`/`cookie_token`，再用 web 码冒充手机刷出 `*_v2` |
| `Views/Windows/LoginQrWindow.xaml.cs` | `AddCommonHeaders`：扫码系头的完整清单与 DS 生成 |
| `Services/Account/TokenRefreshService.cs` | token 过期后的刷新策略（19KB，最值得读） |
| `Services/Account/AccountManager.Cookies.cs` | cookie 的组装、校验与迁移 |
| `Services/Checkin/MiyousheGameCheckinService.cs` | 签到主流程、幂等、限流、重试 |
| `Models/Checkin/MiyousheCheckinGame.cs` | 各游戏 `act_id` 与 host 常量 |
| `Models/MihoyoBBS/Tools.cs` | 签到用的 DS1（salt `G1ktdw…`）与 `md5(cookie)` 设备 id |
| `Services/Qr/MiyousheQrLoginService.cs` | ⚠️ 不是登录码：它是**游戏登录二维码**的确认流程（拿的是游戏 token） |
| `Constants/MiHoYo/HeaderSalts.cs` | 各场景 salt（**与 app_version 配对**，不能混用） |
| `Constants/ApiEndpoints.cs` | 全部端点清单，可作为接口索引 |

**注意**：签到路径固定 `x-rpc-app_version = 2.93.1` + DS1 salt `G1ktdwFL4IyGkHuuWSmz0wUe9Db9scyK`。
11-12 第一次用这套头 + ltoken 打 `luna/info` 得到 `-100`，当时的结论"因为缺 stoken"是**错的**——
真正原因是 uid 不属于当前账号 + `cookie_token` 过期。§2 的对照实验已经修正。

---

## 11. 历史：登录前就能做的准备（已完成）

这三件事在 P6/P9 之间已经落地，留在这里只为解释当前结构为什么长这样：

1. **`GameRole` + `getUserGameRolesByCookie`** → 现在由 `GameRoleService` 自动发现角色，
   彻底替掉了 `HOYO_ROLE_ID`（写死 uid 在换账号后会指向别人的号）。
2. **工具签名无身份参数** → `PlayerTool` / `CheckinTool` 都是零参数，
   身份来自 cookie；多用户时再换成 `@ToolMemoryId` 注入。
3. **凭据存储** → 现在用 `CredentialStore`（明文、owner-only）落到
   `~/.hoyo/credential.properties`；`CredentialCipher` 加密留到 C 阶段多用户时再做。

---

## 12. 实施记录（2026-11-12，登录 + 签到落地）

### 新增代码

| 文件 | 职责 |
|---|---|
| `mihoyo/sign/DsSigner.java` | DS1（`t,r,md5`）与 DS2（带 `b`/`q`）；旧的 `Signer` 已删除，统一入口 |
| `mihoyo/auth/passport/PassportDevice.java` | 持久化 16 位 hex `device_id` + `device_fp`（`~/.hoyo/passport-device`） |
| `mihoyo/auth/passport/PassportClient.java` | 建码 / 轮询 / 换 ltoken / 换 cookie_token / **v2 换取**；三套头（`QR_APP`/`QR_WEB`/`EXCHANGE`）都走 `MihoyoHttp`，Set-Cookie 从响应取、不存共享字段 |
| `mihoyo/auth/QrLoginService.java` | 状态机 `WAITING→SCANNED→BOUND`，换票只做一次，3 分钟过期；`refreshV2()` 静默续期 |
| `mihoyo/auth/CredentialStore.java` | 写 `~/.hoyo/credential.properties`（明文，owner-only） |
| `mihoyo/auth/CredentialProvider.java` | 启动优先级：**带 stoken 的文件 > `.env`** |
| `mihoyo/dto/GameRole.java` + `mihoyo/role/GameRoleService.java` | 角色自动发现（10 分钟缓存），**替代写死的 `HOYO_ROLE_ID`** |
| `mihoyo/checkin/MiyousheCheckinGame.java` | 各游戏 `act_id` / host / `signgame` |
| `mihoyo/checkin/LunaClient.java` | `info` / `sign` / `home`，签到头 + DS1 |
| `mihoyo/checkin/CheckinService.java` | `info → sign → info` 复验、`first_bind`、验证码、retcode 翻译 |
| `agent/tool/CheckinTool.java` | Agent 工具：`checkinToday()`（无身份参数） |
| `adapter/http/AuthController.java` | 建码 / 轮询 / PNG / `GET /api/auth/roles` / `POST /api/auth/refresh` |
| `adapter/http/CheckinController.java` | `POST /api/checkin`（手动触发） |
| `scripts/login-qr.ps1` | 一条命令走完：建码 → 存 PNG → 轮询到 BOUND |

### 已实测通过（本机，2026-11-12）

| 验证项 | 结果 |
|---|---|
| `POST app/createQRLogin`（扫码系头 + DS2） | `retcode=0`，返回 `ticket` + `login-platform` url |
| `POST app/queryQRLoginStatus` | `retcode=0`；真机扫码后 `WAITING→SCANNED→BOUND` |
| 换成另一个 `device_id` 再轮询 | `-3503 当前设备或网络环境存在风险`（所以必须持久化） |
| 真机扫码拿到 stoken/ltoken/cookie_token | ✅（uid `75708228`，mid `***_mhy`） |
| `POST /api/auth/refresh`（v2 换取） | ✅ 拿到 `ltoken_v2` + `cookie_token_v2` |
| 角色自动发现 | ✅ 7 个角色（原神/星铁/绝区零/崩坏3×4） |
| `POST /api/checkin` | ✅ 星铁、绝区零真签成功；原神当日已签返回 `ALREADY` |
| 幂等 | ✅ 同日重复调用不重复签 |
| 单元测试（13 条）+ `PassportClientTest`（2 条）+ `LunaClientTest`（2 条） | 全绿 |

### 已知问题（不是代码 bug，但要知道）

1. **`character/list` 仍返回 `5003`**（新账号，v2 凭据齐全，换过 device_fp / DS / web 头都不变）。
   同一请求在旧账号上是通的，所以更可能是**账号侧的战绩可见性/风控**，不是请求构造问题。
   代码里已经能把它当普通 `MOYO_API_ERROR` 返回给模型，不会崩。
2. `5003` 没有 message，极难自行诊断。如果换账号也不通，下一步应该抓一次官方 App 的同请求对比头。
3. **旧 `.env` 的 `HOYO_ROLE_ID=153383881` 属于另一个账号**，现在已不参与查询（改自动发现）。

### 下一步

- P9-8：`@Scheduled` 每天 00:10 + 当日记录（先内存/文件，多用户再上 `checkin_records`）
- P9-9：stoken 失效时主动提示重新扫码（`/api/auth/refresh` 失败 → 发通知）
- 排查 `5003`：等风控冷却后用旧账号复测；若旧账号也变 5003，就是接口升级，需要抓包

---

## 附：待验证清单

- [x] 扫码登录拿到的 cookie 是否直接包含 `stoken` + `mid` —— **app 码的 `confirmed` 响应直接给**（`tokens[]` + `user_info`），不用 login_ticket 换
- [x] `luna/info` 带上 `stoken` 后是否返回 `retcode:0` —— **问法本身错了**：签到认 `cookie_token`+`account_id`，不看 stoken（见 §2 对照实验）
- [x] `first_bind` 为 true 时的正确交互 —— 不签，提示先去 App 绑定
- [ ] `stoken` 有效期（文档说"改密码后失效"，但没说自然过期时间）
- [ ] 签到风控触发后的冷却时间
- [ ] `5003` 的确切含义（战绩接口只给 retcode、不给 message）
- [ ] 多账号是否共享 `device_id`（FufuLauncher 每个账号一套 cookie，签到设备 id 取 `md5(cookie)`，天然各自独立）

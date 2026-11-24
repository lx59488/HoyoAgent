# HoyoAgent

> 一个跑在自己机器上的米哈游系游戏助手：QQ 里直接聊，**签到是入口，不是终点。**

HoyoAgent 用 QQ 当入口、用大模型当大脑，把「米游社账号数据 → 工具 → 对话」串起来。
现在它每天自动签到、能查签到总览；**接下来要做的是游戏知识库和日常对话**。
名字里的 Hoyo 是承诺——主线只做米哈游系游戏（原神 / 崩坏：星穹铁道 / 绝区零）。

## 现在能做什么

| 能力 | 怎么用 | 状态 |
|---|---|---|
| 绑定 / 换绑 / 解绑米游社账号 | QQ 私聊发「绑定」，扫机器人回的二维码 | ✅ |
| 每日自动签到（三个游戏） | 每天 00:10 自动跑；错过则启动 60s 后补 | ✅ |
| 签到总览（今天签没签 / 累计 / 最近 7 天；可只看一个游戏） | QQ 里问「签到了吗」或「星铁签了吗」 | ✅ |
| 多轮对话与会话记忆 | 同一会话自动带上文；超窗先把更早轮次压成摘要，最近几轮逐字保留 | ✅ |
| 签到结果推送 | webhook（企业微信 / 飞书 / Bark / 自建），默认关 | ✅ |
| Web 调试台 | <http://localhost:8080/>：聊天 + 只读查看各用户完整聊天历史与会话记忆 | ✅ |
| 游戏知识库（角色 / 武器 / 攻略问答） | — | 🚧 未立项，见 [ROADMAP P12](docs/ROADMAP.md) |
| 人设与日常对话 | — | 🚧 未开工，见 [ROADMAP P13](docs/ROADMAP.md) |

**明确不做**：社区发帖点赞、兑换码兑换、账号交易、桌面启动器；对外多租户暂不考虑
（先把自己用好，合规边界见 [mihoyo-auth.md](docs/mihoyo-auth.md) §9）。
判断标准见 [tool-ideas.md](docs/tool-ideas.md) §5。

> 带工具的链路只在 QQ 里成立：HTTP 入口没有平台身份，工具会回「没有绑定米游社账号」。
> Web 调试台用来聊天、看记忆和各用户的完整聊天历史，不用来签到。
>
> 调试台**没有鉴权**：只在本机或可信内网打开；聊天历史是明文落盘的，别把端口暴露到公网。

## 架构

```
adapter/   入站：http（调试台 / API）、qq（官方机器人 WS 网关）
   │
agent/     AI 服务、工具、会话记忆、工具调用观测
   │
mihoyo/    出站：米游社客户端（扫码 / 签到 / 战绩 / 凭据）
   │
channel/   平台无关管道：切片、会话→用户映射、出站通知
common/    统一信封、错误码、脱敏、本地存储基座
```

包边界（改代码前先确认自己在哪边）：

1. `adapter` 只表示**入站**，`mihoyo` 只表示**出站**，`channel` 只放**平台无关**的管道。
2. `mihoyo` 不依赖 `agent` / `adapter`；`agent` 可以依赖 `mihoyo`。
3. 凭据只有一个入口 `AccountService`：工具和适配器都从它拿，不自己读文件、不读 `.env`。

**未来的知识库放哪**：新开一个 `knowledge/` 域（数据源、切分、检索），
不进 `mihoyo`（那是外部客户端），也不塞进 `agent/tool`（工具只做编排）。
理由与开工前要回答的问题写在 [ROADMAP P12](docs/ROADMAP.md)。

## 快速开始

需要 JDK 25（其余靠 Maven Wrapper）。

```powershell
cp .env.example .env                 # 至少填 DS_API_KEY；米游社凭据不用手填
.\mvnw.cmd -B -ntp spring-boot:run
# 浏览器打开 http://localhost:8080/
```

QQ 入口（可选）：`.env` 里设 `QQ_BOT_ENABLED=true` + `QQ_APP_ID` / `QQ_APP_SECRET`，
把自己加成「开发体验用户」，然后私聊机器人发「绑定」。细节见 [qq-inbound.md](docs/qq-inbound.md)。

Docker：`docker compose up -d --build`。唯一需要持久化的是卷里的 `~/.hoyo`
（账号凭据、签到记录、device-id，以及 `hoyo.db` 里的记忆与聊天历史）。

存储默认 `HOYO_STORAGE_BACKEND=file`（JSON/JSONL，便于直接看文件）；`docker compose`
默认改成 `sqlite`（单文件 + WAL，单机常驻更稳）。要切回文件在 `.env` 里设
`HOYO_STORAGE_BACKEND=file`；SQLite 路径默认 `<state-dir>/hoyo.db`，可用 `HOYO_SQLITE_PATH` 覆盖。

## 文档

| 文档 | 管什么 | 不管什么 |
|---|---|---|
| [ROADMAP.md](docs/ROADMAP.md) | 路线图、进度、决策与踩坑记录——**单一事实来源** | 子系统协议细节 |
| [mihoyo-auth.md](docs/mihoyo-auth.md) | 米游社账号：扫码、token 四层、多用户凭据 | 项目整体定位 |
| [qq-inbound.md](docs/qq-inbound.md) | QQ 接入：官方机器人 WS、平台侧准入、被动回复限制 | 业务能力 |
| [tool-ideas.md](docs/tool-ideas.md) | 工具选型：还能加哪些「账号数据」工具、哪些不做 | 知识库 / 闲聊 |
| [knowledge-design.md](docs/knowledge-design.md) | 知识库：语料契约、构建/检索管线、存储选型与评测 | 何时开工（见 ROADMAP P12） |

> 子系统文档只承诺它自己那一块。想知道「这个项目要长成什么样」，看本页和
> [ROADMAP 顶部的能力地图](docs/ROADMAP.md)。

最后校准：2026-11-23（契约 C1–C5 全部落地；绑定与签到只在 QQ 里，HTTP 入口 11-22 已移除；
记忆与聊天历史支持 SQLite 后端，见 [ROADMAP P7-8](docs/ROADMAP.md)）。

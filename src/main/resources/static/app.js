"use strict";

/* ------------------------------------------------------------------ */
/* 状态                                                                */
/* ------------------------------------------------------------------ */

const SESSION_KEY = "hoyoagent.session";

function newSessionId() {
    return crypto.randomUUID
        ? crypto.randomUUID()
        : `s-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function loadSessionId() {
    let id = localStorage.getItem(SESSION_KEY);
    if (!id) {
        id = newSessionId();
        localStorage.setItem(SESSION_KEY, id);
    }
    return id;
}

const state = {
    mode: "agent",
    sessionId: loadSessionId(),
    ticket: null,
    pollTimer: null,
    tickTimer: null,
    deadline: 0,
    busy: false,
    bound: false,
    hasStoken: false,
    messages: 0
};

const GAME_NAMES = {
    hk4e_cn: "原神",
    hkrpg_cn: "崩坏：星穹铁道",
    nap_cn: "绝区零",
    bh3_cn: "崩坏3",
    bh2_cn: "崩坏学园2",
    nxx_cn: "未定事件簿"
};

const QR_LABEL = {
    CREATING: "创建中…",
    WAITING: "等待扫码",
    SCANNED: "已扫码，请在手机上确认",
    BOUND: "绑定成功",
    EXPIRED: "二维码已过期",
    FAILED: "失败"
};

const QR_CLASS = {
    CREATING: "pill-muted",
    WAITING: "pill-warn",
    SCANNED: "pill-warn",
    BOUND: "pill-ok",
    EXPIRED: "pill-err",
    FAILED: "pill-err"
};

const $ = (selector) => document.querySelector(selector);

/* ------------------------------------------------------------------ */
/* HTTP                                                                */
/* ------------------------------------------------------------------ */

async function request(path, options = {}) {
    const started = performance.now();
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), options.timeout || 150000);

    const init = {
        method: options.method || "GET",
        headers: { Accept: "application/json", ...(options.headers || {}) },
        signal: controller.signal
    };
    if (options.body !== undefined) {
        init.body = typeof options.body === "string" ? options.body : JSON.stringify(options.body);
        init.headers["Content-Type"] = "application/json";
    }

    try {
        const response = await fetch(path, init);
        const text = await response.text();
        let json = null;
        try {
            json = text ? JSON.parse(text) : null;
        } catch (ignored) {
            // 非 JSON（例如 Spring 默认错误页）就原样显示
        }
        const ms = Math.round(performance.now() - started);
        showDebug({ method: init.method, path, status: response.status, ms }, json !== null ? json : text);

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        if (json && typeof json.code === "number" && json.code !== 200) {
            throw new Error(json.message || `code=${json.code}`);
        }
        return json;
    } catch (error) {
        if (error.name === "AbortError") {
            throw new Error("请求超时");
        }
        throw error;
    } finally {
        clearTimeout(timeout);
    }
}

function showDebug(req, res) {
    $("#debug-req").textContent = JSON.stringify(req, null, 2);
    $("#debug-res").textContent = typeof res === "string" ? res : JSON.stringify(res, null, 2);
}

/* ------------------------------------------------------------------ */
/* 顶部状态                                                            */
/* ------------------------------------------------------------------ */

function setConnection(ok, text) {
    const el = $("#conn");
    el.className = "pill " + (ok ? "pill-ok" : "pill-err");
    el.textContent = text;
}

function setAccountPill(status) {
    const el = $("#account-pill");
    el.className = "pill " + (status.bound ? "pill-ok" : "pill-muted");
    el.textContent = status.bound ? `已绑定 · ${status.uid || "未知 uid"}` : "未绑定";
}

function badge(text, ok) {
    const el = document.createElement("span");
    el.className = "badge" + (ok ? " ok" : "");
    el.textContent = text;
    return el;
}

function renderAccount(status) {
    const box = $("#account-detail");
    box.classList.remove("hidden");
    box.replaceChildren();

    const dl = document.createElement("dl");
    const rows = [
        ["userId", status.userId || "—"],
        ["来源", status.source || "—"],
        ["uid", status.uid || "—"],
        ["mid", status.mid || "—"]
    ];
    rows.forEach(([key, value]) => {
        const dt = document.createElement("dt");
        dt.textContent = key;
        const dd = document.createElement("dd");
        dd.textContent = value;
        dl.append(dt, dd);
    });

    const badges = document.createElement("div");
    badges.className = "badges";
    badges.append(
        badge("stoken", status.hasStoken),
        badge("ltoken", status.hasLtoken),
        badge("ltoken_v2", status.hasLtokenV2),
        badge("cookie_token", status.hasCookieToken)
    );

    box.append(dl, badges);

    state.bound = !!status.bound;
    state.hasStoken = !!status.hasStoken;
    $("#btn-roles").disabled = !status.bound;
    $("#btn-refresh").disabled = !status.hasStoken;
}

async function loadStatus() {
    try {
        const result = await request("/api/auth/status");
        setConnection(true, "已连接");
        setAccountPill(result.data);
        renderAccount(result.data);
        loadCheckin();
    } catch (error) {
        setConnection(false, "未连接");
        $("#account-pill").className = "pill pill-muted";
        $("#account-pill").textContent = "未知状态";
        $("#account-detail").classList.add("hidden");
        $("#btn-refresh").disabled = true;
        $("#btn-roles").disabled = true;
        showNote("读取绑定状态失败：" + error.message);
    }
}

/* ------------------------------------------------------------------ */
/* 扫码绑定                                                            */
/* ------------------------------------------------------------------ */

function setQrState(name, remainingSeconds, message) {
    const pill = $("#qr-state");
    pill.className = "pill " + (QR_CLASS[name] || "pill-muted");
    pill.textContent = QR_LABEL[name] || name;

    if (typeof remainingSeconds === "number" && remainingSeconds >= 0) {
        state.deadline = Date.now() + remainingSeconds * 1000;
        updateCountdown();
    }
    if (message) {
        $("#qr-msg").textContent = message;
    }
}

function updateCountdown() {
    if (!state.deadline) {
        $("#qr-countdown").textContent = "";
        return;
    }
    const left = Math.max(0, Math.ceil((state.deadline - Date.now()) / 1000));
    const minutes = Math.floor(left / 60);
    const seconds = String(left % 60).padStart(2, "0");
    $("#qr-countdown").textContent = left > 0 ? `剩余 ${minutes}:${seconds}` : "已过期";
}

function startTick() {
    clearInterval(state.tickTimer);
    state.tickTimer = setInterval(updateCountdown, 1000);
}

function stopQr() {
    clearTimeout(state.pollTimer);
    clearInterval(state.tickTimer);
    state.pollTimer = null;
    state.tickTimer = null;
    state.ticket = null;
    state.deadline = 0;
    const countdown = $("#qr-countdown");
    if (countdown) {
        countdown.textContent = "";
    }
}

async function startQr() {
    stopQr();
    $("#qr-area").classList.remove("hidden");
    $("#qr-img").removeAttribute("src");
    $("#qr-msg").textContent = "正在创建二维码…";
    setQrState("CREATING");
    $("#btn-qr").disabled = true;

    try {
        const result = await request("/api/auth/qr", { method: "POST" });
        const data = result.data;
        state.ticket = data.ticket;
        $("#qr-img").src = `/api/auth/qr/${encodeURIComponent(data.ticket)}/image?ts=${Date.now()}`;
        $("#qr-msg").textContent = "用米游社 App 扫码并确认（3 分钟内有效）";
        setQrState(data.state || "WAITING", data.expiresInSeconds);
        startTick();
        schedulePoll(0);
    } catch (error) {
        setQrState("FAILED", 0, "创建失败：" + error.message);
    } finally {
        $("#btn-qr").disabled = false;
    }
}

function schedulePoll(delay) {
    clearTimeout(state.pollTimer);
    state.pollTimer = setTimeout(pollQr, delay);
}

async function pollQr() {
    if (!state.ticket) {
        return;
    }
    try {
        const result = await request(`/api/auth/qr/${encodeURIComponent(state.ticket)}`);
        const data = result.data;
        setQrState(data.state, data.expiresInSeconds, data.message);

        if (data.state === "BOUND") {
            stopQr();
            await loadStatus();
            await loadRoles();
            showNote(`绑定成功：uid ${data.uid || "?"}`);
            return;
        }
        if (data.state === "EXPIRED" || data.state === "FAILED") {
            stopQr();
            return;
        }
        schedulePoll(2000);
    } catch (error) {
        setQrState("FAILED", 0, "轮询失败：" + error.message);
        schedulePoll(3000);
    }
}

async function refreshV2() {
    $("#btn-refresh").disabled = true;
    try {
        const result = await request("/api/auth/refresh", { method: "POST" });
        showNote("v2 刷新：" + result.data.message);
        await loadStatus();
    } catch (error) {
        showNote("v2 刷新失败：" + error.message);
    } finally {
        $("#btn-refresh").disabled = !state.hasStoken;
    }
}

async function loadRoles() {
    const box = $("#roles-box");
    box.classList.remove("hidden");
    const list = $("#roles");
    list.replaceChildren();

    const loading = document.createElement("li");
    loading.textContent = "加载中…";
    list.append(loading);

    try {
        const result = await request("/api/auth/roles");
        const roles = result.data || [];
        list.replaceChildren();
        if (!roles.length) {
            const empty = document.createElement("li");
            empty.textContent = "当前账号没有绑定角色";
            list.append(empty);
            return;
        }
        roles.forEach((role) => {
            const item = document.createElement("li");
            const name = document.createElement("strong");
            name.textContent = GAME_NAMES[role.gameBiz] || role.gameBiz;
            const detail = document.createElement("span");
            detail.textContent = `${role.nickname || "—"} · ${role.gameUid} · Lv.${role.level}`;
            item.append(name, detail);
            list.append(item);
        });
    } catch (error) {
        list.replaceChildren();
        const failed = document.createElement("li");
        failed.textContent = "读取失败：" + error.message;
        list.append(failed);
    }
}

/* ------------------------------------------------------------------ */
/* 每日签到                                                            */
/* ------------------------------------------------------------------ */

const OUTCOME_LABEL = { DONE: "已签", PARTIAL: "部分失败", FAILED: "失败", SKIPPED: "跳过" };

const TRIGGER_LABEL = { SCHEDULED: "定时", STARTUP: "启动补签", MANUAL: "手动" };

function outcomePill(outcome) {
    switch (outcome) {
        case "DONE":
            return "pill-ok";
        case "PARTIAL":
            return "pill-warn";
        case "FAILED":
            return "pill-err";
        default:
            return "pill-muted";
    }
}

/** 后端给的是带偏移的 ISO 串（如 2026-11-14T00:10+08:00），直接切字符串，别让浏览器再按本地时区解释一次。 */
function formatScheduled(iso) {
    if (!iso) {
        return "—";
    }
    const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(iso);
    return match ? `${match[2]}-${match[3]} ${match[4]}:${match[5]}` : iso;
}

async function loadCheckin() {
    try {
        const result = await request("/api/checkin/status");
        renderCheckin(result.data);
    } catch (error) {
        $("#checkin-pill").className = "pill pill-err";
        $("#checkin-pill").textContent = "读取失败";
        $("#checkin-msg").textContent = "读取签到状态失败：" + error.message;
    }
}

function renderCheckin(status) {
    const pill = $("#checkin-pill");
    const today = status.today;

    if (!status.enabled) {
        pill.className = "pill pill-muted";
        pill.textContent = "已关闭";
    } else if (!today) {
        pill.className = "pill pill-warn";
        pill.textContent = "今天未跑";
    } else {
        pill.className = "pill " + outcomePill(today.outcome);
        pill.textContent = OUTCOME_LABEL[today.outcome] || today.outcome;
    }

    $("#checkin-when").textContent = status.enabled
        ? `下次 ${formatScheduled(status.nextRun)}`
        : "自动签到已关闭（hoyo.checkin.enabled=false）";

    const message = $("#checkin-msg");
    if (status.needsAttention) {
        message.textContent = status.attention || "需要看一眼";
        message.className = "hint warn";
    } else if (today) {
        message.textContent = `${TRIGGER_LABEL[today.trigger] || today.trigger} · ${formatScheduled(today.finishedAt)} · ${today.message}`;
        message.className = "hint";
    } else {
        message.textContent = "今天还没到点；00:10 之后会自动签，也可以现在手动跑一次。";
        message.className = "hint";
    }

    const list = $("#checkin-days");
    list.replaceChildren();
    const recent = status.recent || [];
    if (!recent.length) {
        const empty = document.createElement("li");
        empty.textContent = "还没有签到记录";
        list.append(empty);
        return;
    }
    recent.forEach((record) => {
        const item = document.createElement("li");
        const left = document.createElement("span");
        const outcome = document.createElement("span");
        outcome.className = "pill " + outcomePill(record.outcome);
        outcome.textContent = OUTCOME_LABEL[record.outcome] || record.outcome;
        left.append(document.createTextNode(`${(record.date || "").slice(5)} `), outcome);
        const right = document.createElement("span");
        right.textContent = record.message || "";
        item.append(left, right);
        list.append(item);
    });
}

async function runCheckin() {
    const button = $("#btn-checkin");
    button.disabled = true;
    button.textContent = "签到中…";
    try {
        const result = await request("/api/checkin/run", { method: "POST" });
        const record = result.data || {};
        showNote(`签到：${OUTCOME_LABEL[record.outcome] || record.outcome} — ${record.message || ""}`);
        await loadCheckin();
    } catch (error) {
        showNote("签到失败：" + error.message);
    } finally {
        button.disabled = false;
        button.textContent = "现在跑一次";
    }
}

/* ------------------------------------------------------------------ */
/* 对话                                                                */
/* ------------------------------------------------------------------ */

function scrollMessages() {
    const box = $("#messages");
    box.scrollTop = box.scrollHeight;
}

function addMessage(kind, text, tools = []) {
    const wrap = document.createElement("div");
    wrap.className = `msg msg-${kind}`;

    const bubble = document.createElement("div");
    bubble.className = "bubble";
    bubble.textContent = text;
    wrap.append(bubble);

    if (tools && tools.length) {
        const chips = document.createElement("div");
        chips.className = "chips";
        tools.forEach((tool) => {
            const chip = document.createElement("span");
            chip.className = "chip";
            chip.textContent = tool;
            chips.append(chip);
        });
        wrap.append(chips);
    }

    $("#messages").append(wrap);
    state.messages += 1;
    scrollMessages();
}

function showNote(text) {
    addMessage("note", text);
}

function setBusy(busy) {
    state.busy = busy;
    $("#btn-send").disabled = busy;

    const existing = $("#typing");
    if (busy && !existing) {
        const wrap = document.createElement("div");
        wrap.id = "typing";
        wrap.className = "msg msg-assistant";
        const bubble = document.createElement("div");
        bubble.className = "bubble";
        bubble.textContent = "思考中…";
        wrap.append(bubble);
        $("#messages").append(wrap);
        scrollMessages();
    }
    if (!busy && existing) {
        existing.remove();
    }
}

async function sendMessage(text) {
    const message = text.trim();
    if (!message || state.busy) {
        return;
    }

    addMessage("user", message);
    $("#input").value = "";
    autosize();
    setBusy(true);

    const path = state.mode === "agent" ? "/agent" : "/chat";
    const options = state.mode === "agent"
        ? { headers: { "X-Session-Id": state.sessionId } }
        : {};
    try {
        const result = await request(`${path}?q=${encodeURIComponent(message)}`, options);
        const data = result.data || {};
        addMessage("assistant", data.reply || "（空回复）", data.toolsCalled || []);
    } catch (error) {
        addMessage("error", "请求失败：" + error.message);
    } finally {
        setBusy(false);
    }
}

function autosize() {
    const input = $("#input");
    input.style.height = "auto";
    input.style.height = Math.min(input.scrollHeight, 160) + "px";
}

/* ------------------------------------------------------------------ */
/* 事件绑定                                                            */
/* ------------------------------------------------------------------ */

$("#btn-qr").addEventListener("click", startQr);
$("#btn-status").addEventListener("click", loadStatus);
$("#btn-refresh").addEventListener("click", refreshV2);
$("#btn-roles").addEventListener("click", loadRoles);
$("#btn-checkin").addEventListener("click", runCheckin);
$("#btn-new-session").addEventListener("click", () => {
    state.sessionId = newSessionId();
    localStorage.setItem(SESSION_KEY, state.sessionId);
    addMessage("note", `新会话：${state.sessionId}`);
});

document.querySelectorAll(".seg").forEach((button) => {
    button.addEventListener("click", () => {
        document.querySelectorAll(".seg").forEach((item) => item.classList.toggle("active", item === button));
        state.mode = button.dataset.mode;
        $("#input").placeholder = state.mode === "agent"
            ? "Agent 会调用工具，比如「帮我签个到」"
            : "纯模型问答，不调用任何工具";
    });
});

document.querySelectorAll("[data-prompt]").forEach((button) => {
    button.addEventListener("click", () => sendMessage(button.dataset.prompt));
});

$("#btn-clear").addEventListener("click", () => {
    $("#messages").replaceChildren();
    state.messages = 0;
});

$("#composer").addEventListener("submit", (event) => {
    event.preventDefault();
    sendMessage($("#input").value);
});

$("#input").addEventListener("input", autosize);

$("#input").addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        $("#composer").requestSubmit();
    }
});

window.addEventListener("beforeunload", stopQr);

/* ------------------------------------------------------------------ */
/* 启动                                                                */
/* ------------------------------------------------------------------ */

addMessage("note", "先绑定账号，再在右侧提问。Agent 模式会调用工具，Chat 模式只问模型；调试面板可看原始 JSON。");
loadStatus();

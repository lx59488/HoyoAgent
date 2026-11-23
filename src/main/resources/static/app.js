"use strict";

/* ------------------------------------------------------------------ */
/* 状态                                                                */
/* ------------------------------------------------------------------ */

const SESSION_KEY = "hoyoagent.session";

/** 记忆里的消息类型 → 中文标签。 */
const TYPE_LABEL = {
    USER: "用户",
    AI: "模型",
    SYSTEM: "系统",
    TOOL_EXECUTION_RESULT: "工具结果"
};

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
    busy: false
};

const $ = (selector) => document.querySelector(selector);

/* ------------------------------------------------------------------ */
/* HTTP                                                                */
/* ------------------------------------------------------------------ */

async function request(path, options = {}) {
    const started = performance.now();
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), options.timeout || 150000);
    let reached = false;

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
        reached = true;
        setConnection(true, "已连接");

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
        if (!reached) {
            setConnection(false, "未连接");
        }
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

/* ------------------------------------------------------------------ */
/* 会话记忆                                                            */
/* ------------------------------------------------------------------ */

async function loadMemory() {
    try {
        const result = await request(`/api/memory/${encodeURIComponent(state.sessionId)}`);
        renderMemory(result.data || {});
    } catch (error) {
        $("#memory-session").textContent = state.sessionId;
        $("#memory-meta").textContent = "读取失败：" + error.message;
        $("#memory-list").replaceChildren();
    }
}

function renderMemory(view) {
    $("#memory-session").textContent = view.sessionId || state.sessionId;
    const count = typeof view.messages === "number" ? view.messages : 0;
    $("#memory-meta").textContent = view.expired
        ? `${count} 条 · 已空闲超时（下一轮按新会话处理）`
        : `${count} 条 · 下一轮会原样发给模型`;

    const list = $("#memory-list");
    list.replaceChildren();
    const content = view.content || [];
    if (!content.length) {
        const empty = document.createElement("li");
        empty.className = "mem-empty";
        empty.textContent = "本会话还没有记忆";
        list.append(empty);
        return;
    }
    content.forEach((message) => {
        const item = document.createElement("li");
        item.className = "mem-item";
        const type = document.createElement("span");
        type.className = "mem-type";
        type.textContent = TYPE_LABEL[message.type] || message.type;
        const text = document.createElement("span");
        text.className = "mem-text";
        text.textContent = message.text || "";
        item.append(type, text);
        list.append(item);
    });
}

/* ------------------------------------------------------------------ */
/* 用户聊天记录（只读）                                                */
/* ------------------------------------------------------------------ */

const tx = {
    sessions: [],
    current: null,
    loading: false,
    filter: "all",
    query: ""
};

function maskId(value) {
    if (!value) {
        return "—";
    }
    return value.length <= 8 ? "***" : `${value.slice(0, 4)}***${value.slice(-4)}`;
}

function formatTime(value) {
    if (!value) {
        return "—";
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime())
        ? value
        : date.toLocaleString("zh-CN", { hour12: false });
}

async function loadTranscripts() {
    $("#tx-meta").textContent = "读取中…";
    try {
        const result = await request("/api/transcripts");
        tx.sessions = result.data || [];
        renderTranscriptList();
    } catch (error) {
        $("#tx-meta").textContent = "读取失败：" + error.message;
        $("#tx-list").replaceChildren();
    }
}

function renderTranscriptList() {
    const list = $("#tx-list");
    list.replaceChildren();

    const all = tx.sessions || [];
    const sessions = all.filter(matchesFilter);
    const users = groupByUser(sessions);
    $("#tx-meta").textContent = all.length
        ? `${users.length} 个用户 · ${sessions.length} 个会话`
            + (sessions.length === all.length ? "" : `（共 ${all.length} 个）`)
            + " · 只读，按最后聊天时间倒序"
        : "还没有记录：历史从这一版开始积累，QQ 里产生新聊天后才会出现。";

    if (!users.length) {
        const empty = document.createElement("li");
        empty.className = "tx-empty";
        empty.textContent = all.length ? "没有符合筛选的会话" : "暂无记录";
        list.append(empty);
        return;
    }

    users.forEach((user) => list.append(renderUserGroup(user)));
}

/** 类型筛选 + 掩码/原文搜索；只作用于已经取回的列表，不再发请求。 */
function matchesFilter(session) {
    if (tx.filter !== "all" && (session.kind || "other") !== tx.filter) {
        return false;
    }
    const query = tx.query.trim().toLowerCase();
    if (!query) {
        return true;
    }
    return [session.userId, session.sessionId, session.conversationId]
        .filter(Boolean)
        .flatMap((value) => [value.toLowerCase(), maskId(value).toLowerCase()])
        .some((value) => value.includes(query));
}

/** 同一个 userId 的会话归到一组；sessions 已按最后时间倒序，所以组也按最近活动倒序。 */
function groupByUser(sessions) {
    const users = new Map();
    sessions.forEach((session) => {
        const key = session.userId || "未知用户";
        if (!users.has(key)) {
            users.set(key, { userId: key, sessions: [] });
        }
        users.get(key).sessions.push(session);
    });
    return [...users.values()];
}

function renderUserGroup(user) {
    const item = document.createElement("li");
    item.className = "tx-user-group";

    const head = document.createElement("div");
    head.className = "tx-user-head";
    const id = document.createElement("span");
    id.className = "tx-user-id";
    id.textContent = maskId(user.userId);
    const count = document.createElement("span");
    count.className = "tx-user-count";
    count.textContent = `${user.sessions.length} 个会话`;
    head.append(id, count);

    const sessions = document.createElement("ul");
    sessions.className = "tx-sessions";
    user.sessions.forEach((session) => {
        const row = document.createElement("li");
        const button = document.createElement("button");
        button.type = "button";
        button.className = "tx-item";

        const top = document.createElement("span");
        top.className = "tx-item-top";
        const label = document.createElement("span");
        label.className = "tx-session-label";
        label.textContent = sessionLabel(session);
        const meta = document.createElement("span");
        meta.className = "tx-item-meta";
        meta.textContent = `${session.turns} 轮 · ${formatTime(session.lastAt)}`;
        top.append(label, meta);

        const preview = document.createElement("span");
        preview.className = "tx-item-preview";
        preview.textContent = session.preview || "（没有文本）";

        button.append(top, preview);
        button.addEventListener("click", () => openTranscript(session));
        row.append(button);
        sessions.append(row);
    });

    item.append(head, sessions);
    return item;
}

/** 只用于展示，不参与身份判断：会话类型由 transcript 记录里的 kind 决定。 */
function sessionLabel(session) {
    const kind = (session && session.kind) || "other";
    if (kind === "c2c") {
        return "私聊";
    }
    if (kind === "group") {
        const conversationId = (session && session.conversationId) || "";
        const groupId = conversationId.startsWith("group:")
            ? conversationId.slice("group:".length)
            : conversationId;
        return `群聊 ${maskId(groupId)}`;
    }
    return "会话";
}

async function openTranscript(session) {
    tx.current = {
        sessionId: session.sessionId,
        userId: session.userId,
        turns: [],
        hasMore: false
    };
    tx.loading = false;
    $("#tx-title").textContent = maskId(session.userId);
    $("#tx-sub").textContent = `${sessionLabel(session)} · 用户 ${maskId(session.userId)}`
        + ` · ${session.turns} 轮 · ${formatTime(session.lastAt)}`;
    $("#tx-body").replaceChildren();
    $("#tx-foot-note").textContent = "";
    $("#btn-tx-older").disabled = true;

    const dialog = $("#tx-dialog");
    if (typeof dialog.showModal === "function") {
        dialog.showModal();
    } else {
        dialog.setAttribute("open", "");
    }
    await loadTranscriptPage(0);
}

async function loadTranscriptPage(before) {
    if (!tx.current || tx.loading) {
        return;
    }
    tx.loading = true;
    $("#btn-tx-older").disabled = true;

    try {
        const suffix = before > 0 ? `?before=${before}&limit=100` : "?limit=100";
        const result = await request(`/api/transcripts/${encodeURIComponent(tx.current.sessionId)}${suffix}`);
        const page = result.data || {};
        const turns = page.turns || [];
        const body = $("#tx-body");
        const previousHeight = body.scrollHeight;
        const previousTop = body.scrollTop;
        const fragment = document.createDocumentFragment();
        turns.forEach((turn) => fragment.append(renderTurn(turn)));

        if (before > 0) {
            body.prepend(fragment);
            body.scrollTop = previousTop + (body.scrollHeight - previousHeight);
            tx.current.turns = [...turns, ...tx.current.turns];
        } else {
            body.append(fragment);
            body.scrollTop = body.scrollHeight;
            tx.current.turns = turns;
        }

        tx.current.hasMore = Boolean(page.hasMore);
        $("#btn-tx-older").disabled = !tx.current.hasMore;
        $("#tx-foot-note").textContent = tx.current.turns.length
            ? (tx.current.hasMore
                ? `已显示 ${tx.current.turns.length} 轮，可继续往前翻`
                : `已到最早（共 ${tx.current.turns.length} 轮）`)
            : "这个会话还没有可显示的轮次。";
    } catch (error) {
        $("#tx-foot-note").textContent = "读取失败：" + error.message;
    } finally {
        tx.loading = false;
        if (tx.current && tx.current.hasMore) {
            $("#btn-tx-older").disabled = false;
        }
    }
}

function renderTurn(turn) {
    const wrap = document.createElement("div");
    wrap.className = "tx-turn";

    const time = document.createElement("div");
    time.className = "tx-time";
    time.textContent = `#${turn.seq} · ${formatTime(turn.at)}`;
    wrap.append(time);

    wrap.append(turnLine("用户", turn.user, "tx-user"));
    const reply = turn.assistant !== null && turn.assistant !== undefined
        ? turn.assistant
        : (turn.error ? "（调用失败，没有回复）" : "（空回复）");
    wrap.append(turnLine("模型", reply, "tx-ai"));

    if (Array.isArray(turn.tools) && turn.tools.length) {
        const chips = document.createElement("div");
        chips.className = "chips";
        turn.tools.forEach((tool) => {
            const chip = document.createElement("span");
            chip.className = "chip";
            chip.textContent = tool;
            chips.append(chip);
        });
        wrap.append(chips);
    }

    if (turn.error) {
        const error = document.createElement("div");
        error.className = "tx-error";
        error.textContent = "错误：" + turn.error;
        wrap.append(error);
    }
    return wrap;
}

function turnLine(who, text, kind) {
    const line = document.createElement("div");
    line.className = `tx-msg ${kind}`;
    const label = document.createElement("span");
    label.className = "tx-who";
    label.textContent = who;
    const body = document.createElement("div");
    body.className = "tx-text";
    body.textContent = text || "";
    line.append(label, body);
    return line;
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
        if (state.mode === "agent") {
            await loadMemory();
        }
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
/* 会话操作                                                            */
/* ------------------------------------------------------------------ */

async function clearSession() {
    const button = $("#btn-clear");
    button.disabled = true;
    try {
        await request(`/api/memory/${encodeURIComponent(state.sessionId)}`, { method: "DELETE" });
        $("#messages").replaceChildren();
        showNote("已清空本会话记忆");
        await loadMemory();
    } catch (error) {
        addMessage("error", "清空失败：" + error.message);
    } finally {
        button.disabled = false;
    }
}

function newSession() {
    state.sessionId = newSessionId();
    localStorage.setItem(SESSION_KEY, state.sessionId);
    $("#messages").replaceChildren();
    showNote(`新会话：${state.sessionId}`);
    loadMemory();
}

/* ------------------------------------------------------------------ */
/* 事件绑定                                                            */
/* ------------------------------------------------------------------ */

$("#btn-memory").addEventListener("click", loadMemory);
$("#btn-transcripts").addEventListener("click", loadTranscripts);

$("#tx-filter").addEventListener("click", (event) => {
    const button = event.target.closest("button[data-kind]");
    if (!button) {
        return;
    }
    tx.filter = button.dataset.kind;
    document.querySelectorAll("#tx-filter .tx-seg").forEach((item) => {
        item.classList.toggle("is-active", item === button);
    });
    renderTranscriptList();
});
$("#tx-search").addEventListener("input", (event) => {
    tx.query = event.target.value;
    renderTranscriptList();
});
$("#btn-new-session").addEventListener("click", newSession);
$("#btn-clear").addEventListener("click", clearSession);

$("#btn-tx-close").addEventListener("click", () => $("#tx-dialog").close());
$("#btn-tx-older").addEventListener("click", () => {
    const first = tx.current && tx.current.turns[0];
    if (first) {
        loadTranscriptPage(first.seq);
    }
});
$("#tx-dialog").addEventListener("close", () => {
    tx.current = null;
});

document.querySelectorAll(".seg").forEach((button) => {
    button.addEventListener("click", () => {
        document.querySelectorAll(".seg").forEach((item) => item.classList.toggle("active", item === button));
        state.mode = button.dataset.mode;
        $("#input").placeholder = state.mode === "agent"
            ? "Agent 会调用工具（需先在 QQ 里绑定账号）"
            : "纯模型问答，不调用任何工具";
    });
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

/* ------------------------------------------------------------------ */
/* 启动                                                                */
/* ------------------------------------------------------------------ */

addMessage("note", "网页只用于聊天调试和查看记忆；绑定、签到都在 QQ 里完成。当前会话没有平台身份，Agent 的签到类工具会提示「没有绑定」。");
loadMemory();
loadTranscripts();
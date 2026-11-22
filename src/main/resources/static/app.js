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
$("#btn-new-session").addEventListener("click", newSession);
$("#btn-clear").addEventListener("click", clearSession);

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
package com.luobo.hoyoagent.common.conversation;

/**
 * 会话展示键：把 sessionId 归并成「会话类型 + 会话 id」。
 *
 * <p>只用于列表标签、筛选和归档，<b>不参与身份判断</b>。谁能用机器人、这条消息属于谁，
 * 仍然只由 {@code SessionRegistry} 和适配器决定——sessionId 前缀可以被构造，不能当权限用。
 *
 * <p>当前约定（由 QQ 适配器定义）：
 * <ul>
 *   <li>私聊：sessionId={@code qqbot:c2c:<openid>} → conversationId={@code c2c:<openid>}，kind={@code c2c}</li>
 *   <li>群聊：sessionId={@code qqbot:group:<group_openid>#<user_openid>} → conversationId={@code group:<group_openid>}，kind={@code group}</li>
 *   <li>其它（例如网页一次性会话）：conversationId=sessionId，kind={@code other}</li>
 * </ul>
 */
public final class ConversationKeys {

    public static final String KIND_C2C = "c2c";

    public static final String KIND_GROUP = "group";

    public static final String KIND_OTHER = "other";

    private static final String SESSION_PREFIX = "qqbot:";

    private ConversationKeys() {
    }

    public static String kindOf(String sessionId) {
        if (sessionId == null) {
            return KIND_OTHER;
        }
        if (sessionId.startsWith(SESSION_PREFIX + "c2c:")) {
            return KIND_C2C;
        }
        if (sessionId.startsWith(SESSION_PREFIX + "group:")) {
            return KIND_GROUP;
        }
        return KIND_OTHER;
    }

    /**
     * 会话 id 去掉「每个用户一份」的部分：私聊去掉 {@code qqbot:}，群聊还要去掉 {@code #<user_openid>}。
     */
    public static String conversationIdOf(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        if (!sessionId.startsWith(SESSION_PREFIX)) {
            return sessionId;
        }
        String rest = sessionId.substring(SESSION_PREFIX.length());
        int separator = rest.indexOf('#');
        return separator > 0 ? rest.substring(0, separator) : rest;
    }
}

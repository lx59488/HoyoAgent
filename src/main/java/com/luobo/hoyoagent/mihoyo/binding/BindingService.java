package com.luobo.hoyoagent.mihoyo.binding;

import com.luobo.hoyoagent.common.log.Mask;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.mihoyo.auth.QrImage;
import com.luobo.hoyoagent.mihoyo.auth.QrLoginService;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinRecordStore;
import com.luobo.hoyoagent.mihoyo.credential.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 绑定米游社账号的流程编排（平台无关的那一半）。
 *
 * <p>它只管三件事：<b>识别意图</b>、<b>建码（带限流）</b>、<b>保证一张码只有一个轮询者</b>。
 * 发图、发文案、等结果这些"要说人话"的部分留在 QQ 适配器里——换平台时只换那一层。
 *
 * <p>绑定是确定性动作，所以走关键词前置路由，不进模型：模型误判的代价是"该出码没出"或
 * "不该出码乱出"，而这两件事用户一眼就能看出来，没必要让它猜。
 */
@Service
public class BindingService {

    private static final Logger log = LoggerFactory.getLogger(BindingService.class);

    public enum Intent {
        /** 想绑定/换绑，但可能要先确认覆盖 */
        START,
        /** 明确同意覆盖 */
        CONFIRM_REBIND,
        /** 解绑：删掉本地凭据 */
        UNBIND,
        /** 不是绑定类消息 */
        NONE
    }

    /** 顺序有讲究：确认和解绑都含"绑"，必须排在 START 前面判断。 */
    private static final Pattern CONFIRM = Pattern.compile("确认换绑|确认更换|确认绑定");
    private static final Pattern UNBIND = Pattern.compile("解绑|取消绑定|解除绑定");
    private static final Pattern START = Pattern.compile("绑定|绑号|绑一下|换绑|重新绑|扫码");

    /** 同一用户建码的最小间隔：连点会把米游社接口打成风控。 */
    private static final Duration MIN_INTERVAL = Duration.ofSeconds(10);

    private final QrLoginService qrLogin;

    private final AccountService accounts;

    private final Map<String, Instant> lastStart = new ConcurrentHashMap<>();

    /** 一个 ticket 只允许一个轮询线程，否则同一张码会回两条"绑定成功"。 */
    private final Set<String> polling = ConcurrentHashMap.newKeySet();

    private final CheckinRecordStore records;

    public BindingService(QrLoginService qrLogin, AccountService accounts, CheckinRecordStore records) {
        this.qrLogin = qrLogin;
        this.accounts = accounts;
        this.records = records;
    }

    public Intent intentOf(String text) {
        if (text == null || text.isBlank()) {
            return Intent.NONE;
        }
        if (CONFIRM.matcher(text).find()) {
            return Intent.CONFIRM_REBIND;
        }
        if (UNBIND.matcher(text).find()) {
            return Intent.UNBIND;
        }
        if (START.matcher(text).find()) {
            return Intent.START;
        }
        return Intent.NONE;
    }

    /** 建码并渲染成 PNG。限流命中时抛 BusinessException，调用方直接把 message 回给用户。 */
    public QrTicket start(String userId) {
        Instant last = lastStart.get(userId);
        if (last != null && Instant.now().isBefore(last.plus(MIN_INTERVAL))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "刚发过一张二维码，先扫那张（3 分钟内有效）。");
        }
        QrLoginService.Start start = qrLogin.start(userId);
        lastStart.put(userId, Instant.now());
        log.info("已为 user={} 建码，ticket={}", Mask.id(userId), Mask.prefix(start.ticket(), 8));
        return new QrTicket(start.ticket(), QrImage.png(start.url()), start.expiresInSeconds());
    }

    public QrLoginService.Status poll(String userId, String ticket) {
        return qrLogin.poll(userId, ticket);
    }

    /** @return false 表示这张码已经有人在轮询了 */
    public boolean acquirePoller(String ticket) {
        return polling.add(ticket);
    }

    public void releasePoller(String ticket) {
        polling.remove(ticket);
    }

    /** 解绑：凭据 + 该用户的签到记录一起删（合规要求"删除账号必须清空数据"）。 */
    public boolean unbind(String userId) {
        boolean removed = accounts.delete(userId);
        int cleared = records.deleteAll(userId);
        log.info("解绑 user={} 凭据={} 签到记录={} 条", Mask.id(userId), removed, cleared);
        return removed;
    }
    public record QrTicket(String ticket, byte[] png, long expiresInSeconds) {
    }
}

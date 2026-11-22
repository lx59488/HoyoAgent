package com.luobo.hoyoagent.mihoyo.credential;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.common.log.Mask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 userId 解析米游社凭据——全项目唯一的入口。
 *
 * <p>解析顺序只有一条：该用户的账号文件（{@link AccountStore}）。
 * 2026-11-21 起不再有"local 用户继承 credential.properties / HOYO_COOKIE"的兜底：
 * 身份只来自平台适配器，凭据只来自账号目录，两者都没有就是"没绑定"。
 *
 * <p>本类只回答"谁 → 什么凭据"。不认识 openid、不碰 HTTP；
 * 身份从哪来由 {@code SessionRegistry} + {@code @ToolMemoryId} 决定。
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountStore store;

    /** 命中过的解析结果；"没找到"不落缓存，否则解绑后还会命中旧结果。 */
    private final Map<String, Credential> cache = new ConcurrentHashMap<>();

    public AccountService(AccountStore store) {
        this.store = store;
        log.info("账号服务就绪：store={}", store.getClass().getSimpleName());
    }

    /** 查不到返回 empty。 */
    public Optional<Credential> credentialOf(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        Credential cached = cache.get(userId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return Optional.ofNullable(resolve(userId));
    }

    /** 工具层的入口：没凭据就抛，文案是给用户直接看的。 */
    public Credential requireCredential(String userId) {
        Credential credential = credentialOf(userId).orElse(null);
        if (credential == null || !credential.hasCookie()) {
            throw new BusinessException(ErrorCode.MOYO_AUTH_EXPIRED,
                    "你还没绑定米游社账号，回复「绑定」获取二维码");
        }
        return credential;
    }

    public boolean isBound(String userId) {
        return credentialOf(userId).filter(Credential::hasCookie).isPresent();
    }

    public void save(String userId, Credential fresh) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        store.save(new Account(userId, fresh, Instant.now()));
        cache.put(userId, fresh);
        log.info("账号已保存：user={} uid={}", Mask.id(userId), fresh.uid());
    }

    public boolean delete(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        boolean removed = store.delete(userId);
        cache.remove(userId);
        if (removed) {
            log.info("账号已删除：user={}", Mask.id(userId));
        }
        return removed;
    }

    /** 定时任务用：所有已保存的账号。 */
    public List<Account> all() {
        return store.list();
    }

    /** 绑定查重：一个米游社账号只能属于一个 QQ 用户。 */
    public Optional<Account> findByUid(String uid) {
        if (uid == null || uid.isBlank()) {
            return Optional.empty();
        }
        return store.list().stream().filter(a -> uid.equals(a.uid())).findFirst();
    }

    private Credential resolve(String userId) {
        Account stored = store.find(userId).orElse(null);
        if (stored == null) {
            return null;
        }
        Credential credential = stored.credential();
        if (credential == null || !credential.hasCookie()) {
            return null;
        }
        cache.put(userId, credential);   // 并发下重复解析无害：两边得到的是同一份
        return credential;
    }

    /**
     * 绑定/换绑的唯一入口：一个米游社账号只能属于一个平台用户。
     *
     * <p>为什么 {@code synchronized}：两个用户同时扫同一个号时，"查重 + 落盘"必须是一个原子动作，
     * 否则两边都会通过查重。单进程足够，多实例要换成数据库唯一索引。
     *
     * <p>注意 {@link #save} 故意<b>不</b>做查重——它是底层写入，给刷新 v2 这类"uid 不会变"的场景用。
     */
    public synchronized void claim(String userId, Credential fresh) {
        if (fresh == null || fresh.uid() == null || fresh.uid().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "这个米游社账号没有 uid，无法绑定");
        }
        Optional<Account> owner = findByUid(fresh.uid());
        if (owner.isPresent() && !owner.get().userId().equals(userId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "这个米游社账号已经绑定了其他用户");
        }
        save(userId, fresh);
    }

}

package com.luobo.hoyoagent.mihoyo.credential;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 userId 解析米游社凭据——多用户阶段的唯一入口。
 *
 * <p>解析顺序：
 * <ol>
 *   <li>该用户的账号文件（{@link AccountStore}）；</li>
 *   <li><b>只有</b> {@code local} 用户才回落到旧 {@code credential.properties} 和 {@code HOYO_COOKIE}——
 *       这两个是单用户时代的产物，绝不能让别的 QQ 用户"继承"到你的账号。</li>
 * </ol>
 *
 * <p>本类只回答"谁 → 什么凭据"，不认识 openid、不碰 HTTP。
 * 身份从哪来由 {@code SessionRegistry} + {@code @ToolMemoryId} 决定。
 */
@Service
public class AccountService {


    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountStore store;

    private final LegacyCredentialFile legacyFile;

    private final String localUserId;

    private final Credential envCredential;

    private final Map<String, Credential> cache = new ConcurrentHashMap<>();


    public AccountService(AccountStore store,
                          LegacyCredentialFile legacyFile,
                          @Value("${hoyo.user-id:local}") String localUserId,
                          @Value("${hoyo.cookie:}") String rawCookie) {
        this.store = store;
        this.legacyFile = legacyFile;
        this.localUserId = localUserId == null || localUserId.isBlank() ? "local" : localUserId.trim();
        this.envCredential = Credential.of(rawCookie == null ? "" : rawCookie);
        log.info("账号服务就绪：local={} env={}", this.localUserId,
                present(envCredential) ? "present" : "absent");
    }

    public String localUserId() {
        return localUserId;
    }

    /** 查不到返回 empty；不缓存"没有"，否则解绑后还会命中旧结果。 */
    public Optional<Credential> find(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.computeIfAbsent(userId, this::resolve));
    }

    private Credential resolve(String userId) {
        Account stored = store.find(userId).orElse(null);
        if (stored != null && present(stored.credential())) {
            return stored.credential();
        }
        if (userId.equals(localUserId)) {
            Credential legacy = legacyFile.read().orElse(null);
            if (present(legacy)) {
                return legacy;
            }
            if (present(envCredential)) {
                return envCredential;
            }
        }
        return stored == null ? null : stored.credential();
    }

    public boolean isBound(String userId) {
        return find(userId).filter(AccountService::present).isPresent();
    }

    /** 工具层的入口：没凭据就抛，文案是给用户直接看的。 */
    public Credential require(String userId) {
        Credential credential = find(userId).orElse(null);
        if (!present(credential)) {
            throw new BusinessException(ErrorCode.MOYO_AUTH_EXPIRED,
                    "你还没绑定米游社账号，回复「绑定」获取二维码");
        }
        return credential;
    }

    public void save(String userId, Credential fresh) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        store.save(new Account(userId, fresh, Instant.now()));
        cache.put(userId, fresh);
        log.info("账号已保存：user={} uid={}", mask(userId), fresh.uid());
    }

    public boolean delete(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        boolean removed = store.delete(userId);
        cache.remove(userId);
        if (removed) {
            log.info("账号已删除：user={}", mask(userId));
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

    private static boolean present(Credential credential) {
        return credential != null
                && credential.cookieHeader() != null
                && !credential.cookieHeader().isBlank();
    }

    private static String mask(String userId) {
        if (userId == null || userId.isBlank()) {
            return "***";
        }
        return userId.length() <= 8
                ? "***"
                : userId.substring(0, 4) + "***" + userId.substring(userId.length() - 4);
    }


}

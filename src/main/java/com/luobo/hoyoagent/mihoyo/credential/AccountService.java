package com.luobo.hoyoagent.mihoyo.credential;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.common.log.Mask;
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
 * 按 userId 解析米游社凭据——全项目唯一的入口。
 *
 * <p>解析顺序：
 * <ol>
 *   <li>该用户的账号文件（{@link AccountStore}）；</li>
 *   <li><b>只有</b> {@code local} 用户才回落到旧 {@code credential.properties} 和 {@code HOYO_COOKIE}——
 *       这两个是单用户时代的产物，绝不能让别的 QQ 用户"继承"到你的账号。</li>
 * </ol>
 *
 * <p>本类只回答"谁 → 什么凭据"，外加"这份凭据从哪来"（{@link Source}，只给调试接口看）。
 * 不认识 openid、不碰 HTTP；身份从哪来由 {@code SessionRegistry} + {@code @ToolMemoryId} 决定。
 *
 * <p>2026-11-20：删掉了 {@code CredentialProvider}。它和本类是同一件事的两份实现，各缓存一份：
 * 扫码换绑写的是这里的缓存，而 {@code /api/auth/status} 读的是它启动时的快照，会报旧 uid。
 * 现在来源信息并入 {@link Source}，写路径只剩 {@link #save}/{@link #claim} 一个。
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountStore store;

    private final LegacyCredentialFile legacyFile;

    private final String localUserId;

    private final Credential envCredential;

    /** 命中过的解析结果；"没找到"不落缓存，否则解绑后还会命中旧结果。 */
    private final Map<String, Resolved> cache = new ConcurrentHashMap<>();

    public AccountService(AccountStore store,
                          LegacyCredentialFile legacyFile,
                          @Value("${hoyo.user-id:local}") String localUserId,
                          @Value("${hoyo.cookie:}") String rawCookie) {
        this.store = store;
        this.legacyFile = legacyFile;
        this.localUserId = localUserId == null || localUserId.isBlank() ? "local" : localUserId.trim();
        this.envCredential = Credential.of(rawCookie == null ? "" : rawCookie);
        log.info("账号服务就绪：local={} env={}", this.localUserId,
                envCredential.hasCookie() ? "present" : "absent");
    }

    /** 凭据是从哪儿解析出来的。调试页要能回答"为什么用的是这个号"。 */
    public enum Source {
        /** 账号目录里的 {@code .account} 文件 */
        ACCOUNT,
        /** 旧 {@code ~/.hoyo/credential.properties}（只读兜底，仅 local） */
        LEGACY,
        /** {@code .env} 的 {@code HOYO_COOKIE}（仅 local） */
        ENV,
        /** 没有 */
        NONE
    }

    public String localUserId() {
        return localUserId;
    }

    /** 查不到返回 empty。 */
    public Optional<Credential> credentialOf(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(resolveCached(userId).credential());
    }

    /** 调试用：这份凭据的来源。没绑定时返回 {@link Source#NONE}。 */
    public Source sourceOf(String userId) {
        if (userId == null || userId.isBlank()) {
            return Source.NONE;
        }
        return resolveCached(userId).source();
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
        cache.put(userId, new Resolved(fresh, Source.ACCOUNT));
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

    private Resolved resolveCached(String userId) {
        Resolved cached = cache.get(userId);
        if (cached != null) {
            return cached;
        }
        Resolved fresh = resolve(userId);
        if (fresh.credential() != null) {
            cache.put(userId, fresh);   // 并发下重复解析无害：两边得到的是同一份
        }
        return fresh;
    }

    private Resolved resolve(String userId) {
        Account stored = store.find(userId).orElse(null);
        if (stored != null && stored.credential().hasCookie()) {
            return new Resolved(stored.credential(), Source.ACCOUNT);
        }
        if (userId.equals(localUserId)) {
            Credential legacy = legacyFile.read().orElse(null);
            if (legacy != null && legacy.hasCookie()) {
                return new Resolved(legacy, Source.LEGACY);
            }
            if (envCredential.hasCookie()) {
                return new Resolved(envCredential, Source.ENV);
            }
        }
        return stored == null
                ? new Resolved(null, Source.NONE)
                : new Resolved(stored.credential(), Source.ACCOUNT);
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

    private record Resolved(Credential credential, Source source) {
    }
}

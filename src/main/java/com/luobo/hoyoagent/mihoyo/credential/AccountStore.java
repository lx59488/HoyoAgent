package com.luobo.hoyoagent.mihoyo.credential;

import java.util.List;
import java.util.Optional;

/**
 * 账号凭据的存取边界。
 *
 * <p>现在只有 {@link FileAccountStore}（每用户一个文件）一个实现；多用户 + 签到记录落库时
 * 可以再换 {@code JdbcAccountStore}，上层（{@link CredentialProvider} / 工具）不用动。
 *
 * <p>约定：<b>只存长期凭据，不存瞬时会话</b>（扫码 ticket 状态留在内存）。
 */
public interface AccountStore {

    Optional<Account> find(String userId);

    void save(Account account);

    /** @return 是否真的删掉了一个账号 */
    boolean delete(String userId);

    List<Account> list();
}

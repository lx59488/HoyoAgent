package com.luobo.hoyoagent.mihoyo.checkin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

/**
 * 签到记录的存取边界。
 *
 * <p>契约只有四个动作：按用户查、按用户列最近几条、写、按用户全删。
 * 刻意<b>不含</b>任何路径概念——实现可以是"每人一个目录、一天一个 JSON"
 * （{@link FileCheckinRecordStore}），也可以是数据库里的一张 {@code checkin_record} 表。
 *
 * <p>两条约定对所有实现都成立（换实现时要重新确认，别默认继承）：
 * <ul>
 *   <li><b>读不到就是空</b>——解析失败当"没有记录"，宁可多签一次，服务端幂等兜底；</li>
 *   <li><b>写失败不抛</b>——记录是观测，不是业务结果。</li>
 * </ul>
 * 换成数据库后第二条约要重新想：数据库连不上时是"静默丢一条记录"还是"让签到失败"，
 * 这是产品决定，不是技术细节。
 */
public interface CheckinRecordStore {


    Optional<CheckinRecord> find(String userId, String date);

    /** 该用户最近的 {@code limit} 条，按日期倒序。 */
    List<CheckinRecord> recent(String userId, int limit);

    void save(CheckinRecord record);

    /** 解绑时调用：删掉这个用户的全部历史，返回删掉的行数。 */
    int deleteAll(String userId);

    /**
     * 人类可读的存储位置，<b>只用于启动日志</b>。
     *
     * <p>为什么不把 {@code Path root()} 放进契约：数据库实现给不出路径，
     * 留着它等于逼未来的实现去编一个假路径。日志要的是"记录存哪了"，
     * 不是一个能拿来操作的文件句柄。
     */
    String location();
}

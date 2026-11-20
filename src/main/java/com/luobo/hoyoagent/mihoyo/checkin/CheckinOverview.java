package com.luobo.hoyoagent.mihoyo.checkin;

import java.util.List;
/**
 * 签到总览：只读快照，不触发任何签到请求。
 *
 * <p>和 {@link CheckinRunResult} 的区别：Summary 是"这一次签到的执行结果"，
 * Overview 是"现在是什么状态"——同一天可以生成很多次，永远不会改米游社侧的数据。
 *
 * <p>字段全是字符串/整数，不塞 JsonNode：这个对象要直接进模型的上下文。
 */
public record CheckinOverview(String message,
                              List<Game> games,
                              List<Recent> recent,
                              String today) {


    public CheckinOverview {
        games = games == null ? List.of() : List.copyOf(games);
        recent = recent == null ? List.of() : List.copyOf(recent);
    }

    /** 单个游戏的今日状态。status: SIGNED / UNSIGNED / FIRST_BIND / FAILED */
    public record Game(String gameBiz,
                       String gameName,
                       String uid,
                       String status,
                       String message,
                       int totalDays,
                       int missedEstimate) {
    }

    /** 最近一次运行的摘要，来自本地记录，不联网。 */
    public record Recent(String date,
                         String trigger,
                         String outcome,
                         String message) {
    }
}

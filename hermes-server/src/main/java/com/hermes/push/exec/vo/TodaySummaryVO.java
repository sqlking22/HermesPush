package com.hermes.push.exec.vo;

import java.util.List;

public record TodaySummaryVO(
    long todayTotal,
    long todaySuccess,
    long todayFailed,
    long todayRunning,
    List<NextTriggerVO> nextTriggers) {
}

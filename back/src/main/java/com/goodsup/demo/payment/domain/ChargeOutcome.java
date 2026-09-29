package com.goodsup.demo.payment.domain;

public enum ChargeOutcome {
    // 스케줄러 후속처리
    DONE,
    AWAITING_PG,
    RETRY_NEEDED
}

package com.goodsup.demo.payment.domain;

public enum OutboxEventStatus {
    PENDING,    // claim 대기 / 재시도 대기
    PROCESSING, // 임시점유
    PROCESSED,  // 모든 결제 최종 상태
    FAILED      // 재시도 X 최종 상태
}

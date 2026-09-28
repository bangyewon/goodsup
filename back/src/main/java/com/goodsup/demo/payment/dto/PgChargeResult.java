package com.goodsup.demo.payment.dto;

import com.goodsup.demo.payment.domain.PgChargeStatus;

public record PgChargeResult(PgChargeStatus status, String pgTransactionId, String errorMessage) {

    public static PgChargeResult success(String pgTransactionId) {
        return new PgChargeResult(PgChargeStatus.SUCCESS, pgTransactionId, null);
    }
    public static PgChargeResult pending() {
        return new PgChargeResult(PgChargeStatus.PENDING, null, null);
    }

    public static PgChargeResult failure(String errorMessage) {
        return new PgChargeResult(PgChargeStatus.FAILURE, null, errorMessage);
    }
}

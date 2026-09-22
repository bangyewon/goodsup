package com.goodsup.demo.payment.dto;

public record PgChargeResult(boolean success, String pgTransactionId, String errorMessage) {

    public static PgChargeResult success(String pgTransactionId) {
        return new PgChargeResult(true, pgTransactionId, null);
    }

    public static PgChargeResult failure(String errorMessage) {
        return new PgChargeResult(false, null, errorMessage);
    }
}

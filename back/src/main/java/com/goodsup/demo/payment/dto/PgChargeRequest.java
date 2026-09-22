package com.goodsup.demo.payment.dto;

public record PgChargeRequest(Long orderId, int amount, String idempotencyKey) {
}

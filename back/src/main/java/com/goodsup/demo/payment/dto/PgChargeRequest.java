package com.goodsup.demo.payment.dto;

import com.goodsup.demo.payment.domain.PaymentMethod;

public record PgChargeRequest(Long orderId, int amount, PaymentMethod paymentMethod, String idempotencyKey) {

    public static PgChargeRequest from(PaymentChargeSnapshot snapshot) {
        return new PgChargeRequest(
                snapshot.orderId(), snapshot.amount(), snapshot.paymentMethod(), "order-" + snapshot.orderId());
    }
}

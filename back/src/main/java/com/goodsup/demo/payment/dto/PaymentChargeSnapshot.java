package com.goodsup.demo.payment.dto;

public record PaymentChargeSnapshot(Long paymentId, Long orderId, int amount, boolean alreadyTerminal) {
}

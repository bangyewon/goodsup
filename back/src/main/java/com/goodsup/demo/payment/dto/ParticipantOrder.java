package com.goodsup.demo.payment.dto;

import com.goodsup.demo.payment.domain.PaymentMethod;

public record ParticipantOrder(Long orderId, int amount, PaymentMethod paymentMethod) {
}

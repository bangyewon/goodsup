package com.goodsup.demo.payment.dto;

import com.goodsup.demo.payment.domain.PaymentMethod;

public record PaymentChargeSnapshot(Long paymentId, Long orderId, int amount, PaymentMethod paymentMethod,
                                    boolean alreadyTerminal) {
}

package com.goodsup.demo.payment.dto.response;

import com.goodsup.demo.payment.domain.Payment;
import com.goodsup.demo.payment.domain.PaymentMethod;
import com.goodsup.demo.payment.domain.PaymentStatus;

public record PaymentResponse(
        Long id,
        Long orderId,
        int amount,
        PaymentMethod paymentMethod,
        PaymentStatus status,
        String pgTransactionId
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrders().getId(),
                payment.getAmount(),
                payment.getPaymentMethod(),
                payment.getStatus(),
                payment.getPgTransactionId()
        );
    }
}

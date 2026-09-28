package com.goodsup.demo.orders.dto.request;

import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.orders.domain.Orders;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.payment.domain.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ParticipateGoodsFundingRequest(
        @Positive(message = "인당 구매 수량은 0보다 커야 합니다.")
        int quantity,
        @NotNull(message = "결제수단은 필수입니다.")
        PaymentMethod paymentMethod
) {
    public Orders toEntity(GoodsFunding goodsFunding, User user) {
        return Orders.builder()
                .goodsFunding(goodsFunding)
                .user(user)
                .quantity(quantity)
                .paymentMethod(paymentMethod)
                .build();
    }
}

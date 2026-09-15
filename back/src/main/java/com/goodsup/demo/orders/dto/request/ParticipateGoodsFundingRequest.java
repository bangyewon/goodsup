package com.goodsup.demo.orders.dto.request;

import jakarta.validation.constraints.Positive;

public record ParticipateGoodsFundingRequest(
        @Positive(message = "인당 구매 수량은 0보다 커야 합니다.")
        int quantity
) {
}

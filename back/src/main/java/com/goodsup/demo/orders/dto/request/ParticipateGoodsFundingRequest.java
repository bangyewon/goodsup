package com.goodsup.demo.orders.dto.request;

public record ParticipateGoodsFundingRequest(
        Long goodsFundingId,
        int quantity
) {
}

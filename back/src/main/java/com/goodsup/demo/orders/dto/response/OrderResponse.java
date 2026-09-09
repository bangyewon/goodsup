package com.goodsup.demo.orders.dto.response;

import com.goodsup.demo.orders.domain.DeliveryStatus;
import com.goodsup.demo.orders.domain.Orders;

public record OrderResponse(
        Long id,
        Long goodsFundingId,
        int quantity,
        DeliveryStatus deliveryStatus
) {
    public static OrderResponse from(Orders orders) {
        return new OrderResponse(
                orders.getId(),
                orders.getGoodsFunding().getId(),
                orders.getQuantity(),
                orders.getDeliveryStatus()
        );
    }
}

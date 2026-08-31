package com.goodsup.demo.goods.dto.response;

import com.goodsup.demo.goods.domain.GoodsFunding;

import java.time.LocalDateTime;

public record ShowGoodsFundingResponse(
        String title,
        String description,
        int price,
        int targetQuantity,
        int maxQuantityPerUser,
        LocalDateTime deadlineAt
) {
    public static ShowGoodsFundingResponse from(GoodsFunding goodsFunding) {
        return new ShowGoodsFundingResponse(
                goodsFunding.getTitle(),
                goodsFunding.getDescription(),
                goodsFunding.getPrice(),
                goodsFunding.getTargetQuantity(),
                goodsFunding.getMaxQuantityPerUser(),
                goodsFunding.getDeadlineAt()
        );
    }
}

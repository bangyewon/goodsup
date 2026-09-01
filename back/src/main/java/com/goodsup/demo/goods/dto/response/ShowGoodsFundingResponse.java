package com.goodsup.demo.goods.dto.response;

import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.domain.GoodsFundingStatus;

import java.time.LocalDateTime;

public record ShowGoodsFundingResponse(
        Long id,
        String title,
        String description,
        int price,
        int targetQuantity,
        int maxQuantityPerUser,
        LocalDateTime deadlineAt,
        int currentQuantity,
        GoodsFundingStatus status
) {
    public static ShowGoodsFundingResponse from(GoodsFunding goodsFunding) {
        return new ShowGoodsFundingResponse(
                goodsFunding.getId(),
                goodsFunding.getTitle(),
                goodsFunding.getDescription(),
                goodsFunding.getPrice(),
                goodsFunding.getTargetQuantity(),
                goodsFunding.getMaxQuantityPerUser(),
                goodsFunding.getDeadlineAt(),
                goodsFunding.getCurrentQuantity(),
                goodsFunding.getStatus()
        );
    }
}

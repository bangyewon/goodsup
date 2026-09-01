package com.goodsup.demo.goods.dto.response;

import com.goodsup.demo.goods.domain.GoodsFunding;

import java.time.LocalDateTime;

public record ShowGoodsFundingListResponse(
        String title,
        String nickname,
        LocalDateTime deadlineAt
) {
    public static ShowGoodsFundingListResponse from(GoodsFunding goodsFunding) {
        return new ShowGoodsFundingListResponse(
                goodsFunding.getTitle(),
                goodsFunding.getHost().getNickname(),
                goodsFunding.getDeadlineAt()
        );
    }
}

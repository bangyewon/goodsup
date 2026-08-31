package com.goodsup.demo.goods.dto.response;

import com.goodsup.demo.goods.domain.GoodsFunding;

public record ShowGoodsFundingListResponse(
        String title,
        String nickname
) {
    public static ShowGoodsFundingListResponse from(GoodsFunding goodsFunding) {
        return new ShowGoodsFundingListResponse(
                goodsFunding.getTitle(),
                goodsFunding.getHost().getNickname()
        );
    }
}

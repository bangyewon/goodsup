package com.goodsup.demo.goods.dto.request;

import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.user.domain.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

public record RegisterGoodsFundingRequest(
        @NotBlank(message = "공고 제목은 필수입니다.")
        String title,
        String description,
        @Positive(message = "가격은 0보다 커야 합니다.")
        int price,
        @Positive(message = "목표 수량은 0보다 커야 합니다.")
        int targetQuantity,
        @Positive(message = "인당 최대 구매 수량은 0보다 커야 합니다.")
        int maxQuantityPerUser,
        @NotBlank(message = "마감일자는 필수입니다.")
        LocalDateTime deadlineAt
) {
    public GoodsFunding toEntity(User user) {
        return GoodsFunding.builder()
                .host(user)
                .title(title)
                .description(description)
                .price(price)
                .targetQuantity(targetQuantity)
                .maxQuantityPerUser(maxQuantityPerUser)
                .deadlineAt(deadlineAt)
                .build();
    }
}

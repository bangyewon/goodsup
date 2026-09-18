package com.goodsup.demo.goods.domain;

import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.user.domain.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoodsFundingTest {

    private GoodsFunding newGoodsFunding(int targetQuantity, LocalDateTime deadlineAt) {
        User host = User.builder()
                .email("host@entity-test.com")
                .password("password")
                .nickname("host")
                .build();
        return GoodsFunding.builder()
                .host(host)
                .title("굿즈펀딩")
                .description("설명")
                .price(1000)
                .targetQuantity(targetQuantity)
                .maxQuantityPerUser(targetQuantity)
                .deadlineAt(deadlineAt)
                .build();
    }

    @Test
    void FAILED로_확정된_이후에는_참여를_시도해도_수량과_상태가_바뀌지_않는다() {
        LocalDateTime deadlineAt = LocalDateTime.now().plusSeconds(1);
        GoodsFunding goodsFunding = newGoodsFunding(100, deadlineAt);
        boolean transitioned = goodsFunding.closeAsFailedIfDeadlinePassed(deadlineAt.plusSeconds(1));
        assertThat(transitioned).isTrue();
        assertThat(goodsFunding.getStatus()).isEqualTo(GoodsFundingStatus.FAILED);

        assertThatThrownBy(() -> goodsFunding.increaseQuantityAndCloseIfNeeded(100))
                .isInstanceOf(GoodsException.class);

        assertThat(goodsFunding.getStatus()).isEqualTo(GoodsFundingStatus.FAILED);
        assertThat(goodsFunding.getCurrentQuantity()).isZero();
    }
}

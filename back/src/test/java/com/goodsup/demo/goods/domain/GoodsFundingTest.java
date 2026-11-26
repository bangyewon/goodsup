package com.goodsup.demo.goods.domain;

import com.goodsup.demo.common.apiResponse.ErrorCode;
import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.user.domain.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoodsFundingTest {

    private GoodsFunding newGoodsFunding(int targetQuantity, LocalDateTime deadlineAt) {
        return newGoodsFunding(targetQuantity, targetQuantity, deadlineAt);
    }

    private GoodsFunding newGoodsFunding(int targetQuantity, int maxQuantityPerUser, LocalDateTime deadlineAt) {
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
                .maxQuantityPerUser(maxQuantityPerUser)
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

        assertThatThrownBy(() -> goodsFunding.increaseQuantityAndCloseIfNeeded(1, 0))
                .isInstanceOf(GoodsException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RECRUITING_CLOSED);

        assertThat(goodsFunding.getStatus()).isEqualTo(GoodsFundingStatus.FAILED);
        assertThat(goodsFunding.getCurrentQuantity()).isZero();
    }

    @Test
    void 기존_주문과_합쳐_인당_한도를_넘으면_거절되고_수량은_바뀌지_않는다() {
        GoodsFunding goodsFunding = newGoodsFunding(100, 5, LocalDateTime.now().plusDays(1));

        assertThatThrownBy(() -> goodsFunding.increaseQuantityAndCloseIfNeeded(2, 4))
                .isInstanceOf(GoodsException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MAX_QUANTITY_OVER);

        assertThat(goodsFunding.getCurrentQuantity()).isZero();
        assertThat(goodsFunding.getStatus()).isEqualTo(GoodsFundingStatus.RECRUITING);
    }

    @Test
    void 기존_주문과_합쳐_인당_한도와_정확히_같으면_참여된다() {
        GoodsFunding goodsFunding = newGoodsFunding(100, 5, LocalDateTime.now().plusDays(1));

        goodsFunding.increaseQuantityAndCloseIfNeeded(1, 4);

        assertThat(goodsFunding.getCurrentQuantity()).isEqualTo(1);
    }

    @Test
    void 잔여_수량보다_많이_요청하면_거절되고_수량은_바뀌지_않는다() {
        GoodsFunding goodsFunding = newGoodsFunding(3, LocalDateTime.now().plusDays(1));
        goodsFunding.increaseQuantityAndCloseIfNeeded(2, 0);

        assertThatThrownBy(() -> goodsFunding.increaseQuantityAndCloseIfNeeded(2, 0))
                .isInstanceOf(GoodsException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.QUANTITY_EXCEEDS_REMAINING);

        assertThat(goodsFunding.getCurrentQuantity()).isEqualTo(2);
        assertThat(goodsFunding.getStatus()).isEqualTo(GoodsFundingStatus.RECRUITING);
    }
}

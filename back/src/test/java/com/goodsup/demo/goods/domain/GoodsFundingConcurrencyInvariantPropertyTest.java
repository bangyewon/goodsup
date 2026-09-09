package com.goodsup.demo.goods.domain;

import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.user.domain.User;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 무작위 참여 수량 시퀀스를 GoodsFunding에 순차 적용하며, 매 단계마다
 * "참여 수량은 목표 수량을 절대 초과하지 않는다"와 "FINISHED 상태는 되돌아가지 않는다"를 검증한다.
 * ExecutorService 기반 동시성 통합 테스트({@link com.goodsup.demo.orders.service.OrderConcurrencyIntegrationTest})를
 * 대체하지 않고 보완하는 목적 — 사람이 떠올리기 어려운 수량 조합의 반례를 탐색한다.
 */
class GoodsFundingConcurrencyInvariantPropertyTest {

    private static final int TARGET_QUANTITY = 100;

    @Property
    void 참여_수량은_목표_수량을_절대_초과하지_않는다(@ForAll("participationSequences") List<Integer> quantities) {
        GoodsFunding goodsFunding = newGoodsFunding();

        for (int quantity : quantities) {
            boolean wasFinishedBefore = goodsFunding.getStatus() == GoodsFundingStatus.FINISHED;

            try {
                goodsFunding.increaseQuantityAndCloseIfNeeded(quantity);
            } catch (GoodsException e) {
                // 잔여 수량 부족에 따른 정상 거절 — 상태는 변하지 않아야 한다.
            }

            assertThat(goodsFunding.getCurrentQuantity()).isLessThanOrEqualTo(TARGET_QUANTITY);
            if (wasFinishedBefore) {
                assertThat(goodsFunding.getStatus()).isEqualTo(GoodsFundingStatus.FINISHED);
            }
        }
    }

    @Provide
    Arbitrary<List<Integer>> participationSequences() {
        return Arbitraries.integers().between(1, 15).list().ofMinSize(1).ofMaxSize(50);
    }

    private GoodsFunding newGoodsFunding() {
        User host = User.builder()
                .email("host@jqwik-test.com")
                .password("password")
                .nickname("host")
                .build();
        return GoodsFunding.builder()
                .host(host)
                .title("jqwik 테스트 굿즈펀딩")
                .description("설명")
                .price(1000)
                .targetQuantity(TARGET_QUANTITY)
                .maxQuantityPerUser(TARGET_QUANTITY)
                .deadlineAt(LocalDateTime.now().plusDays(1))
                .build();
    }
}

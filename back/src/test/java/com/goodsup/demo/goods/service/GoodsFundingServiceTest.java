package com.goodsup.demo.goods.service;

import com.goodsup.demo.common.apiResponse.ErrorCode;
import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.domain.GoodsFundingRepository;
import com.goodsup.demo.goods.dto.request.RegisterGoodsFundingRequest;
import com.goodsup.demo.goods.dto.response.ShowGoodsFundingListResponse;
import com.goodsup.demo.goods.dto.response.ShowGoodsFundingResponse;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoodsFundingServiceTest {

    @Mock
    private GoodsFundingRepository goodsFundingRepository;

    @Mock
    private UserService userService;

    @InjectMocks
    private GoodsFundingService goodsFundingService;

    private User host(String nickname) {
        return User.builder()
                .email("host@test.com")
                .password("password")
                .nickname(nickname)
                .build();
    }

    private GoodsFunding goodsFunding(User host, String title) {
        return GoodsFunding.builder()
                .host(host)
                .title(title)
                .description("설명")
                .price(10000)
                .targetQuantity(10)
                .maxQuantityPerUser(2)
                .deadlineAt(LocalDateTime.now().plusDays(1))
                .build();
    }

    private RegisterGoodsFundingRequest registerRequest() {
        return new RegisterGoodsFundingRequest(
                "제목",
                "설명",
                10000,
                10,
                2,
                LocalDateTime.now().plusDays(1)
        );
    }

    @Test
    void 존재하는_유저가_공동구매를_등록하면_생성된_id를_반환한다() {
        User host = host("host");
        when(userService.getUser(1L)).thenReturn(host);
        when(goodsFundingRepository.save(any(GoodsFunding.class))).thenAnswer(invocation -> {
            GoodsFunding saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });

        long goodsId = goodsFundingService.registerGoodsFunding(1L, registerRequest());

        assertThat(goodsId).isEqualTo(100L);
        verify(goodsFundingRepository).save(any(GoodsFunding.class));
    }

    @Test
    void 존재하지_않는_유저가_공동구매를_등록하면_예외가_발생한다() {
        when(userService.getUser(anyLong())).thenThrow(new GoodsException(ErrorCode.ENTITY_NOT_FOUND));

        assertThatThrownBy(() -> goodsFundingService.registerGoodsFunding(1L, registerRequest()))
                .isInstanceOf(GoodsException.class);

        verify(goodsFundingRepository, never()).save(any());
    }

    @Test
    void 존재하는_공동구매를_조회하면_응답을_반환한다() {
        GoodsFunding goodsFunding = goodsFunding(host("host"), "펀딩 제목");
        when(goodsFundingRepository.findById(1L)).thenReturn(Optional.of(goodsFunding));

        ShowGoodsFundingResponse response = goodsFundingService.showGoodsFunding(1L);

        assertThat(response.title()).isEqualTo("펀딩 제목");
        assertThat(response.price()).isEqualTo(10000);
    }

    @Test
    void 존재하지_않는_공동구매를_조회하면_예외가_발생한다() {
        when(goodsFundingRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goodsFundingService.showGoodsFunding(1L))
                .isInstanceOf(GoodsException.class);
    }

    @Test
    void 공동구매_목록을_페이징으로_조회한다() {
        GoodsFunding first = goodsFunding(host("host1"), "펀딩1");
        GoodsFunding second = goodsFunding(host("host2"), "펀딩2");
        Pageable pageable = PageRequest.of(0, 20);
        Page<GoodsFunding> page = new PageImpl<>(List.of(first, second), pageable, 2);
        when(goodsFundingRepository.findAllWithHost(pageable)).thenReturn(page);

        Page<ShowGoodsFundingListResponse> result = goodsFundingService.showGoodsFundingList(pageable);

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent())
                .extracting(ShowGoodsFundingListResponse::title, ShowGoodsFundingListResponse::nickname)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("펀딩1", "host1"),
                        org.assertj.core.groups.Tuple.tuple("펀딩2", "host2")
                );
    }

    @Test
    void 마감이_지난_RECRUITING_공구_id를_조회한다() {
        LocalDateTime referenceTime = LocalDateTime.now();
        when(goodsFundingRepository.findIdsByStatusAndDeadlineAtBefore(
                com.goodsup.demo.goods.domain.GoodsFundingStatus.RECRUITING, referenceTime))
                .thenReturn(List.of(1L, 2L));

        List<Long> candidateIds = goodsFundingService.findExpiredRecruitingIds(referenceTime);

        assertThat(candidateIds).containsExactly(1L, 2L);
    }

    @Test
    void 목표_미달로_마감된_공구는_락을_잡고_FAILED로_전이한다() {
        GoodsFunding goodsFunding = goodsFunding(host("host"), "펀딩");
        LocalDateTime referenceTime = goodsFunding.getDeadlineAt().plusSeconds(1);
        when(goodsFundingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(goodsFunding));

        boolean transitioned = goodsFundingService.settleAsFailed(1L, referenceTime);

        assertThat(transitioned).isTrue();
        assertThat(goodsFunding.getStatus()).isEqualTo(com.goodsup.demo.goods.domain.GoodsFundingStatus.FAILED);
    }

    @Test
    void 마감_전이면_정산해도_전이되지_않는다() {
        GoodsFunding goodsFunding = goodsFunding(host("host"), "펀딩");
        when(goodsFundingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(goodsFunding));

        boolean transitioned = goodsFundingService.settleAsFailed(1L, LocalDateTime.now());

        assertThat(transitioned).isFalse();
        assertThat(goodsFunding.getStatus()).isEqualTo(com.goodsup.demo.goods.domain.GoodsFundingStatus.RECRUITING);
    }

    @Test
    void 존재하지_않는_공구를_정산하면_예외가_발생한다() {
        when(goodsFundingRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goodsFundingService.settleAsFailed(1L, LocalDateTime.now()))
                .isInstanceOf(GoodsException.class);
    }

    @Test
    void 존재하지_않는_공구를_락_조회하면_예외가_발생한다() {
        when(goodsFundingRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goodsFundingService.getForUpdate(1L))
                .isInstanceOf(GoodsException.class);
    }
}

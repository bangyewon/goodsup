package com.goodsup.demo.orders.service;

import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.domain.GoodsFundingRepository;
import com.goodsup.demo.goods.domain.GoodsFundingStatus;
import com.goodsup.demo.orders.domain.Orders;
import com.goodsup.demo.orders.domain.OrdersRepository;
import com.goodsup.demo.orders.dto.request.ParticipateGoodsFundingRequest;
import com.goodsup.demo.orders.dto.response.OrderResponse;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private GoodsFundingRepository goodsFundingRepository;

    @Mock
    private OrdersRepository ordersRepository;

    @InjectMocks
    private OrderService orderService;

    private User user() {
        return User.builder()
                .email("user@test.com")
                .password("password")
                .nickname("user")
                .build();
    }

    private GoodsFunding goodsFunding(int targetQuantity, int maxQuantityPerUser) {
        return GoodsFunding.builder()
                .host(user())
                .title("굿즈펀딩")
                .description("설명")
                .price(10000)
                .targetQuantity(targetQuantity)
                .maxQuantityPerUser(maxQuantityPerUser)
                .deadlineAt(LocalDateTime.now().plusDays(1))
                .build();
    }

    private ParticipateGoodsFundingRequest request(Long goodsFundingId, int quantity) {
        return new ParticipateGoodsFundingRequest(goodsFundingId, quantity);
    }

    @Test
    void 존재하지_않는_유저가_참여하면_예외가_발생한다() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.participateGoodsFunding(1L, request(1L, 1)))
                .isInstanceOf(GoodsException.class);

        verify(goodsFundingRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void 존재하지_않는_공동구매에_참여하면_예외가_발생한다() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(goodsFundingRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.participateGoodsFunding(1L, request(1L, 1)))
                .isInstanceOf(GoodsException.class);
    }

    @Test
    void 모집이_종료된_공동구매에_참여하면_예외가_발생한다() {
        GoodsFunding goodsFunding = goodsFunding(10, 5);
        ReflectionTestUtils.setField(goodsFunding, "status", GoodsFundingStatus.FINISHED);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(goodsFundingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(goodsFunding));

        assertThatThrownBy(() -> orderService.participateGoodsFunding(1L, request(1L, 1)))
                .isInstanceOf(GoodsException.class);

        verify(ordersRepository, never()).save(any());
    }

    @Test
    void 마감시각이_지난_공동구매에_참여하면_예외가_발생한다() {
        GoodsFunding goodsFunding = goodsFunding(10, 5);
        ReflectionTestUtils.setField(goodsFunding, "deadlineAt", LocalDateTime.now().minusMinutes(1));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(goodsFundingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(goodsFunding));

        assertThatThrownBy(() -> orderService.participateGoodsFunding(1L, request(1L, 1)))
                .isInstanceOf(GoodsException.class);

        verify(ordersRepository, never()).save(any());
    }

    @Test
    void 일인당_최대_수량을_초과해서_참여하면_예외가_발생한다() {
        GoodsFunding goodsFunding = goodsFunding(10, 2);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(goodsFundingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(goodsFunding));

        assertThatThrownBy(() -> orderService.participateGoodsFunding(1L, request(1L, 3)))
                .isInstanceOf(GoodsException.class);

        verify(ordersRepository, never()).save(any());
    }

    @Test
    void 기존_참여_수량과_합산해서_일인당_최대_수량을_초과하면_예외가_발생한다() {
        GoodsFunding goodsFunding = goodsFunding(10, 5);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(goodsFundingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(goodsFunding));
        when(ordersRepository.sumQuantityByGoodsFundingIdAndUserId(1L, 1L)).thenReturn(4);

        assertThatThrownBy(() -> orderService.participateGoodsFunding(1L, request(1L, 2)))
                .isInstanceOf(GoodsException.class);

        verify(ordersRepository, never()).save(any());
    }

    @Test
    void 잔여_수량을_초과해서_참여하면_예외가_발생한다() {
        GoodsFunding goodsFunding = goodsFunding(10, 10);
        ReflectionTestUtils.setField(goodsFunding, "currentQuantity", 8);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(goodsFundingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(goodsFunding));

        assertThatThrownBy(() -> orderService.participateGoodsFunding(1L, request(1L, 3)))
                .isInstanceOf(GoodsException.class);

        verify(ordersRepository, never()).save(any());
    }

    @Test
    void 정상_참여하면_주문이_생성되고_수량이_증가한다() {
        GoodsFunding goodsFunding = goodsFunding(10, 5);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(goodsFundingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(goodsFunding));
        when(ordersRepository.save(any(Orders.class))).thenAnswer(invocation -> {
            Orders saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });

        OrderResponse response = orderService.participateGoodsFunding(1L, request(1L, 3));

        assertThat(response.quantity()).isEqualTo(3);
        assertThat(goodsFunding.getCurrentQuantity()).isEqualTo(3);
        assertThat(goodsFunding.getStatus()).isEqualTo(GoodsFundingStatus.RECRUITING);
    }

    @Test
    void 목표_수량을_달성하면_공동구매_상태가_FINISHED로_전이된다() {
        GoodsFunding goodsFunding = goodsFunding(10, 10);
        ReflectionTestUtils.setField(goodsFunding, "currentQuantity", 7);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(goodsFundingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(goodsFunding));
        when(ordersRepository.save(any(Orders.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.participateGoodsFunding(1L, request(1L, 3));

        assertThat(goodsFunding.getCurrentQuantity()).isEqualTo(10);
        assertThat(goodsFunding.getStatus()).isEqualTo(GoodsFundingStatus.FINISHED);
    }
}

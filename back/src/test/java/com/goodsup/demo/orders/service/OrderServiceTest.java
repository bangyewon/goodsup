package com.goodsup.demo.orders.service;

import com.goodsup.demo.common.apiResponse.ErrorCode;
import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.goods.domain.GoodsFunding;
import com.goodsup.demo.goods.service.GoodsFundingService;
import com.goodsup.demo.goods.domain.GoodsFundingStatus;
import com.goodsup.demo.orders.domain.Orders;
import com.goodsup.demo.orders.domain.OrdersRepository;
import com.goodsup.demo.orders.dto.request.ParticipateGoodsFundingRequest;
import com.goodsup.demo.payment.domain.PaymentMethod;
import com.goodsup.demo.orders.dto.response.OrderResponse;
import com.goodsup.demo.payment.service.OutboxEventService;
import com.goodsup.demo.payment.service.PaymentService;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

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
    private UserService userService;

    @Mock
    private GoodsFundingService goodsFundingService;

    @Mock
    private OrdersRepository ordersRepository;

    @Mock
    private PaymentService paymentService;

    @Mock
    private OutboxEventService outboxEventService;

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

    private ParticipateGoodsFundingRequest request(int quantity) {
        return new ParticipateGoodsFundingRequest(quantity, PaymentMethod.CARD);
    }

    @Test
    void 존재하지_않는_유저가_참여하면_예외가_발생한다() {
        when(userService.getUser(anyLong())).thenThrow(new GoodsException(ErrorCode.ENTITY_NOT_FOUND));

        assertThatThrownBy(() -> orderService.participateGoodsFunding(1L, 1L, request(1)))
                .isInstanceOf(GoodsException.class);

        verify(goodsFundingService, never()).getForUpdate(anyLong());
    }

    @Test
    void 존재하지_않는_공동구매에_참여하면_예외가_발생한다() {
        when(userService.getUser(1L)).thenReturn(user());
        when(goodsFundingService.getForUpdate(1L)).thenThrow(new GoodsException(ErrorCode.ENTITY_NOT_FOUND));

        assertThatThrownBy(() -> orderService.participateGoodsFunding(1L, 1L, request(1)))
                .isInstanceOf(GoodsException.class);
    }

    @Test
    void 모집이_종료된_공동구매에_참여하면_예외가_발생한다() {
        GoodsFunding goodsFunding = goodsFunding(10, 5);
        ReflectionTestUtils.setField(goodsFunding, "status", GoodsFundingStatus.FINISHED);
        when(userService.getUser(1L)).thenReturn(user());
        when(goodsFundingService.getForUpdate(1L)).thenReturn(goodsFunding);

        assertThatThrownBy(() -> orderService.participateGoodsFunding(1L, 1L, request(1)))
                .isInstanceOf(GoodsException.class);

        verify(ordersRepository, never()).save(any());
    }

    @Test
    void 마감시각이_지난_공동구매에_참여하면_예외가_발생한다() {
        GoodsFunding goodsFunding = goodsFunding(10, 5);
        ReflectionTestUtils.setField(goodsFunding, "deadlineAt", LocalDateTime.now().minusMinutes(1));
        when(userService.getUser(1L)).thenReturn(user());
        when(goodsFundingService.getForUpdate(1L)).thenReturn(goodsFunding);

        assertThatThrownBy(() -> orderService.participateGoodsFunding(1L, 1L, request(1)))
                .isInstanceOf(GoodsException.class);

        verify(ordersRepository, never()).save(any());
    }

    @Test
    void 일인당_최대_수량을_초과해서_참여하면_예외가_발생한다() {
        GoodsFunding goodsFunding = goodsFunding(10, 2);
        when(userService.getUser(1L)).thenReturn(user());
        when(goodsFundingService.getForUpdate(1L)).thenReturn(goodsFunding);

        assertThatThrownBy(() -> orderService.participateGoodsFunding(1L, 1L, request(3)))
                .isInstanceOf(GoodsException.class);

        verify(ordersRepository, never()).save(any());
    }

    @Test
    void 기존_참여_수량과_합산해서_일인당_최대_수량을_초과하면_예외가_발생한다() {
        GoodsFunding goodsFunding = goodsFunding(10, 5);
        when(userService.getUser(1L)).thenReturn(user());
        when(goodsFundingService.getForUpdate(1L)).thenReturn(goodsFunding);
        when(ordersRepository.sumQuantityByGoodsFundingIdAndUserId(1L, 1L)).thenReturn(4);

        assertThatThrownBy(() -> orderService.participateGoodsFunding(1L, 1L, request(2)))
                .isInstanceOf(GoodsException.class);

        verify(ordersRepository, never()).save(any());
    }

    @Test
    void 잔여_수량을_초과해서_참여하면_예외가_발생한다() {
        GoodsFunding goodsFunding = goodsFunding(10, 10);
        ReflectionTestUtils.setField(goodsFunding, "currentQuantity", 8);
        when(userService.getUser(1L)).thenReturn(user());
        when(goodsFundingService.getForUpdate(1L)).thenReturn(goodsFunding);

        assertThatThrownBy(() -> orderService.participateGoodsFunding(1L, 1L, request(3)))
                .isInstanceOf(GoodsException.class);

        verify(ordersRepository, never()).save(any());
    }

    @Test
    void 정상_참여하면_주문이_생성되고_수량이_증가한다() {
        GoodsFunding goodsFunding = goodsFunding(10, 5);
        when(userService.getUser(1L)).thenReturn(user());
        when(goodsFundingService.getForUpdate(1L)).thenReturn(goodsFunding);
        when(ordersRepository.save(any(Orders.class))).thenAnswer(invocation -> {
            Orders saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });

        OrderResponse response = orderService.participateGoodsFunding(1L, 1L, request(3));

        assertThat(response.quantity()).isEqualTo(3);
        assertThat(goodsFunding.getCurrentQuantity()).isEqualTo(3);
        assertThat(goodsFunding.getStatus()).isEqualTo(GoodsFundingStatus.RECRUITING);
    }

    @Test
    void 목표_수량을_달성하면_공동구매_상태가_FINISHED로_전이된다() {
        GoodsFunding goodsFunding = goodsFunding(10, 10);
        ReflectionTestUtils.setField(goodsFunding, "currentQuantity", 7);
        when(userService.getUser(1L)).thenReturn(user());
        when(goodsFundingService.getForUpdate(1L)).thenReturn(goodsFunding);
        when(ordersRepository.save(any(Orders.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.participateGoodsFunding(1L, 1L, request(3));

        assertThat(goodsFunding.getCurrentQuantity()).isEqualTo(10);
        assertThat(goodsFunding.getStatus()).isEqualTo(GoodsFundingStatus.FINISHED);
    }
}

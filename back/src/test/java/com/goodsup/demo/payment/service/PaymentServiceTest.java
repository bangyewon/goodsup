package com.goodsup.demo.payment.service;

import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.orders.domain.Orders;
import com.goodsup.demo.orders.domain.OrdersRepository;
import com.goodsup.demo.payment.domain.Payment;
import com.goodsup.demo.payment.domain.PaymentMethod;
import com.goodsup.demo.payment.domain.PaymentRepository;
import com.goodsup.demo.payment.domain.PaymentStatus;
import com.goodsup.demo.payment.domain.PgChargeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrdersRepository ordersRepository;

    @InjectMocks
    private PaymentService paymentService;

    private Payment payment() {
        Orders orders = Orders.builder().quantity(1).paymentMethod(PaymentMethod.CARD).build();
        ReflectionTestUtils.setField(orders, "id", 1L);
        Payment payment = Payment.builder().orders(orders).amount(10000).paymentMethod(PaymentMethod.CARD).build();
        ReflectionTestUtils.setField(payment, "id", 100L);
        return payment;
    }

    @Test
    void 존재하지_않는_주문의_결제를_조회하면_예외가_발생한다() {
        when(paymentRepository.findByOrdersId(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getByOrderId(1L))
                .isInstanceOf(GoodsException.class);
    }

    @Test
    void 웹훅으로_성공_결과를_받으면_결제가_SUCCEEDED로_전이된다() {
        Payment payment = payment();
        when(paymentRepository.findByOrdersId(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(payment));

        paymentService.applyWebhookResult(1L, PgChargeStatus.SUCCESS, "PG-TX-1");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getPgTransactionId()).isEqualTo("PG-TX-1");
    }

    @Test
    void 이미_확정된_결제에_대한_중복_웹훅은_상태를_바꾸지_않는다() {
        Payment payment = payment();
        payment.markSucceeded("PG-TX-1");
        when(paymentRepository.findByOrdersId(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(payment));

        paymentService.applyWebhookResult(1L, PgChargeStatus.FAILURE, null);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
}

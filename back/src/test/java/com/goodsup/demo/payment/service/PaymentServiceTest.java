package com.goodsup.demo.payment.service;

import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.payment.domain.Payment;
import com.goodsup.demo.payment.domain.PaymentMethod;
import com.goodsup.demo.payment.domain.PaymentRepository;
import com.goodsup.demo.payment.domain.PaymentStatus;
import com.goodsup.demo.payment.domain.PgChargeStatus;
import com.goodsup.demo.payment.dto.ParticipantOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private PaymentService paymentService;

    private Payment payment() {
        Payment payment = Payment.builder().orderId(1L).goodsFundingId(10L).amount(10000).paymentMethod(PaymentMethod.CARD).build();
        ReflectionTestUtils.setField(payment, "id", 100L);
        return payment;
    }

    @Test
    void 참여자_주문마다_REQUESTED_결제가_생성된다() {
        paymentService.createRequestedPaymentsForFunding(10L, List.of(
                new ParticipantOrder(1L, 10000, PaymentMethod.CARD),
                new ParticipantOrder(2L, 20000, PaymentMethod.CARD)));

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Payment::getOrderId, Payment::getGoodsFundingId, Payment::getStatus)
                .containsExactly(
                        tuple(1L, 10L, PaymentStatus.REQUESTED),
                        tuple(2L, 10L, PaymentStatus.REQUESTED));
    }

    @Test
    void 존재하지_않는_주문의_결제를_조회하면_예외가_발생한다() {
        when(paymentRepository.findByOrderId(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getByOrderId(1L))
                .isInstanceOf(GoodsException.class);
    }

    @Test
    void 웹훅으로_성공_결과를_받으면_결제가_SUCCEEDED로_전이된다() {
        Payment payment = payment();
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(payment));

        paymentService.applyWebhookResult(1L, PgChargeStatus.SUCCESS, "PG-TX-1");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getPgTransactionId()).isEqualTo("PG-TX-1");
    }

    @Test
    void 이미_확정된_결제에_대한_중복_웹훅은_상태를_바꾸지_않는다() {
        Payment payment = payment();
        payment.markSucceeded("PG-TX-1");
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(payment));

        paymentService.applyWebhookResult(1L, PgChargeStatus.FAILURE, null);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
}

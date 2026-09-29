package com.goodsup.demo.payment.controller;

import com.goodsup.demo.payment.domain.PgChargeStatus;
import com.goodsup.demo.payment.service.PaymentService;
import com.goodsup.demo.payment.service.WebhookSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static com.goodsup.demo.payment.WebhookTestSigner.sign;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookControllerTest {

    private static final String SECRET = "test-secret";
    private static final String BODY = "{\"orderId\":1,\"status\":\"SUCCESS\",\"pgTransactionId\":\"PG-TX-1\"}";

    @Mock
    private PaymentService paymentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PaymentWebhookSignatureFilter filter =
                new PaymentWebhookSignatureFilter(new WebhookSignatureVerifier(SECRET, 300));
        mockMvc = MockMvcBuilders.standaloneSetup(new PaymentWebhookController(paymentService))
                .addFilters(filter)
                .build();
    }

    private String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private MockHttpServletRequestBuilder webhook(String body) {
        return post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void 올바른_서명이면_결제_결과를_반영하고_200을_반환한다() throws Exception {
        String timestamp = now();

        mockMvc.perform(webhook(BODY)
                        .header("X-PG-Timestamp", timestamp)
                        .header("X-PG-Signature", sign(SECRET, timestamp, BODY.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk());

        verify(paymentService).applyWebhookResult(1L, PgChargeStatus.SUCCESS, "PG-TX-1");
    }

    @Test
    void 서명이_일치하지_않으면_403이고_결제_결과를_반영하지_않는다() throws Exception {
        String timestamp = now();

        mockMvc.perform(webhook(BODY)
                        .header("X-PG-Timestamp", timestamp)
                        .header("X-PG-Signature", sign("other-secret", timestamp, BODY.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isForbidden());

        verify(paymentService, never()).applyWebhookResult(any(), any(), any());
    }

    @Test
    void 서명_헤더가_없으면_403이다() throws Exception {
        mockMvc.perform(webhook(BODY))
                .andExpect(status().isForbidden());

        verify(paymentService, never()).applyWebhookResult(any(), any(), any());
    }

    @Test
    void 본문이_서명_이후_변조되면_403이다() throws Exception {
        String timestamp = now();
        String signature = sign(SECRET, timestamp, BODY.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(webhook(BODY.replace("SUCCESS", "FAILURE"))
                        .header("X-PG-Timestamp", timestamp)
                        .header("X-PG-Signature", signature))
                .andExpect(status().isForbidden());

        verify(paymentService, never()).applyWebhookResult(any(), any(), any());
    }

    @Test
    void 오래된_요청은_서명이_맞아도_재전송으로_보고_403이다() throws Exception {
        String old = String.valueOf(Instant.now().getEpochSecond() - 301);

        mockMvc.perform(webhook(BODY)
                        .header("X-PG-Timestamp", old)
                        .header("X-PG-Signature", sign(SECRET, old, BODY.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isForbidden());

        verify(paymentService, never()).applyWebhookResult(any(), any(), any());
    }
}

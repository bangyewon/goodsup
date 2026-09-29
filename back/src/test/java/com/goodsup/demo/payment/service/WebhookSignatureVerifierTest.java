package com.goodsup.demo.payment.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static com.goodsup.demo.payment.WebhookTestSigner.sign;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "test-secret";
    private static final byte[] BODY = "{\"orderId\":1}".getBytes(StandardCharsets.UTF_8);

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET, 300);

    private String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    @Test
    void 올바른_서명과_타임스탬프면_통과한다() throws Exception {
        String timestamp = now();

        assertThat(verifier.verify(timestamp, sign(SECRET, timestamp, BODY), BODY)).isTrue();
    }

    @Test
    void 다른_시크릿으로_만든_서명은_거부한다() throws Exception {
        String timestamp = now();

        assertThat(verifier.verify(timestamp, sign("other-secret", timestamp, BODY), BODY)).isFalse();
    }

    @Test
    void 본문이_변조되면_거부한다() throws Exception {
        String timestamp = now();
        String signature = sign(SECRET, timestamp, BODY);

        assertThat(verifier.verify(timestamp, signature, "{\"orderId\":2}".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void 허용_오차를_벗어난_타임스탬프는_서명이_맞아도_거부한다() throws Exception {
        String old = String.valueOf(Instant.now().getEpochSecond() - 301);
        String future = String.valueOf(Instant.now().getEpochSecond() + 301);

        assertThat(verifier.verify(old, sign(SECRET, old, BODY), BODY)).isFalse();
        assertThat(verifier.verify(future, sign(SECRET, future, BODY), BODY)).isFalse();
    }

    @Test
    void 헤더가_없거나_타임스탬프가_숫자가_아니면_거부한다() throws Exception {
        String timestamp = now();

        assertThat(verifier.verify(null, "sig", BODY)).isFalse();
        assertThat(verifier.verify(timestamp, null, BODY)).isFalse();
        assertThat(verifier.verify("not-a-number", "sig", BODY)).isFalse();
    }
}

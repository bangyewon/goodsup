package com.goodsup.demo.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class WebhookSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final long toleranceSeconds;

    public WebhookSignatureVerifier(
            @Value("${goodsup.payment.webhook.secret}") String secret,
            @Value("${goodsup.payment.webhook.tolerance-seconds:300}") long toleranceSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.toleranceSeconds = toleranceSeconds;
    }

    public boolean verify(String timestamp, String signature, byte[] body) {
        if (timestamp == null || signature == null) {
            return false;
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - epochSeconds) > toleranceSeconds) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(timestamp, body).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}

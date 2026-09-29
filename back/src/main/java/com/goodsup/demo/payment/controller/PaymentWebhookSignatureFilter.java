package com.goodsup.demo.payment.controller;

import com.goodsup.demo.common.apiResponse.CustomApiResponse;
import com.goodsup.demo.common.apiResponse.ErrorCode;
import com.goodsup.demo.payment.service.WebhookSignatureVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentWebhookSignatureFilter extends OncePerRequestFilter {

    static final String WEBHOOK_PATH = "/api/payments/webhook";
    static final String TIMESTAMP_HEADER = "X-PG-Timestamp";
    static final String SIGNATURE_HEADER = "X-PG-Signature";
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final WebhookSignatureVerifier signatureVerifier;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !WEBHOOK_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES || !signatureVerifier.verify(
                request.getHeader(TIMESTAMP_HEADER), request.getHeader(SIGNATURE_HEADER), body)) {
            log.warn("웹훅 서명 검증 실패: remoteAddr={}", request.getRemoteAddr());
            CustomApiResponse.errorResponse(response, ErrorCode.FORBIDDEN.getMessage(), HttpStatus.FORBIDDEN.value());
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
    }
}

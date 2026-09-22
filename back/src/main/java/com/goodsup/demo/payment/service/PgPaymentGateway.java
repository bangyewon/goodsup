package com.goodsup.demo.payment.service;

import com.goodsup.demo.payment.dto.PgChargeRequest;
import com.goodsup.demo.payment.dto.PgChargeResult;

public interface PgPaymentGateway {
    PgChargeResult charge(PgChargeRequest request);
}

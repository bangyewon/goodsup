package com.goodsup.demo.common.exception;

import com.goodsup.demo.common.apiResponse.ErrorCode;
import lombok.Getter;

@Getter
public class GoodsException extends RuntimeException{
    private final ErrorCode errorCode;

    public GoodsException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public int getHttpStatusCode() {
        return this.errorCode.getStatusCode();
    }
}

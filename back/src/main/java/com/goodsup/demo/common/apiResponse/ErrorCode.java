package com.goodsup.demo.common.apiResponse;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 글로벌 에러
    PARAMETER_INVALID("잘못된 파라미터 입니다.", HttpStatus.BAD_REQUEST),
    METHOD_INVALID("잘못된 METHOD 요청입니다.", HttpStatus.METHOD_NOT_ALLOWED),
    INTERNAL_SERVER_ERROR("서버 내부 오류입니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    ENTITY_NOT_FOUND("객체를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    ENTITY_TYPE_INVALID("유효하지 않은 엔터티 타입입니다.", HttpStatus.BAD_REQUEST),
    BAD_REQUEST("잘못된 요청입니다", HttpStatus.BAD_REQUEST),
    FORBIDDEN("권한이 없습니다.", HttpStatus.FORBIDDEN);


    private final String message;
    private final HttpStatus httpStatus;

    public int getStatusCode() {
        return httpStatus.value();
    }
}

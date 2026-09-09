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
    FORBIDDEN("권한이 없습니다.", HttpStatus.FORBIDDEN),

    // 굿즈펀딩 에러
    MAX_QUANTITY_PER_USER_EXCEEDS_TARGET("인당 최대 구매 수량은 목표 수량보다 클 수 없습니다.", HttpStatus.BAD_REQUEST),
    DEADLINE_MUST_BE_FUTURE("마감 시각은 현재 시각보다 이후여야 합니다.", HttpStatus.BAD_REQUEST),
    RECRUITING_CLOSED("모집이 끝났습니다.", HttpStatus.CONFLICT),
    MAX_QUANTITY_OVER("살 수 있는 수량을 초과했습니다.",HttpStatus.BAD_REQUEST),
    QUANTITY_EXCEEDS_REMAINING("잔여 수량이 부족합니다.", HttpStatus.CONFLICT);


    private final String message;
    private final HttpStatus httpStatus;

    public int getStatusCode() {
        return httpStatus.value();
    }
}

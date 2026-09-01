package com.goodsup.demo.common.apiResponse;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class CustomApiResponseTest {

    @Test
    void toResponseEntity는_body의_statusCode를_실제_HTTP_상태코드로_사용한다() {
        CustomApiResponse<String> response =
                CustomApiResponse.success("data", HttpStatus.CREATED.value(), "생성됐습니다.");

        ResponseEntity<CustomApiResponse<String>> entity = response.toResponseEntity();

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(entity.getBody()).isEqualTo(response);
        assertThat(entity.getBody().statusCode()).isEqualTo(HttpStatus.CREATED.value());
    }
}

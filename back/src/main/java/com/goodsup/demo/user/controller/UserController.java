package com.goodsup.demo.user.controller;

import com.goodsup.demo.common.apiResponse.CustomApiResponse;
import com.goodsup.demo.user.dto.request.RegisterUserRequest;
import com.goodsup.demo.user.dto.response.RegisterUserResponse;
import com.goodsup.demo.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<CustomApiResponse<RegisterUserResponse>> registerUser(
            @Valid @RequestBody RegisterUserRequest request) {
        RegisterUserResponse response = userService.registerUser(request);
        return CustomApiResponse.success(response, HttpStatus.CREATED.value(), "회원가입이 완료됐습니다.")
                .toResponseEntity();
    }
}

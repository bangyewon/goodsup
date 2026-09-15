package com.goodsup.demo.user.dto.response;

import com.goodsup.demo.user.domain.User;

public record RegisterUserResponse(
        Long id,
        String email,
        String nickname
) {
    public static RegisterUserResponse from(User user) {
        return new RegisterUserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname()
        );
    }
}

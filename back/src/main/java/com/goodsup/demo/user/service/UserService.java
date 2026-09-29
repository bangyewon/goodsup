package com.goodsup.demo.user.service;

import com.goodsup.demo.common.apiResponse.ErrorCode;
import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.domain.UserRepository;
import com.goodsup.demo.user.dto.request.RegisterUserRequest;
import com.goodsup.demo.user.dto.response.RegisterUserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public RegisterUserResponse registerUser(RegisterUserRequest request) {
        if (userRepository.existsByEmailAndDeletedAt(request.email(), User.NOT_DELETED)) {
            throw new GoodsException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = request.toEntity(passwordEncoder.encode(request.password()));

        try {
            User savedUser = userRepository.save(user);
            return RegisterUserResponse.from(savedUser);
        } catch (DataIntegrityViolationException e) {
            throw new GoodsException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    public User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new GoodsException(ErrorCode.ENTITY_NOT_FOUND));
    }

    public User getReference(Long userId) {
        return userRepository.getReferenceById(userId);
    }
}

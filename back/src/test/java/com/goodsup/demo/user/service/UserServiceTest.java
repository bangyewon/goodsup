package com.goodsup.demo.user.service;

import com.goodsup.demo.common.exception.GoodsException;
import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.domain.UserRepository;
import com.goodsup.demo.user.dto.request.RegisterUserRequest;
import com.goodsup.demo.user.dto.response.RegisterUserResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    private RegisterUserRequest registerRequest() {
        return new RegisterUserRequest("user@test.com", "password1234", "닉네임");
    }

    @Test
    void 정상적으로_회원가입하면_인코딩된_비밀번호로_저장되고_응답을_반환한다() {
        when(userRepository.existsByEmailAndDeletedAt("user@test.com", User.NOT_DELETED)).thenReturn(false);
        when(passwordEncoder.encode("password1234")).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 1L);
            return saved;
        });

        RegisterUserResponse response = userService.registerUser(registerRequest());

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("user@test.com");
        assertThat(response.nickname()).isEqualTo("닉네임");
        verify(userRepository).save(argThat(user -> "encodedPassword".equals(user.getPassword())));
    }

    @Test
    void 이미_가입된_이메일로_회원가입하면_예외가_발생하고_저장을_시도하지_않는다() {
        when(userRepository.existsByEmailAndDeletedAt("user@test.com", User.NOT_DELETED)).thenReturn(true);

        assertThatThrownBy(() -> userService.registerUser(registerRequest()))
                .isInstanceOf(GoodsException.class);

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void 사전_체크를_통과해도_저장_시점에_이메일이_중복되면_예외가_발생한다() {
        when(userRepository.existsByEmailAndDeletedAt("user@test.com", User.NOT_DELETED)).thenReturn(false);
        when(passwordEncoder.encode("password1234")).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> userService.registerUser(registerRequest()))
                .isInstanceOf(GoodsException.class);
    }
}

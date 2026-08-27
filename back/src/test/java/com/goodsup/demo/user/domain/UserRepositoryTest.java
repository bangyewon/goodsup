package com.goodsup.demo.user.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class UserRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired
    private UserRepository userRepository;

    @Test
    void 활성_유저끼리_이메일이_중복되면_저장에_실패한다() {
        userRepository.saveAndFlush(User.builder()
                .email("user@test.com")
                .password("password")
                .nickname("host")
                .build());

        User duplicate = User.builder()
                .email("user@test.com")
                .password("password2")
                .nickname("host2")
                .build();

        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 탈퇴한_유저의_이메일은_새_유저가_재사용할_수_있다() {
        User withdrawnUser = userRepository.saveAndFlush(User.builder()
                .email("user@test.com")
                .password("password")
                .nickname("host")
                .build());

        withdrawnUser.delete();
        userRepository.saveAndFlush(withdrawnUser);

        User newUser = User.builder()
                .email("user@test.com")
                .password("password2")
                .nickname("host2")
                .build();

        User savedUser = userRepository.saveAndFlush(newUser);

        assertThat(savedUser.getId()).isNotNull();
        assertThat(savedUser.isDeleted()).isFalse();
    }
}

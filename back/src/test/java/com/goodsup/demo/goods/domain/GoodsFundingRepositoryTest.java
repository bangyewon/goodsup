package com.goodsup.demo.goods.domain;

import com.goodsup.demo.user.domain.User;
import com.goodsup.demo.user.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class GoodsFundingRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired
    private GoodsFundingRepository goodsFundingRepository;

    @Autowired
    private UserRepository userRepository;

    private User host() {
        return userRepository.saveAndFlush(User.builder()
                .email("host@repo-test.com")
                .password("password")
                .nickname("host")
                .build());
    }

    private GoodsFunding funding(User host, LocalDateTime deadlineAt, GoodsFundingStatus status) {
        GoodsFunding goodsFunding = GoodsFunding.builder()
                .host(host)
                .title("굿즈펀딩")
                .description("설명")
                .price(1000)
                .targetQuantity(10)
                .maxQuantityPerUser(5)
                .deadlineAt(deadlineAt)
                .build();
        if (status != GoodsFundingStatus.RECRUITING) {
            ReflectionTestUtils.setField(goodsFunding, "status", status);
        }
        return goodsFundingRepository.saveAndFlush(goodsFunding);
    }

    @Test
    void 임박_범위_안이어도_RECRUITING이_아니면_조회되지_않는다() {
        User host = host();
        LocalDateTime now = LocalDateTime.now();
        funding(host, now.plusHours(1), GoodsFundingStatus.FINISHED);

        List<Long> candidateIds = goodsFundingRepository.findIdsByStatusAndDeadlineAtBetween(
                GoodsFundingStatus.RECRUITING, now, now.plusHours(24));

        assertThat(candidateIds).isEmpty();
    }

    @Test
    void RECRUITING이어도_임박_범위_밖이면_조회되지_않는다() {
        User host = host();
        LocalDateTime now = LocalDateTime.now();
        funding(host, now.plusDays(10), GoodsFundingStatus.RECRUITING);

        List<Long> candidateIds = goodsFundingRepository.findIdsByStatusAndDeadlineAtBetween(
                GoodsFundingStatus.RECRUITING, now, now.plusHours(24));

        assertThat(candidateIds).isEmpty();
    }

    @Test
    void 임박_범위_안의_RECRUITING_공구만_정확히_조회된다() {
        User host = host();
        LocalDateTime now = LocalDateTime.now();
        GoodsFunding withinRange = funding(host, now.plusHours(1), GoodsFundingStatus.RECRUITING);
        funding(host, now.plusDays(10), GoodsFundingStatus.RECRUITING);
        funding(host, now.plusHours(2), GoodsFundingStatus.FINISHED);

        List<Long> candidateIds = goodsFundingRepository.findIdsByStatusAndDeadlineAtBetween(
                GoodsFundingStatus.RECRUITING, now, now.plusHours(24));

        assertThat(candidateIds).containsExactly(withinRange.getId());
    }
}

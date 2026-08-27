package com.goodsup.demo.user.domain;

import com.goodsup.demo.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "users", uniqueConstraints = {@UniqueConstraint(columnNames = {"email", "deleted_at"}, name = "uk_user_email_deleted_at")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    private static final LocalDateTime NOT_DELETED =  LocalDateTime.MIN;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "nickname", nullable = false)
    private String nickname;

    @Column(name = "deleted_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime deletedAt;

    @Builder
    private User(String email, String password, String nickname) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.deletedAt = NOT_DELETED;
    }

    public boolean isDeleted() {
        return !NOT_DELETED.equals(deletedAt);
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }
}

package com.home.infrastructure.persistence.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(schema = "users", name = "user_account")
public class UserAccountJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(length = 320)
    private String email;

    @Column(name = "profile_image", columnDefinition = "text")
    private String profileImage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserAccountJpaEntity() {}

    public UserAccountJpaEntity(String displayName, String email, String profileImage, Instant now) {
        this.role = "USER";
        this.displayName = displayName;
        this.email = email;
        this.profileImage = profileImage;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String displayName, String email, String profileImage, Instant now) {
        this.displayName = displayName;
        this.email = email;
        this.profileImage = profileImage;
        this.updatedAt = now;
    }

    public long id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String email() {
        return email;
    }

    public String profileImage() {
        return profileImage;
    }
}

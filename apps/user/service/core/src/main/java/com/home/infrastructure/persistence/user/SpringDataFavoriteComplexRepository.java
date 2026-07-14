package com.home.infrastructure.persistence.user;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataFavoriteComplexRepository
        extends JpaRepository<FavoriteComplexJpaEntity, FavoriteComplexId> {
    @Query(value = "SELECT id FROM users.user_account WHERE id = :userId FOR UPDATE", nativeQuery = true)
    Optional<Long> lockUser(@Param("userId") long userId);

    long countByIdUserId(long userId);

    Page<FavoriteComplexJpaEntity> findByIdUserId(long userId, Pageable pageable);

    void deleteByIdUserIdAndIdComplexId(long userId, long complexId);
}

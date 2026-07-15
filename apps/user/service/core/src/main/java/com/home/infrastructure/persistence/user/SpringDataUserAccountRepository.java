package com.home.infrastructure.persistence.user;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataUserAccountRepository extends JpaRepository<UserAccountJpaEntity, Long> {}

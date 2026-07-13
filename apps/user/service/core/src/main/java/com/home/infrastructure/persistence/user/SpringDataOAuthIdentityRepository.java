package com.home.infrastructure.persistence.user;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SpringDataOAuthIdentityRepository extends JpaRepository<OAuthIdentityJpaEntity,Long>{
 Optional<OAuthIdentityJpaEntity> findByProviderAndProviderSubject(String provider,String providerSubject);
 Optional<OAuthIdentityJpaEntity> findByUserId(Long userId);
}

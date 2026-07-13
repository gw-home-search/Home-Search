package com.home.infrastructure.persistence.user;
import com.home.application.auth.port.RefreshTokenRepository;
import com.home.domain.user.token.ActiveRefreshToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
@Repository
public class JpaRefreshTokenRepository implements RefreshTokenRepository{
 private final SpringDataRefreshTokenRepository repository;public JpaRefreshTokenRepository(SpringDataRefreshTokenRepository repository){this.repository=repository;}
 @Override @Transactional public void replaceActive(ActiveRefreshToken token){repository.replaceActive(token.userId(),token.tokenHash(),token.issuedAt(),token.expiresAt());}
 @Override public Optional<ActiveRefreshToken> findActiveByHash(String hash){return repository.findActiveByHash(hash).map(entity->new ActiveRefreshToken(entity.userId(),entity.tokenHash(),entity.issuedAt(),entity.expiresAt()));}
 @Override @Transactional public boolean rotateActive(String expectedHash,ActiveRefreshToken replacement,Instant now){return repository.rotate(expectedHash,replacement.tokenHash(),now,replacement.expiresAt())==1;}
 @Override @Transactional public void revokeByHash(String hash,Instant now){repository.revoke(hash,now);}
}

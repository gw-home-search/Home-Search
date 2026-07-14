package com.home.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.favorite.port.FavoriteComplexRepository;
import com.home.domain.user.favorite.FavoriteLimitPolicy;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class UserSpringJpaModernizationTest {
    @Test
    void favoritePolicyBelongsToTheApplicationService() throws Exception {
        assertThatCode(() -> Class.forName("com.home.application.favorite.FavoriteService"))
                .doesNotThrowAnyException();
        assertThat(Arrays.stream(FavoriteComplexRepository.class.getMethods())
                        .flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .doesNotContain(FavoriteLimitPolicy.class);
    }

    @Test
    void refreshTokenCasUsesTheExplicitJdbcAdapterOnly() {
        assertThatCode(() -> Class.forName("com.home.infrastructure.persistence.user.JdbcRefreshTokenRepository"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> Class.forName("com.home.infrastructure.persistence.user.JpaRefreshTokenRepository"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() ->
                        Class.forName("com.home.infrastructure.persistence.user.SpringDataRefreshTokenRepository"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.home.infrastructure.persistence.user.RefreshTokenJpaEntity"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}

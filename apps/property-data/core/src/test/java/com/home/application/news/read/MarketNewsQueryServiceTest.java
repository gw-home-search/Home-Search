package com.home.application.news.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.home.application.read.ResourceNotFoundException;
import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsDataStatus;
import com.home.domain.news.MarketNewsScopeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketNewsQueryServiceTest {

    @Test
    @DisplayName("정상 발행이 없으면 provider 호출 없이 UNAVAILABLE 결과를 만든다")
    void returnsUnavailableWithoutPublication() {
        MarketNewsReadRepository repository = mock(MarketNewsReadRepository.class);
        when(repository.findPublished(MarketNewsScopeType.NATIONWIDE, null, MarketNewsCategory.ALL, null, 20))
                .thenReturn(Optional.empty());

        MarketNewsReadResult result =
                new MarketNewsQueryService(repository).list(MarketNewsScopeType.NATIONWIDE, null, null, null, 20);

        assertThat(result.dataStatus()).isEqualTo(MarketNewsDataStatus.UNAVAILABLE);
        assertThat(result.items()).isEmpty();
    }

    @Test
    @DisplayName("SIDO code와 opaque cursor를 검증해 snapshot reader에 전달한다")
    void validatesSidoAndDecodesCursor() {
        MarketNewsReadRepository repository = mock(MarketNewsReadRepository.class);
        MarketNewsCursor cursor = new MarketNewsCursor(UUID.fromString("d0fb824c-938e-4cc8-a674-336262ef4206"), 31);
        MarketNewsReadResult expected =
                MarketNewsReadResult.unavailable(MarketNewsScopeType.SIDO, "11", MarketNewsCategory.POLICY);
        when(repository.existsRootSidoCode("11")).thenReturn(true);
        when(repository.findPublished(MarketNewsScopeType.SIDO, "11", MarketNewsCategory.POLICY, cursor, 50))
                .thenReturn(Optional.of(expected));

        MarketNewsReadResult result = new MarketNewsQueryService(repository)
                .list(MarketNewsScopeType.SIDO, "11", MarketNewsCategory.POLICY, cursor.encode(), 50);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("잘못된 scope·cursor·limit과 없는 단지는 공개 조회 전에 거부한다")
    void rejectsInvalidQueriesAndMissingComplex() {
        MarketNewsReadRepository repository = mock(MarketNewsReadRepository.class);
        MarketNewsQueryService service = new MarketNewsQueryService(repository);

        assertThatThrownBy(() -> service.list(MarketNewsScopeType.SIDO, null, MarketNewsCategory.ALL, null, 20))
                .isInstanceOf(InvalidNewsQueryException.class);
        assertThatThrownBy(() -> service.list(MarketNewsScopeType.NATIONWIDE, "11", MarketNewsCategory.ALL, null, 20))
                .isInstanceOf(InvalidNewsQueryException.class);
        assertThatThrownBy(() -> service.list(MarketNewsScopeType.NATIONWIDE, null, MarketNewsCategory.ALL, "!", 20))
                .isInstanceOf(InvalidNewsQueryException.class);
        assertThatThrownBy(() -> service.list(MarketNewsScopeType.NATIONWIDE, null, MarketNewsCategory.ALL, null, 51))
                .isInstanceOf(InvalidNewsQueryException.class);
        when(repository.existsComplex(501L)).thenReturn(false);
        assertThatThrownBy(() -> service.complexNews(501L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("유효한 단지 뉴스는 repository의 고정 limit 5를 사용한다")
    void readsAtMostFiveComplexItems() {
        MarketNewsReadRepository repository = mock(MarketNewsReadRepository.class);
        when(repository.existsComplex(501L)).thenReturn(true);
        when(repository.findComplexNews(501L, 5)).thenReturn(List.of());

        assertThat(new MarketNewsQueryService(repository).complexNews(501L)).isEmpty();
        verify(repository).findComplexNews(501L, 5);
    }
}

package com.home.infrastructure.external.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.news.collection.NewsProviderItem;
import com.home.application.news.collection.NormalizedNewsItem;
import com.home.domain.news.NewsRejectionReason;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NaverNewsItemNormalizerTest {

    private final NaverNewsItemNormalizer normalizer = new NaverNewsItemNormalizer();

    @Test
    @DisplayName("NAVER markup과 entity를 text로 정제하고 originallink를 우선한다")
    void decodesProviderTextAndPrefersOriginalLink() {
        NewsProviderItem raw = new NewsProviderItem(
                "&lt;b&gt;서울&lt;/b&gt; 아파트 &amp; 정책",
                "https://news.example.test/original",
                "https://search.naver.example.test/link",
                "주택 &quot;공급&quot; <b>확대</b>",
                "Fri, 24 Jul 2026 15:00:00 +0900",
                1,
                1);

        NormalizedNewsItem item = normalizer.normalize(raw);

        assertThat(item.title()).isEqualTo("서울 아파트 & 정책");
        assertThat(item.description()).isEqualTo("주택 \"공급\" 확대");
        assertThat(item.publicUrl()).isEqualTo("https://news.example.test/original");
        assertThat(item.providedAt()).isEqualTo(Instant.parse("2026-07-24T06:00:00Z"));
    }

    @Test
    @DisplayName("userinfo 또는 HTTP(S)가 아닌 공개 URL은 거부한다")
    void rejectsUnsafeProviderUrl() {
        assertThat(normalizer
                        .tryNormalize(new NewsProviderItem(
                                "아파트 정책",
                                "https://user:password@news.example.test/article",
                                "javascript:alert(1)",
                                "부동산 정책",
                                "Fri, 24 Jul 2026 15:00:00 +0900",
                                1,
                                1))
                        .rejectionReason())
                .isEqualTo(NewsRejectionReason.INVALID_URL);
    }

    @Test
    @DisplayName("누락·초과 제목과 잘못된 제공 시각은 각각 재현 가능한 rejection reason을 남긴다")
    void explainsRequiredFieldAndDateRejections() {
        assertThat(normalizer
                        .tryNormalize(new NewsProviderItem(
                                " ",
                                "https://news.example.test/article",
                                null,
                                "부동산 정책",
                                "Fri, 24 Jul 2026 15:00:00 +0900",
                                1,
                                1))
                        .rejectionReason())
                .isEqualTo(NewsRejectionReason.MISSING_REQUIRED_FIELD);
        assertThat(normalizer
                        .tryNormalize(new NewsProviderItem(
                                "아파트 정책", "https://news.example.test/article", null, "부동산 정책", "not-a-date", 1, 1))
                        .rejectionReason())
                .isEqualTo(NewsRejectionReason.INVALID_PROVIDED_AT);
        assertThat(normalizer
                        .tryNormalize(new NewsProviderItem(
                                "아파트 정책", "https://news.example.test/article", null, "부동산 정책", null, 1, 1))
                        .rejectionReason())
                .isEqualTo(NewsRejectionReason.INVALID_PROVIDED_AT);
    }

    @Test
    @DisplayName("numeric entity를 decode하고 dedupe hash만 canonical URL로 계산한다")
    void decodesNumericEntitiesAndCanonicalizesIdentityOnly() {
        NormalizedNewsItem item = normalizer.normalize(new NewsProviderItem(
                "아파트 &#xAC70;&#47000; &#38; 가격",
                "HTTPS://NEWS.EXAMPLE.TEST:443/article/?tracking=1#fragment",
                null,
                "주택 매매",
                "Fri, 24 Jul 2026 15:00:00 +0900",
                1,
                1));

        assertThat(item.title()).isEqualTo("아파트 거래 & 가격");
        assertThat(item.publicUrl()).isEqualTo("HTTPS://NEWS.EXAMPLE.TEST:443/article/?tracking=1#fragment");
        assertThat(item.canonicalUrlHash()).hasSize(64);
    }
}

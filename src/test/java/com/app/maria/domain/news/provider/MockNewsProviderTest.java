package com.app.maria.domain.news.provider;

import static org.assertj.core.api.Assertions.*;

import com.app.maria.domain.news.service.NewsThemeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

class MockNewsProviderTest {
    @Test
    void selfAuthoredBodiesContainEveryFactAndRiskAndHaveUniqueStableIds() throws Exception {
        var provider =
                new MockNewsProvider(
                        new ObjectMapper().registerModule(new JavaTimeModule()),
                        new NewsThemeService(null));
        assertThat(provider.articles()).hasSize(19);
        assertThat(provider.articles()).extracting(a -> a.id()).doesNotHaveDuplicates();
        provider.articles()
                .forEach(
                        a -> {
                            assertThat(a.source()).isNotBlank();
                            // 원문 링크는 없거나 https 주소만 허용한다
                            if (a.url() != null) assertThat(a.url()).startsWith("https://");
                            a.facts().forEach(f -> assertThat(a.body()).contains(f));
                            a.risks().forEach(r -> assertThat(a.body()).contains(r));
                        });
    }
}

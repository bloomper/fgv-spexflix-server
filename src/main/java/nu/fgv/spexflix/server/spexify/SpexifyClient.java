/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nu.fgv.spexflix.server.spexify;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexflix.server.config.CacheConfig;
import nu.fgv.spexflix.server.config.SpexflixProperties;
import nu.fgv.spexflix.server.config.SpexregisterClientConfig;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;

/**
 * Spexify's GraphQL API, as the {@code SPEXFLIX} role on {@code fgv-spexify} allows: the published tracks of a spex.
 * Without a configured URL, or when Spexify does not answer, there are none.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class SpexifyClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final String TRACKS = """
            query SpexTracks($spexIds: [ID!]!) {
                spexTracks(spexIds: $spexIds) { id title discNumber trackNumber albumTitle }
            }""";

    private final @Nullable HttpSyncGraphQlClient graphQlClient;

    public SpexifyClient(final SpexflixProperties properties, final OAuth2AuthorizedClientManager spexregisterAuthorizedClientManager) {
        final String url = properties.spexify().graphqlUrl();

        if (StringUtils.hasText(url)) {
            final RestClient restClient = RestClient.builder()
                    .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(HttpClientSettings.defaults()
                            .withConnectTimeout(TIMEOUT)
                            .withReadTimeout(TIMEOUT)))
                    .requestInterceptor(new OAuth2ClientHttpRequestInterceptor(spexregisterAuthorizedClientManager))
                    .defaultRequest(request -> request.attributes(clientRegistrationId(SpexregisterClientConfig.REGISTRATION_ID)))
                    .build();

            this.graphQlClient = HttpSyncGraphQlClient.builder(restClient).url(url).build();
        } else {
            this.graphQlClient = null;
        }
    }

    @Cacheable(cacheNames = CacheConfig.SPEXIFY_TRACKS)
    public List<Track> tracks(final List<UUID> spexIds) {
        if (graphQlClient == null) {
            return List.of();
        }
        try {
            final ClientGraphQlResponse response = graphQlClient.document(TRACKS).variable("spexIds", spexIds).executeSync();

            if (!response.getErrors().isEmpty() || !response.isValid()) {
                log.debug("Spexify answered with errors: {}", response.getErrors());
                return List.of();
            }
            return response.field("spexTracks").toEntityList(Track.class).stream().filter(Objects::nonNull).toList();
        } catch (final RuntimeException e) {
            log.debug("Spexify is unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * A published track in Spexify.
     */
    public record Track(String id, String title, int discNumber, int trackNumber, String albumTitle) {
    }
}

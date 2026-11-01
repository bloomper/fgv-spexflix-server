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

package nu.fgv.spexflix.server.config;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Keycloak admin client ({@code fgv-admin}), used only by the sample data import, which creates users.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Configuration(proxyBeanMethods = false)
public class KeycloakConfig {

    @Bean(destroyMethod = "close")
    public Keycloak keycloakAdmin(final SpexflixProperties properties) {
        final SpexflixProperties.Keycloak keycloak = properties.keycloak();

        return KeycloakBuilder.builder()
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .serverUrl(keycloak.url())
                .realm(keycloak.realm())
                .clientId(keycloak.adminClientId())
                .clientSecret(keycloak.adminClientSecret())
                .build();
    }
}

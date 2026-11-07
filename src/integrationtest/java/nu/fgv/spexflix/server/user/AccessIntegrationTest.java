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

package nu.fgv.spexflix.server.user;

import nu.fgv.spexflix.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may use the API at all: a token from {@code fgv-spexflix} with {@code USER}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class AccessIntegrationTest extends AbstractIntegrationTest {

    @Test
    void anIdTokenIsRefusedInPlaceOfAnAccessToken() {
        final int status = http().post().uri("/api/graphql")
                .header(HttpHeaders.AUTHORIZATION, idToken(EDITOR))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"query\": \"{ me { username } }\"}")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(401);
    }

    @Test
    void meListsTheCallersRoles() {
        graphQl(EDITOR)
                .document("{ me { username roles spexifyUser } }")
                .execute()
                .path("me.username").entity(String.class).isEqualTo(EDITOR)
                .path("me.roles").entityList(String.class).contains("USER", "EDITOR")
                .path("me.spexifyUser").entity(Boolean.class).isEqualTo(false);
    }

    @Test
    void meSaysWhetherTheCallerAlsoUsesSpexify() {
        graphQl(BOTH)
                .document("{ me { spexifyUser } }")
                .execute()
                .path("me.spexifyUser").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void withoutUserTheCatalogueIsForbidden() {
        expectError(graphQl(NO_ACCESS).document("{ videos { id } }").execute(), "FORBIDDEN");
    }

    @Test
    void withoutATokenTheCatalogueIsUnauthorized() {
        expectError(graphQl(null).document("{ videos { id } }").execute(), "UNAUTHORIZED");
    }

    @Test
    void withoutATokenPostersAreUnauthorized() {
        final HttpStatusCode status = http().get()
                .uri("/api/videos/{id}/poster", 1)
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void viewersCannotCreateVideos() {
        expectError(graphQl(VIEWER)
                .document("mutation { videoCreate(input: {spexIds: [\"00000000-0000-7000-8000-000000000001\"]}) { id } }")
                .execute(), "FORBIDDEN");
    }

    @Test
    void onlyAdminsChangeSettings() {
        final String update = "mutation { settingsUpdate(input: {maxUploadGb: 40, keepOriginals: true}) { maxUploadGb } }";

        expectError(graphQl(EDITOR).document(update).execute(), "FORBIDDEN");
        graphQl(ADMIN)
                .document(update)
                .execute()
                .path("settingsUpdate.maxUploadGb").entity(Integer.class).isEqualTo(40);
    }

    @Test
    void fieldsResolvedInParallelRecordAFirstTimeCallerOnce() {
        // Each field records the caller; in the first request they race to insert the same user
        graphQl(VIEWER).document("{ continueWatching { id } watchList { id } }").execute().errors().verify();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app_user").query(Long.class).single()).isEqualTo(1L);
    }
}

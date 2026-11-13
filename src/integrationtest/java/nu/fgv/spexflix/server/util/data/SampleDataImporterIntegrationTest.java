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

package nu.fgv.spexflix.server.util.data;

import nu.fgv.spexflix.server.AbstractIntegrationTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sample data is built on Spexipedia's spex and Spexregister's sample users, who get roles here. The only user of
 * Spexflix's own is {@code no-access@fgv.nu}. The worker is off, so the queued jobs stay queued.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "spexflix.worker.enabled=false")
class SampleDataImporterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SampleDataImporter importer;

    private static void spexipediaWithSpex() {
        SPEXIPEDIA.stubFor(post(urlEqualTo("/api/graphql")).withRequestBody(containing("AllSpex"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"spexAll": [
                            {"id": "00000000-0000-7000-8000-000000000001", "year": "1975", "title": "Nobel", "revival": false, "retired": false, "parent": null, "poster": null, "category": {"id": "00000000-0000-7000-8000-000000000001", "name": "Chalmersspexet"}},
                            {"id": "00000000-0000-7000-8000-000000000002", "year": "2024", "title": "Cleopatra", "revival": false, "retired": false, "parent": null, "poster": null, "category": {"id": "00000000-0000-7000-8000-000000000001", "name": "Chalmersspexet"}}
                        ]}}""")));
    }

    @Test
    @Order(1)
    void refusesWithoutSpexregistersSampleUsers() {
        spexipediaWithSpex();

        assertThatThrownBy(() -> importer.importSampleData()).hasMessageContaining("Spexregister's sample user");
    }

    @Test
    @Order(2)
    void buildsVideosOnSpexipediasSpexAndGivesItsUsersRoles() throws Exception {
        createSpexregisterUser(SampleDataImporter.ADMIN);
        createSpexregisterUser(SampleDataImporter.EDITOR);
        createSpexregisterUser(SampleDataImporter.USER);
        spexipediaWithSpex();

        importer.importSampleData();

        assertThat(spexflixRoles(SampleDataImporter.ADMIN)).containsExactly("ADMIN", "EDITOR", "USER");
        assertThat(spexflixRoles(SampleDataImporter.EDITOR)).containsExactly("EDITOR", "USER");
        assertThat(spexflixRoles(SampleDataImporter.USER)).containsExactly("USER");
        assertThat(spexflixRoles(SampleDataImporter.NO_ACCESS)).isEmpty();

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM video").query(Long.class).single()).isEqualTo(15);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM video WHERE trashed_at IS NOT NULL").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM processing_job WHERE status = 'QUEUED'").query(Long.class).single()).isEqualTo(15);
        // Chapters link to Spexify's track for Nobel, which the stand-in for Spexify has
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM chapter WHERE spexify_track_id = '00000000-0000-7000-8000-000000000011'").query(Long.class).single()).isPositive();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM subtitle").query(Long.class).single()).isPositive();
        // The user's "continue watching" (one started, one finished), plus views to the end by every sample user
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM watch_progress p JOIN app_user u ON u.id = p.user_id WHERE u.username = ? AND p.finished = 0""")
                .param(SampleDataImporter.USER).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM watch_progress p JOIN app_user u ON u.id = p.user_id WHERE u.username = ? AND p.finished = 1""")
                .param(SampleDataImporter.USER).query(Long.class).single()).isGreaterThan(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM video_rating").query(Long.class).single()).isPositive();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM playlist_item").query(Long.class).single()).isPositive();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM audit_entry").query(Long.class).single()).isEqualTo(8);
    }
}

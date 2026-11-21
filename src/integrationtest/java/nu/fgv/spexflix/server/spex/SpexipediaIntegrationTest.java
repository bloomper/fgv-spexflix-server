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

package nu.fgv.spexflix.server.spex;

import nu.fgv.spexflix.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spexflix and Spexipedia: what Spexipedia's service account reads with {@code SPEXIPEDIA}, its notifications and the
 * change feed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class SpexipediaIntegrationTest extends AbstractIntegrationTest {

    private static final String SPEXIPEDIA_SERVICE = "fgv-spexipedia-service";
    private static final String VIDEOS = "query($spex: [ID!]!) { spexipediaVideos(spexIds: $spex) { id title spexId } }";

    @TempDir
    private Path folder;
    @Autowired
    private SpexipediaSync sync;

    @Test
    void spexipediaReadsThePublishedVideosOfSpex() throws Exception {
        final String published = readyVideo(folder, true);

        readyVideo(folder, false);
        graphQlWithToken(serviceToken(SPEXIPEDIA_SERVICE)).document(VIDEOS).variable("spex", List.of(SPEX_ID)).execute()
                .path("spexipediaVideos[*].id").entityList(String.class).containsExactly(published)
                .path("spexipediaVideos[0].spexId").entity(String.class).isEqualTo(SPEX_ID);
    }

    @Test
    void onlySpexipediaReadsItsLinks() {
        expectError(graphQl(EDITOR).document(VIDEOS).variable("spex", List.of(SPEX_ID)).execute(), "FORBIDDEN");
        expectError(graphQlWithToken(spexifyServiceToken()).document(VIDEOS).variable("spex", List.of(SPEX_ID)).execute(), "FORBIDDEN");
    }

    @Test
    void spexipediaNotifiesWithItsServiceAccountOnly() {
        notify(serviceToken(SPEXIPEDIA_SERVICE)).expectStatus().isAccepted();
        notify(token(ADMIN)).expectStatus().isForbidden();
        notify(null).expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec notify(final String token) {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + localPort).build()
                .post().uri("/api/sync/notify")
                .headers(headers -> {
                    if (token != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, token);
                    }
                })
                .exchange();
    }

    @Test
    void theFeedKeepsTheCopiesCurrentAndMovesMergedReferences() throws Exception {
        final String videoId = readyVideo(folder, false);
        final UUID spex = UUID.fromString(SPEX_ID);
        final UUID category = UUID.fromString("00000000-0000-7000-8000-000000000001");
        final UUID other = UUID.fromString("00000000-0000-7000-8000-000000000005");

        sync.applyAll(List.of(
                new FeedChange("1", "SPEX_CATEGORY", category, "UPSERT", null, new FeedChange.SpexCategory(category, "Chalmersspexet!"), null),
                new FeedChange("2", "SPEX", spex, "UPSERT", null, null, new FeedChange.Spex(spex, "2025", "Kleopatra", category, null, false))));

        graphQl(EDITOR).document("query($id: ID!) { video(id: $id) { spex { title year categoryName } } }")
                .variable("id", videoId).execute()
                .path("video.spex[0].title").entity(String.class).isEqualTo("Kleopatra")
                .path("video.spex[0].year").entity(Integer.class).isEqualTo(2025)
                .path("video.spex[0].categoryName").entity(String.class).isEqualTo("Chalmersspexet!");

        sync.applyAll(List.of(new FeedChange("3", "SPEX", spex, "MERGE", other, null, null)));
        assertThat(jdbcClient.sql("SELECT spex_id FROM video_spex").query(String.class).single()).isEqualTo(other.toString());

        // An video about both spex names the surviving one once, and the positions close up
        final UUID third = UUID.fromString("00000000-0000-7000-8000-000000000006");
        final UUID duplicate = UUID.fromString("00000000-0000-7000-8000-000000000007");

        jdbcClient.sql("""
                        INSERT INTO video_spex (video_id, position, spex_id, title)
                        SELECT video_id, position + 1, :duplicate, 'Dubblett' FROM video_spex WHERE spex_id = :other""")
                .param("duplicate", duplicate.toString()).param("other", other.toString()).update();
        jdbcClient.sql("""
                        INSERT INTO video_spex (video_id, position, spex_id, title)
                        SELECT video_id, position + 2, :third, 'Tredje' FROM video_spex WHERE spex_id = :other""")
                .param("third", third.toString()).param("other", other.toString()).update();
        sync.applyAll(List.of(new FeedChange("9", "SPEX", duplicate, "MERGE", other, null, null)));
        assertThat(jdbcClient.sql("SELECT spex_id FROM video_spex ORDER BY position").query(String.class).list())
                .containsExactly(other.toString(), third.toString());
        assertThat(jdbcClient.sql("SELECT MAX(position) - MIN(position) FROM video_spex").query(Integer.class).single()).isEqualTo(1);
    }
}

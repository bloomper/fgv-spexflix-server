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

package nu.fgv.spexflix.server.imports;

import nu.fgv.spexflix.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.graphql.test.tester.GraphQlTester;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import API (spec 5.8): videos of a kind by alias, queued for processing, in collections, unpublished,
 * repeatable by source key, and only for IMPORT.
 */
class ImportIntegrationTest extends AbstractIntegrationTest {

    private static final String MIGRATION = "fgv-content-migration";
    private static final String BATCH = """
            mutation($input: ImportBatchInput!, $dryRun: Boolean!) { importBatch(input: $input, dryRun: $dryRun) {
                created updated unchanged kept failed items { kind sourceKey id outcome message warnings }
            } }""";

    @TempDir
    private Path folder;

    private Map<String, String> upload(final Path file) throws Exception {
        final byte[] bytes = Files.readAllBytes(file);
        final String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        http().put().uri("/api/import/files/" + sha).header("Authorization", serviceToken(MIGRATION)).body(bytes).retrieve().toBodilessEntity();
        return Map.of("sha256", sha, "name", "2024_Cleopatra_akt1.mp4");
    }

    private GraphQlTester.Response batch(final Map<String, Object> input, final boolean dryRun) {
        return graphQlWithToken(serviceToken(MIGRATION)).document(BATCH).variable("input", input).variable("dryRun", dryRun).execute();
    }

    @Test
    void onlyImportReachesTheImportApi() {
        expectError(graphQl(ADMIN).document(BATCH).variable("input", Map.of()).variable("dryRun", false).execute(), "FORBIDDEN");
    }

    @Test
    void videosArriveUnpublishedAndQueuedInACollection() throws Exception {
        final Map<String, String> file = upload(video(folder, 1));
        final Map<String, Object> input = Map.of(
                "videos", List.of(Map.of("sourceKey", "test:video/1", "spexIds", List.of(SPEX_ID), "kind", "busk", "title", "Cleopatra, akt 1",
                        "file", file, "chapters", List.of(Map.of("startSeconds", 0, "title", "Ouvertyr")))),
                "collections", List.of(Map.of("sourceKey", "test:collection", "title", "Cleopatra 2024", "videoSourceKeys", List.of("test:video/1"))));

        batch(input, true).path("importBatch.created").entity(Integer.class).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM video").query(Integer.class).single()).isZero();

        final String video = batch(input, false).path("importBatch.created").entity(Integer.class).isEqualTo(2)
                .path("importBatch.items[0].id").entity(String.class).get();

        graphQl(EDITOR).document("query($id: ID!) { video(id: $id) { published kind { nameSv } chapters { title } } }")
                .variable("id", video).execute()
                .path("video.published").entity(Boolean.class).isEqualTo(false)
                .path("video.kind.nameSv").entity(String.class).isEqualTo("Buskföreställning")
                .path("video.chapters[0].title").entity(String.class).isEqualTo("Ouvertyr");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM processing_job").query(Integer.class).single()).isEqualTo(1);

        // The original was moved out of the store; the same record again needs no file
        batch(input, false).path("importBatch.unchanged").entity(Integer.class).isEqualTo(2);
        graphQlWithToken(serviceToken(MIGRATION)).document("mutation { importForget(sourcePrefix: \"test:\") }").execute()
                .path("importForget").entity(Integer.class).isEqualTo(2);
    }
}

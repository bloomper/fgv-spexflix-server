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

package nu.fgv.spexflix.server.admin;

import nu.fgv.spexflix.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What editors and admins see of the processing queue, the storage and the settings, and the trash.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class AdminIntegrationTest extends AbstractIntegrationTest {

    @TempDir
    private Path folder;

    @Test
    void theQueueShowsTheProcessedJob() throws Exception {
        final String videoId = readyVideo(folder, false);

        graphQl(EDITOR).document("{ processingQueue { videoId status progress } }").execute()
                .path("processingQueue[0].videoId").entity(String.class).isEqualTo(videoId)
                .path("processingQueue[0].status").entity(String.class).isEqualTo("DONE")
                .path("processingQueue[0].progress").entity(Integer.class).isEqualTo(100);
        expectError(graphQl(VIEWER).document("{ processingQueue { id } }").execute(), "FORBIDDEN");
    }

    @Test
    void adminsSeeTheStorageAndChangeTheSettings() throws Exception {
        readyVideo(folder, false);

        graphQl(ADMIN).document("{ storage { originalsBytes renditionsBytes freeBytes videoCount } }").execute()
                .path("storage.videoCount").entity(Integer.class).isEqualTo(1)
                .path("storage.originalsBytes").entity(Double.class).satisfies(bytes -> assertThat(bytes).isPositive());
        expectError(graphQl(EDITOR).document("{ storage { videoCount } }").execute(), "FORBIDDEN");

        graphQl(ADMIN).document("mutation { settingsUpdate(input: {welcomeSv: \"Hej\", maxUploadGb: 80, keepOriginals: false}) { maxUploadGb } }")
                .execute().path("settingsUpdate.maxUploadGb").entity(Integer.class).isEqualTo(80);
        graphQl(VIEWER).document("{ settings { welcomeSv keepOriginals } }").execute()
                .path("settings.welcomeSv").entity(String.class).isEqualTo("Hej")
                .path("settings.keepOriginals").entity(Boolean.class).isEqualTo(false);
    }

    @Test
    void anAdminEmptiesTheTrashAndTheFilesGo() throws Exception {
        final String videoId = readyVideo(folder, false);

        graphQl(EDITOR).document("mutation($id: ID!) { videoTrash(id: $id) { id } }").variable("id", videoId).execute().errors().verify();
        graphQl(EDITOR).document("{ trash { id } }").execute().path("trash[0].id").entity(String.class).isEqualTo(videoId);
        expectError(graphQl(EDITOR).document("mutation { trashEmpty }").execute(), "FORBIDDEN");
        graphQl(ADMIN).document("mutation { trashEmpty }").execute().errors().verify();

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM video").query(Long.class).single()).isZero();
        try (var files = java.nio.file.Files.list(MEDIA.resolve("originals"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void editorsTakeThePosterFromTheVideoAndAdminsReprocessIt() throws Exception {
        final String videoId = readyVideo(folder, true);

        graphQl(EDITOR).document("mutation($id: ID!) { videoPosterFrame(id: $id, seconds: 2.5) { posterSeconds } }").variable("id", videoId)
                .execute().path("videoPosterFrame.posterSeconds").entity(Double.class).isEqualTo(2.5);
        expectError(graphQl(EDITOR).document("mutation($id: ID!) { videoReprocess(id: $id) { id } }").variable("id", videoId).execute(), "FORBIDDEN");
        graphQl(ADMIN).document("mutation($id: ID!) { videoReprocess(id: $id) { status job { status } } }").variable("id", videoId).execute()
                .path("videoReprocess.status").entity(String.class).isEqualTo("READY");
    }
}

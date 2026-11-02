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

import nu.fgv.spexflix.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The links between Spexflix's chapters and Spexify's tracks, both ways: editors pick tracks read from Spexify, and
 * Spexify's service account reads the links with the {@code SPEXIFY} role.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class SpexifyLinksIntegrationTest extends AbstractIntegrationTest {

    private static final String CHAPTERS = """
            mutation($id: ID!, $chapters: [ChapterInput!]!) { chaptersSet(videoId: $id, chapters: $chapters) { chapters { title spexifyTrackId } } }""";

    @TempDir
    private Path folder;

    @Test
    void chaptersLinkToSpexifysTracksAndSpexifyReadsTheLinks() throws Exception {
        final String videoId = readyVideo(folder, true);

        graphQl(EDITOR)
                .document("query($id: ID!) { spexifyTracks(videoId: $id) { id title albumTitle } }")
                .variable("id", videoId)
                .execute()
                .path("spexifyTracks[0].id").entity(String.class).isEqualTo("00000000-0000-7000-8000-000000000011")
                .path("spexifyTracks[0].albumTitle").entity(String.class).isEqualTo("Cleopatra");
        graphQl(EDITOR)
                .document(CHAPTERS)
                .variables(Map.of("id", videoId, "chapters", List.of(
                        Map.of("startSeconds", 0, "title", "Ouvertyr", "spexifyTrackId", "00000000-0000-7000-8000-000000000011", "spexifyTrackTitle", "Ouvertyr"),
                        Map.of("startSeconds", 5, "title", "Finalen"))))
                .execute()
                .path("chaptersSet.chapters[0].spexifyTrackId").entity(String.class).isEqualTo("00000000-0000-7000-8000-000000000011");

        graphQlWithToken(spexifyServiceToken())
                .document("{ chapterLinks(trackIds: [\"00000000-0000-7000-8000-000000000011\"]) { trackId videoId chapterTitle startSeconds } }")
                .execute()
                .path("chapterLinks[0].videoId").entity(String.class).isEqualTo(videoId)
                .path("chapterLinks[0].chapterTitle").entity(String.class).isEqualTo("Ouvertyr")
                .path("chapterLinks[0].startSeconds").entity(Integer.class).isEqualTo(0);

        graphQl(EDITOR).document("mutation($id: ID!) { videoPublish(id: $id, published: false) { id } }").variable("id", videoId).execute();
        graphQlWithToken(spexifyServiceToken())
                .document("{ chapterLinks(trackIds: [\"00000000-0000-7000-8000-000000000011\"]) { videoId } }")
                .execute()
                .path("chapterLinks").entityList(Object.class).hasSize(0);
    }

    @Test
    void viewersCannotListSpexifysTracks() throws Exception {
        final String videoId = graphQl(EDITOR)
                .document("mutation { videoCreate(input: {spexIds: [\"00000000-0000-7000-8000-000000000001\"], kindId: \"00000000-0000-7000-8000-00000000b002\"}) { id } }")
                .execute()
                .path("videoCreate.id").entity(String.class).get();

        expectError(graphQl(VIEWER).document("query($id: ID!) { spexifyTracks(videoId: $id) { id } }").variable("id", videoId).execute(),
                "FORBIDDEN");
    }
}

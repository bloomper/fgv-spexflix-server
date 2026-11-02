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
package nu.fgv.spexflix.server.video;

import nu.fgv.spexflix.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Collections of videos, and what plays after a video: the next in a collection, else the next of the same spex.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class CollectionIntegrationTest extends AbstractIntegrationTest {

    @TempDir
    private Path folder;

    @Test
    void anEditorCollectsVideosThatViewersPlayInOrder() throws Exception {
        final String first = readyVideo(folder, true);
        final String second = readyVideo(folder, true);
        final String unpublished = readyVideo(folder, false);

        // Without a collection, the next video is the next of the same spex
        graphQl(VIEWER).document("query($id: ID!) { video(id: $id) { next { id } } }").variable("id", first).execute()
                .path("video.next.id").entity(String.class).isEqualTo(second);

        expectError(graphQl(VIEWER).document("mutation($ids: [ID!]!) { collectionCreate(input: {title: \"Alla år\", videoIds: $ids}) { id } }")
                .variable("ids", List.of(second, first)).execute(), "FORBIDDEN");

        final String collectionId = graphQl(EDITOR)
                .document("mutation($ids: [ID!]!) { collectionCreate(input: {title: \"Alla år\", description: \"Varje år\", videoIds: $ids}) { id videos { id } } }")
                .variable("ids", List.of(second, unpublished, first))
                .execute()
                .path("collectionCreate.videos[*].id").entityList(String.class).containsExactly(second, unpublished, first)
                .path("collectionCreate.id").entity(String.class).get();

        // Viewers see only the listed videos, in the collection's order, and play them in that order
        graphQl(VIEWER)
                .document("query($c: ID!, $v: ID!) { collections { id } collection(id: $c) { title videos { id } } video(id: $v) { collections { title } next { id } } }")
                .variables(Map.of("c", collectionId, "v", second))
                .execute()
                .path("collections[*].id").entityList(String.class).containsExactly(collectionId)
                .path("collection.title").entity(String.class).isEqualTo("Alla år")
                .path("collection.videos[*].id").entityList(String.class).containsExactly(second, first)
                .path("video.collections[*].title").entityList(String.class).containsExactly("Alla år")
                .path("video.next.id").entity(String.class).isEqualTo(first);

        graphQl(EDITOR)
                .document("mutation($id: ID!, $ids: [ID!]!) { collectionUpdate(id: $id, input: {title: \"Två år\", videoIds: $ids}) { title videos { id } } }")
                .variables(Map.of("id", collectionId, "ids", List.of(first)))
                .execute()
                .path("collectionUpdate.title").entity(String.class).isEqualTo("Två år")
                .path("collectionUpdate.videos[*].id").entityList(String.class).containsExactly(first);

        expectError(graphQl(EDITOR).document("mutation($id: ID!) { collectionDelete(id: $id) }").variable("id", collectionId).execute(), "FORBIDDEN");
        graphQl(ADMIN).document("mutation($id: ID!) { collectionDelete(id: $id) }").variable("id", collectionId).execute().errors().verify();
        graphQl(EDITOR).document("{ collections { id } }").execute().path("collections").entityList(Object.class).hasSize(0);
        // The change log narrows to collections, and to one collection
        graphQl(ADMIN)
                .document("query($id: ID!) { all: auditLog(filter: {entityType: \"COLLECTION\"}) { entries { action } } "
                        + "one: auditLog(filter: {entityId: $id, action: \"UPDATE\"}) { totalCount entries { actorId actorName } } auditActors { id } }")
                .variable("id", collectionId)
                .execute()
                .path("all.entries[*].action").entityList(String.class).containsExactly("DELETE", "UPDATE", "CREATE")
                .path("one.totalCount").entity(Integer.class).isEqualTo(1)
                .path("one.entries[0].actorId").hasValue()
                .path("auditActors").entityList(Object.class).hasSize(2);
        graphQl(VIEWER).document("query($id: ID!) { video(id: $id) { title } }").variable("id", first).execute()
                .path("video.title").hasValue();
    }

    @Test
    void aCollectionRefusesVideosInTheTrash() throws Exception {
        final String videoId = readyVideo(folder, true);

        graphQl(EDITOR).document("mutation($id: ID!) { videoTrash(id: $id) { id } }").variable("id", videoId).execute();
        expectError(graphQl(EDITOR).document("mutation($ids: [ID!]!) { collectionCreate(input: {title: \"X\", videoIds: $ids}) { id } }")
                .variable("ids", List.of(videoId)).execute(), "BAD_REQUEST");
        expectError(graphQl(EDITOR).document("mutation { collectionCreate(input: {title: \" \", videoIds: []}) { id } }").execute(), "BAD_REQUEST");
    }
}

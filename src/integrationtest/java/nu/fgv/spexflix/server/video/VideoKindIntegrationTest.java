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

import java.util.List;
import java.util.Map;

/**
 * Admins configure the video kinds, editors pick one for a video, and a kind in use stays.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class VideoKindIntegrationTest extends AbstractIntegrationTest {

    private static final String SHOW = "00000000-0000-7000-8000-00000000b001";
    private static final String NUMBER = "00000000-0000-7000-8000-00000000b002";

    @Test
    void theMigrationStartsWithFiveKindsAndShowIsTheDefault() {
        graphQl(VIEWER).document("{ videoKinds { id nameSv nameEn isDefault } }").execute()
                .path("videoKinds[*].nameEn").entityList(String.class).containsExactly("Show", "Number", "Trailer", "Behind the scenes", "Other")
                .path("videoKinds[0].isDefault").entity(Boolean.class).isEqualTo(true)
                .path("videoKinds[1].isDefault").entity(Boolean.class).isEqualTo(false);

        graphQl(EDITOR)
                .document("mutation { videoCreate(input: {spexId: \"00000000-0000-7000-8000-000000000001\"}) { kind { id } } }")
                .execute()
                .path("videoCreate.kind.id").entity(String.class).isEqualTo(SHOW);
    }

    @Test
    void anAdminAddsAKindThatAnEditorThenUses() {
        expectError(graphQl(EDITOR).document("mutation { videoKindCreate(input: {nameSv: \"Trailer\", nameEn: \"Trailer\"}) { id } }")
                .execute(), "FORBIDDEN");

        final String jubilee = graphQl(ADMIN)
                .document("mutation { videoKindCreate(input: {nameSv: \"Jubileum\", nameEn: \"Jubilee\"}) { id position } }")
                .execute()
                .path("videoKindCreate.position").entity(Integer.class).isEqualTo(6)
                .path("videoKindCreate.id").entity(String.class).get();
        final String videoId = graphQl(EDITOR)
                .document("mutation($kind: ID) { videoCreate(input: {title: \"Jubileumsfilmen\", kindId: $kind}) { id kind { nameSv } } }")
                .variable("kind", jubilee)
                .execute()
                .path("videoCreate.kind.nameSv").entity(String.class).isEqualTo("Jubileum")
                .path("videoCreate.id").entity(String.class).get();

        // Without a kind, an update keeps the video's
        graphQl(EDITOR)
                .document("mutation($id: ID!) { videoUpdate(id: $id, input: {title: \"Jubileumsfilmen 2\"}) { kind { id } } }")
                .variable("id", videoId)
                .execute()
                .path("videoUpdate.kind.id").entity(String.class).isEqualTo(jubilee);

        graphQl(ADMIN).document("{ videoKinds { nameEn videoCount } }").execute()
                .path("videoKinds[5].videoCount").entity(Integer.class).isEqualTo(1);
        expectError(graphQl(ADMIN).document("mutation($id: ID!) { videoKindDelete(id: $id) }").variable("id", jubilee).execute(), "CONFLICT");
    }

    @Test
    void reorderingMakesAnotherKindTheDefault() {
        final List<String> ids = graphQl(VIEWER).document("{ videoKinds { id } }").execute()
                .path("videoKinds[*].id").entityList(String.class).get();
        final List<String> reordered = new java.util.ArrayList<>(ids);

        reordered.remove(NUMBER);
        reordered.addFirst(NUMBER);
        graphQl(ADMIN).document("mutation($ids: [ID!]!) { videoKindsReorder(ids: $ids) { id } }")
                .variables(Map.of("ids", reordered))
                .execute()
                .path("videoKindsReorder[0].id").entity(String.class).isEqualTo(NUMBER);
        graphQl(VIEWER).document("{ videoKinds { id isDefault } }").execute()
                .path("videoKinds[0].id").entity(String.class).isEqualTo(NUMBER)
                .path("videoKinds[0].isDefault").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void anUnusedKindIsDeleted() {
        graphQl(ADMIN).document("mutation($id: ID!) { videoKindDelete(id: $id) }").variable("id", NUMBER).execute().errors().verify();
        graphQl(VIEWER).document("{ videoKinds { id } }").execute()
                .path("videoKinds[*].id").entityList(String.class).doesNotContain(NUMBER);
    }
}

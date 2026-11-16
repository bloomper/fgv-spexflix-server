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

package nu.fgv.spexflix.server.mcp;

import nu.fgv.spexflix.server.AbstractIntegrationTest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP server: only {@code fgv-spexflix-mcp} tokens with its audience, only tools that read, and the same visibility
 * as the web, without any address for playing a video.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class McpToolsIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> TOOLS = List.of("list_videos", "search_videos", "get_video", "list_spex", "list_playlists", "get_playlist");

    private final AtomicInteger requestId = new AtomicInteger();
    @Autowired
    private ObjectMapper objectMapper;

    private static Map<String, Object> args(final Object... pairs) {
        final Map<String, Object> map = new HashMap<>();

        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static boolean refused(final JsonNode response) {
        return response.has("error") || response.path("result").path("isError").asBoolean(false);
    }

    /**
     * The HTTP status of a JSON-RPC request to the MCP endpoint with the given Authorization header, if any.
     */
    private int status(final @Nullable String token) {
        return http().post().uri(MCP_PATH)
                .headers(headers -> {
                    if (token != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, token);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private JsonNode rpc(final String token, final String method, final Map<String, Object> params) {
        final String body = http().post().uri(MCP_PATH)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body(Map.of("jsonrpc", "2.0", "id", requestId.incrementAndGet(), "method", method, "params", params))
                .retrieve()
                .body(String.class);

        return objectMapper.readTree(body);
    }

    private JsonNode call(final String user, final String tool, final Map<String, Object> arguments) {
        final JsonNode response = rpc(mcpToken(user), "tools/call", Map.of("name", tool, "arguments", arguments));

        // Never an address for playing a video, its manifests, posters or subtitle files
        assertThat(response.toString()).as(tool).doesNotContain("media.spexflix.test", ".m3u8", ".vtt", "/v/", "/api/poster");
        return response;
    }

    private JsonNode ok(final String user, final String tool, final Map<String, Object> arguments) {
        final JsonNode response = call(user, tool, arguments);
        final JsonNode result = response.get("result");

        if (result == null || result.path("isError").asBoolean(false)) {
            throw new AssertionError("Tool error: " + response);
        }
        return objectMapper.readTree(result.get("content").get(0).get("text").asString());
    }

    private String playlist(final String user, final String name, final boolean isPublic, final String videoId) {
        final String id = graphQl(user)
                .document("mutation($input: PlaylistInput!) { playlistCreate(input: $input) { id } }")
                .variable("input", Map.of("name", name, "isPublic", isPublic))
                .execute()
                .path("playlistCreate.id").entity(String.class).get();

        graphQl(user)
                .document("mutation($id: ID!, $video: ID!) { playlistAddItem(playlistId: $id, videoId: $video) { id } }")
                .variables(Map.of("id", id, "video", videoId))
                .execute()
                .path("playlistAddItem.id").entity(String.class).isEqualTo(id);
        return id;
    }

    @Test
    void theToolsOnlyRead() {
        final List<String> names = new ArrayList<>();

        for (final JsonNode tool : rpc(mcpToken(VIEWER), "tools/list", Map.of()).path("result").path("tools")) {
            final String name = tool.get("name").asString();

            names.add(name);
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean(false)).as(name).isTrue();
            assertThat(tool.path("annotations").path("destructiveHint").asBoolean(true)).as(name).isFalse();
        }

        assertThat(names).containsExactlyInAnyOrderElementsOf(TOOLS)
                .noneMatch(name -> name.contains("play") && !name.contains("playlist") || name.contains("upload") || name.contains("poster")
                        || name.contains("subtitle") || name.contains("process") || name.contains("setting") || name.contains("maintenance")
                        || name.contains("create") || name.contains("update") || name.contains("delete") || name.contains("set_"));
    }

    @Test
    void onlyMcpTokensWithTheMcpAudienceAreAccepted() {
        assertThat(status(mcpToken(VIEWER))).isEqualTo(200);
        assertThat(status(token(VIEWER))).as("the web's token").isEqualTo(401);
        assertThat(status(publicClientToken(VIEWER, UNBOUND_MCP_CLIENT_ID))).as("a token without the MCP audience").isEqualTo(401);
        assertThat(status(null)).as("no token").isEqualTo(401);

        final String challenge = http().post().uri(MCP_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .exchange((request, response) -> response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));

        assertThat(challenge).contains("resource_metadata");

        // And an MCP token is no good for the rest of the API
        final int graphql = http().post().uri("/api/graphql")
                .header(HttpHeaders.AUTHORIZATION, mcpToken(VIEWER))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"query\": \"{ me { username } }\"}")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(graphql).isEqualTo(401);
    }

    @Test
    void withoutUserTheToolsAreRefused() {
        assertThat(refused(call(NO_ACCESS, "list_videos", args()))).isTrue();
    }

    @Test
    void viewersSeeThePublishedVideosAndEditorsTheRest(@TempDir final Path folder) throws Exception {
        final String published = readyVideo(folder, true);
        final String unpublished = readyVideo(folder, false);

        final JsonNode videos = ok(VIEWER, "list_videos", args("spexId", SPEX_ID)).get("items");

        assertThat(videos).hasSize(1);
        assertThat(videos.get(0).get("id").asString()).isEqualTo(published);
        assertThat(videos.get(0).get("title").asString()).isEqualTo("Cleopatra");
        assertThat(videos.get(0).get("year").asInt()).isEqualTo(2024);
        assertThat(videos.get(0).get("durationSeconds").asLong()).isEqualTo(10L);
        assertThat(videos.get(0).get("webUrl").asString()).isEqualTo("https://spexflix.test/videos/" + published);
        assertThat(videos.get(0).path("status").isMissingNode() || videos.get(0).path("status").isNull()).as("status for a viewer").isTrue();
        assertThat(ok(VIEWER, "list_videos", args("includeUnpublished", true)).get("items")).hasSize(1);
        assertThat(ok(VIEWER, "list_videos", args("year", 2023)).get("items")).isEmpty();

        final JsonNode all = ok(EDITOR, "list_videos", args("includeUnpublished", true)).get("items");

        assertThat(all).hasSize(2);
        assertThat(all.findValuesAsString("status")).containsOnly("READY");

        final JsonNode spex = ok(VIEWER, "list_spex", args()).get("items");

        assertThat(spex).hasSize(1);
        assertThat(spex.get(0).get("id").asString()).isEqualTo(SPEX_ID);
        assertThat(spex.get(0).get("categoryName").asString()).isEqualTo("Chalmersspexet");
        assertThat(spex.get(0).get("videoCount").asInt()).isEqualTo(1);

        assertThat(ok(VIEWER, "search_videos", args("query", "cleo")).get("items")).hasSize(1);
        assertThat(ok(EDITOR, "search_videos", args("query", "cleo")).get("items")).hasSize(2);

        final JsonNode video = ok(VIEWER, "get_video", args("videoId", published));

        assertThat(video.get("video").get("spex").get(0).get("title").asString()).isEqualTo("Cleopatra");
        assertThat(video.get("height").asInt()).isPositive();
        assertThat(refused(call(VIEWER, "get_video", args("videoId", unpublished)))).as("an unpublished video for a viewer").isTrue();
        assertThat(ok(EDITOR, "get_video", args("videoId", unpublished)).get("video").get("published").asBoolean()).isFalse();
    }

    @Test
    void playlistsAreTheCallersOwnAndThePublicOnes(@TempDir final Path folder) throws Exception {
        final String video = readyVideo(folder, true);
        final String own = playlist(VIEWER, "Mina favoriter", false, video);
        final String shared = playlist(EDITOR, "Bästa numren", true, video);
        final String hidden = playlist(EDITOR, "Hemlig", false, video);

        final JsonNode playlists = ok(VIEWER, "list_playlists", args()).get("items");

        assertThat(playlists.findValuesAsString("id")).containsExactly(own, shared);
        assertThat(playlists.get(0).get("owned").asBoolean()).isTrue();
        assertThat(playlists.get(1).get("ownerName").asString()).isNotBlank();

        final JsonNode details = ok(VIEWER, "get_playlist", args("playlistId", shared));

        assertThat(details.get("playlist").get("webUrl").asString()).isEqualTo("https://spexflix.test/playlists/" + shared);
        assertThat(details.get("items")).hasSize(1);
        assertThat(details.get("items").get(0).get("video").get("id").asString()).isEqualTo(video);
        assertThat(refused(call(VIEWER, "get_playlist", args("playlistId", hidden)))).as("someone else's private playlist").isTrue();
    }
}

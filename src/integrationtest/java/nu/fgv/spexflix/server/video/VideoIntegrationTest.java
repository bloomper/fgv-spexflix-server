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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A video from upload to watching: an editor creates it on a spex and uploads its file with tus, the worker processes
 * it into HLS with ffmpeg, and a viewer plays it from signed addresses, with progress and a watch list.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class VideoIntegrationTest extends AbstractIntegrationTest {

    private static final Pattern PLAYBACK = Pattern.compile("https://media\\.spexflix\\.test/v/(\\d+)/([A-Za-z0-9_-]+)/([0-9a-f-]{36})/(\\d+)/master\\.m3u8");

    @TempDir
    private Path folder;

    @Test
    void aVideoGoesFromUploadToViewers() throws Exception {
        final String videoId = readyVideo(folder, false);

        graphQl(VIEWER).document("{ videos { id } }").execute().path("videos").entityList(Object.class).hasSize(0);

        final var processed = graphQl(EDITOR)
                .document("query($id: ID!) { video(id: $id) { uuid title year hasThumbnails renditions { height } } }")
                .variable("id", videoId)
                .execute()
                .path("video.title").entity(String.class).isEqualTo("Cleopatra")
                .path("video.year").entity(Integer.class).isEqualTo(2024)
                .path("video.hasThumbnails").entity(Boolean.class).isEqualTo(true)
                .path("video.renditions[*].height").entityList(Integer.class).containsExactly(240);
        final String uuid = processed.path("video.uuid").entity(String.class).get();

        graphQl(EDITOR).document("mutation($id: ID!) { videoPublish(id: $id, published: true) { published } }").variable("id", videoId).execute();

        final String masterUrl = graphQl(VIEWER)
                .document("query($id: ID!) { playback(videoId: $id) { masterUrl thumbnailsUrl expiresAt } }")
                .variable("id", videoId)
                .execute()
                .path("playback.thumbnailsUrl").hasValue()
                .path("playback.masterUrl").entity(String.class).get();
        final Matcher matcher = PLAYBACK.matcher(masterUrl);

        assertThat(matcher.matches()).as(masterUrl).isTrue();
        assertThat(matcher.group(3)).isEqualTo(uuid);
        // The signature nginx's secure_link checks: base64url(md5("<expires><uuid> <key>"))
        assertThat(matcher.group(2)).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("MD5")
                .digest((matcher.group(1) + uuid + " integration-test-signing-key").getBytes(StandardCharsets.UTF_8))));
        assertThat(MEDIA.resolve("hls").resolve(uuid).resolve(matcher.group(4)).resolve("master.m3u8")).exists();
        try (var files = Files.list(MEDIA.resolve("originals").resolve(uuid))) {
            assertThat(files.map(file -> file.getFileName().toString())).anyMatch(name -> name.startsWith("original."));
        }
    }

    @Test
    void viewersKeepTheirPlaceAndAWatchList() throws Exception {
        final String videoId = readyVideo(folder, true);

        graphQl(VIEWER).document("mutation($id: ID!) { progressSave(videoId: $id, positionSeconds: 4) }").variable("id", videoId).execute();
        graphQl(VIEWER).document("mutation($id: ID!) { watchListAdd(videoId: $id) }").variable("id", videoId).execute();

        graphQl(VIEWER)
                .document("{ continueWatching { id progressSeconds finished } watchList { id inWatchList } }")
                .execute()
                .path("continueWatching[0].id").entity(String.class).isEqualTo(videoId)
                .path("continueWatching[0].progressSeconds").entity(Integer.class).isEqualTo(4)
                .path("continueWatching[0].finished").entity(Boolean.class).isEqualTo(false)
                .path("watchList[0].inWatchList").entity(Boolean.class).isEqualTo(true);
        // Progress and the watch list are the viewer's own
        graphQl(EDITOR).document("{ continueWatching { id } watchList { id } }").execute()
                .path("continueWatching").entityList(Object.class).hasSize(0)
                .path("watchList").entityList(Object.class).hasSize(0);

        graphQl(VIEWER).document("mutation($id: ID!) { progressSave(videoId: $id, positionSeconds: 10) }").variable("id", videoId).execute();
        graphQl(VIEWER).document("{ continueWatching { id } }").execute().path("continueWatching").entityList(Object.class).hasSize(0);
    }

    @Test
    void subtitlesAreConvertedAndPlayed() throws Exception {
        final String videoId = readyVideo(folder, true);
        final MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();

        form.add("file", new ByteArrayResource("1\n00:00:01,000 --> 00:00:04,500\nOuvertyr\n".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "cleopatra.srt";
            }
        });
        form.add("language", "sv");
        form.add("label", "Svenska");
        http().post()
                .uri("/api/videos/{id}/subtitles", videoId)
                .header(HttpHeaders.AUTHORIZATION, token(EDITOR))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .toBodilessEntity();

        final String url = graphQl(VIEWER)
                .document("query($id: ID!) { playback(videoId: $id) { subtitles { language url } } }")
                .variable("id", videoId)
                .execute()
                .path("playback.subtitles[0].language").entity(String.class).isEqualTo("sv")
                .path("playback.subtitles[0].url").entity(String.class).get();
        final String uuid = url.split("/")[6];

        assertThat(Files.readString(MEDIA.resolve("hls").resolve(uuid).resolve("subtitles").resolve("sv.vtt")))
                .startsWith("WEBVTT").contains("00:00:01.000 --> 00:00:04.500");
    }

    @Test
    void onlyReadyVideosArePlayedOrPublished() {
        final String videoId = graphQl(EDITOR)
                .document("mutation { videoCreate(input: {spexId: 1, kind: TRAILER}) { id } }")
                .execute()
                .path("videoCreate.id").entity(String.class).get();

        expectError(graphQl(EDITOR).document("query($id: ID!) { playback(videoId: $id) { masterUrl } }").variable("id", videoId).execute(),
                "BAD_REQUEST");
        expectError(graphQl(EDITOR).document("mutation($id: ID!) { videoPublish(id: $id, published: true) { id } }").variable("id", videoId).execute(),
                "BAD_REQUEST");
    }

    @Test
    void trashedVideosAreDeletedOnlyByAdmins() throws Exception {
        final String videoId = readyVideo(folder, true);

        graphQl(EDITOR).document("mutation($id: ID!) { videoTrash(id: $id) { trashedAt } }").variable("id", videoId).execute()
                .path("videoTrash.trashedAt").hasValue();
        graphQl(VIEWER).document("{ videos { id } }").execute().path("videos").entityList(Object.class).hasSize(0);

        expectError(graphQl(EDITOR).document("mutation($id: ID!) { videoDelete(id: $id) }").variable("id", videoId).execute(), "FORBIDDEN");
        graphQl(ADMIN).document("mutation($id: ID!) { videoDelete(id: $id) }").variable("id", videoId).execute().errors().verify();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM video").query(Long.class).single()).isZero();
        try (var files = Files.list(MEDIA.resolve("hls"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void viewersCannotSeeUnpublishedVideos() {
        final String videoId = graphQl(EDITOR)
                .document("mutation { videoCreate(input: {spexId: 1, kind: NUMBER}) { id } }")
                .execute()
                .path("videoCreate.id").entity(String.class).get();

        graphQl(VIEWER)
                .document("query($id: ID!) { video(id: $id) { id } }")
                .variable("id", videoId)
                .execute()
                .path("video").valueIsNull();
        graphQl(VIEWER).document("{ videos(filter: {includeUnpublished: true}) { id } }").execute()
                .path("videos").entityList(Object.class).hasSize(0);
        graphQl(EDITOR).document("{ videos(filter: {includeUnpublished: true}) { id } }").execute()
                .path("videos[*].id").entityList(String.class).contains(videoId);
    }
}

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

import nu.fgv.spexflix.server.processing.MediaStorage;
import nu.fgv.spexflix.server.spex.SpexService;
import nu.fgv.spexflix.server.util.AbstractApiTest;
import nu.fgv.spexflix.server.util.error.ResourceNotFoundException;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;
import org.springframework.restdocs.request.PathParametersSnippet;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static nu.fgv.spexflix.server.util.TestUuids.uuid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.multipart;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.relaxedResponseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.partWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.requestParts;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documents the poster and subtitle endpoints. Video is served by the media server, not here.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@WebMvcTest(MediaController.class)
class MediaHttpApiTest extends AbstractApiTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};

    private final PathParametersSnippet videoId = pathParameters(parameterWithName("id").description("The id of the video"));
    private final ResponseFieldsSnippet videoFields = relaxedResponseFields(
            fieldWithPath("id").description("The id of the video"),
            fieldWithPath("title").description("The title of the video"),
            fieldWithPath("subtitles").description("The video's subtitles, by language")
    );

    @MockitoBean
    private VideoService videoService;

    @MockitoBean
    private MediaStorage storage;

    @MockitoBean
    private SpexService spexService;

    @TempDir
    private Path folder;

    private Video video;

    private static VideoDto videoDto() {
        return new VideoDto(uuid(1), "Cleopatra", 2024, VideoKind.FULL_SHOW, "Premiären",
                "2024-02-14", null, null, VideoStatus.READY, null, 7_200_000d, 1920, 1080, true,
                Instant.parse("2026-10-01T10:00:00Z"), null, "cleopatra.mkv", 4_000_000_000d, true, true, 600d,
                List.of(), List.of(), List.of(), Instant.parse("2026-09-30T10:00:00Z"), null);
    }

    @BeforeEach
    void setUpVideo() throws IOException {
        video = new Video();
        video.setId(1L);
        video.setTitle("Cleopatra");
        video.setPosterUploaded(true);
        when(storage.uploadedPoster(video)).thenReturn(Files.write(folder.resolve("poster-uploaded.jpg"), JPEG));
        when(storage.poster(video)).thenReturn(folder.resolve("poster.jpg"));
    }

    @Test
    void servesAPoster() throws Exception {
        when(videoService.visible(uuid(1))).thenReturn(video);

        mockMvc
                .perform(get("/api/videos/{id}/poster", uuid(1)).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andDo(document(
                        "video-poster-get",
                        videoId,
                        secureRequestHeaders,
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("The image's type"),
                                headerWithName(HttpHeaders.CACHE_CONTROL).description("Private, for an hour")
                        ),
                        security(List.of("USER"))
                ));
    }

    @Test
    void uploadsAPoster() throws Exception {
        when(videoService.posterUpload(eq(uuid(1)), any())).thenReturn(videoDto());

        mockMvc
                .perform(multipart("/api/videos/{id}/poster", uuid(1))
                        .file(new MockMultipartFile("file", "poster.jpg", "image/jpeg", JPEG))
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andDo(document(
                        "video-poster-post",
                        preprocessResponse(prettyPrint()),
                        videoId,
                        requestParts(partWithName("file").description("A JPEG, PNG or WebP image of at most 10 MB")),
                        secureRequestHeaders,
                        videoFields,
                        security(List.of("USER", "EDITOR"))
                ));
    }

    @Test
    void uploadsASubtitle() throws Exception {
        when(videoService.subtitleSet(eq(uuid(1)), eq("sv"), eq("Svenska"), any())).thenReturn(videoDto());

        mockMvc
                .perform(multipart("/api/videos/{id}/subtitles", uuid(1))
                        .file(new MockMultipartFile("file", "cleopatra.srt", "application/x-subrip",
                                "1\n00:00:01,000 --> 00:00:04,500\nOuvertyr\n".getBytes(StandardCharsets.UTF_8)))
                        .param("language", "sv")
                        .param("label", "Svenska")
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andDo(document(
                        "video-subtitle-post",
                        preprocessResponse(prettyPrint()),
                        videoId,
                        requestParts(partWithName("file").description("WebVTT, or SubRip, which is converted to WebVTT; at most 2 MB")),
                        secureRequestHeaders,
                        videoFields,
                        security(List.of("USER", "EDITOR"))
                ));
    }

    @Test
    void aVideoTheCallerMayNotSeeIsNotFound() throws Exception {
        when(videoService.visible(uuid(2))).thenThrow(new ResourceNotFoundException(Video.class, uuid(2)));

        mockMvc
                .perform(get("/api/videos/{id}/poster", uuid(2)).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andDo(document(
                        "error",
                        preprocessResponse(prettyPrint()),
                        errorResponseFields
                ));
    }

    @Test
    void withoutAnUploadedPosterTheFrameOrTheSpexPosterIsShown() throws Exception {
        video.setPosterUploaded(false);
        when(videoService.visible(uuid(1))).thenReturn(video);
        when(storage.poster(video)).thenReturn(Files.write(folder.resolve("frame.jpg"), JPEG));
        mockMvc.perform(get("/api/videos/{id}/poster", uuid(1))).andExpect(status().isOk());

        when(storage.poster(video)).thenReturn(folder.resolve("missing.jpg"));
        video.getSpex().setId(uuid(7));
        when(spexService.poster(uuid(7))).thenReturn(java.util.Optional.of(new nu.fgv.spexflix.server.spex.SpexregisterClient.Poster(JPEG, "image/jpeg")));
        mockMvc.perform(get("/api/videos/{id}/poster", uuid(1))).andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"));

        when(spexService.poster(uuid(7))).thenReturn(java.util.Optional.empty());
        mockMvc.perform(get("/api/videos/{id}/poster", uuid(1))).andExpect(status().isNotFound());

        video.getSpex().setId(null);
        mockMvc.perform(get("/api/videos/{id}/poster", uuid(1))).andExpect(status().isNotFound());
    }

    @Test
    void anythingButAnImageOrSubtitlesIsRefused() throws Exception {
        mockMvc.perform(multipart("/api/videos/{id}/poster", uuid(1)).file(new MockMultipartFile("file", "p.jpg", "image/jpeg", "<svg/>".getBytes())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/videos/{id}/subtitles", uuid(1)).file(new MockMultipartFile("file", "s.srt", "text/plain", "bara text".getBytes()))
                        .param("language", "sv").param("label", "Svenska"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/videos/{id}/subtitles", uuid(1)).file(new MockMultipartFile("file", "s.srt", "text/plain", new byte[0]))
                        .param("language", "sv").param("label", "Svenska"))
                .andExpect(status().isBadRequest());
    }
}

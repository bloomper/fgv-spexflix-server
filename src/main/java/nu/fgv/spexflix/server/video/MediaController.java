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
import nu.fgv.spexflix.server.util.error.BadRequestException;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Posters and uploads of posters and subtitles, which GraphQL does not suit. Video itself is served by the media
 * server from signed addresses, never by Java. A thin adapter: {@link VideoService} decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
public class MediaController {

    private static final long MAX_IMAGE = 10L * 1024 * 1024;
    private static final long MAX_SUBTITLE = 2L * 1024 * 1024;
    private static final Pattern SRT_TIME = Pattern.compile("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})");

    private final VideoService videoService;
    private final MediaStorage storage;
    private final SpexService spexService;

    public MediaController(final VideoService videoService, final MediaStorage storage, final SpexService spexService) {
        this.videoService = videoService;
        this.storage = storage;
        this.spexService = spexService;
    }

    /**
     * The uploaded poster, else the frame taken in processing, else the spex poster, else 404.
     */
    @GetMapping("/api/videos/{id}/poster")
    public ResponseEntity<?> poster(@PathVariable final Long id) throws IOException {
        final Video video = videoService.visible(id);
        final Path uploaded = storage.uploadedPoster(video);
        final Path frame = storage.poster(video);
        final Path file = video.isPosterUploaded() && Files.isRegularFile(uploaded) ? uploaded : Files.isRegularFile(frame) ? frame : null;

        if (file != null) {
            return ResponseEntity.ok()
                    .contentType(Objects.requireNonNullElse(imageType(head(file)), MediaType.IMAGE_JPEG))
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                    .body(new FileSystemResource(file));
        }
        if (video.getSpex().getId() != null) {
            return spexService.poster(video.getSpex().getId())
                    .<ResponseEntity<?>>map(poster -> ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(poster.contentType()))
                            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                            .body(poster.content()))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/api/videos/{id}/poster")
    public VideoDto uploadPoster(@PathVariable final Long id, @RequestParam("file") final MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_IMAGE) {
            throw new BadRequestException("error.poster.invalid");
        }

        final byte[] content = file.getBytes();

        if (imageType(content) == null) {
            throw new BadRequestException("error.poster.invalid");
        }
        return videoService.posterUpload(id, content);
    }

    /**
     * A subtitle file in WebVTT, or SubRip, which is converted to WebVTT.
     */
    @PostMapping("/api/videos/{id}/subtitles")
    public VideoDto uploadSubtitle(@PathVariable final Long id, @RequestParam("file") final MultipartFile file,
                                   @RequestParam("language") final String language,
                                   @RequestParam("label") final String label) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_SUBTITLE) {
            throw new BadRequestException("error.subtitle.invalid");
        }
        return videoService.subtitleSet(id, language, label, toWebVtt(new String(file.getBytes(), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
    }

    static String toWebVtt(final String text) {
        final String content = text.startsWith("﻿") ? text.substring(1) : text;

        if (content.startsWith("WEBVTT")) {
            return content;
        }
        if (!SRT_TIME.matcher(content).find()) {
            throw new BadRequestException("error.subtitle.invalid");
        }
        return "WEBVTT\n\n" + SRT_TIME.matcher(content.replace("\r\n", "\n")).replaceAll("$1.$2");
    }

    /**
     * The first bytes of a file, enough to tell what image it is, without reading all of it.
     */
    private static byte[] head(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(16);
        }
    }

    static @Nullable MediaType imageType(final byte[] content) {
        if (content.length > 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8) {
            return MediaType.IMAGE_JPEG;
        }
        if (content.length > 8 && (content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N' && content[3] == 'G') {
            return MediaType.IMAGE_PNG;
        }
        if (content.length > 12 && content[0] == 'R' && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return MediaType.parseMediaType("image/webp");
        }
        return null;
    }
}

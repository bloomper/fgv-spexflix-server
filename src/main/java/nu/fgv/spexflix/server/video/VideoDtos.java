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

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * What the GraphQL API returns and takes for videos.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class VideoDtos {

    private VideoDtos() {
    }

    public record VideoDto(Long id, String uuid, String title, @Nullable Integer year, VideoKind kind,
                           @Nullable String performance, @Nullable String performanceDate, @Nullable String description,
                           @Nullable VideoSpexDto spex, VideoStatus status, @Nullable String failure,
                           @Nullable Double durationMs, @Nullable Integer width, @Nullable Integer height,
                           boolean published, @Nullable Instant publishedAt, @Nullable Instant trashedAt,
                           @Nullable String originalName, @Nullable Double originalSize, boolean originalKept,
                           boolean hasThumbnails, @Nullable Double posterSeconds, List<RenditionDto> renditions,
                           List<ChapterDto> chapters, List<SubtitleDto> subtitles, Instant createdAt,
                           @Nullable Instant lastModifiedAt) {
    }

    public record VideoSpexDto(Long id, @Nullable String title, @Nullable Integer year, @Nullable Boolean revival,
                               @Nullable Long categoryId, @Nullable String categoryName) {
    }

    public record RenditionDto(int height, int width, int bitrateKbps, boolean copied, double size) {
    }

    public record ChapterDto(int startSeconds, String title, @Nullable Long spexifyTrackId,
                             @Nullable String spexifyTrackTitle) {
    }

    public record SubtitleDto(String language, String label) {
    }

    public record CategoryDto(Long id, String name, int videoCount) {
    }

    public record VideoFilter(@Nullable Long categoryId, @Nullable Integer year, @Nullable Long spexId,
                              @Nullable VideoKind kind, @Nullable Boolean includeUnpublished) {
    }

    public record VideoInput(@Nullable Long spexId, @Nullable String title, @Nullable Integer year, VideoKind kind,
                             @Nullable String performance, @Nullable String performanceDate,
                             @Nullable String description) {
    }

    public record ChapterInput(int startSeconds, String title, @Nullable Long spexifyTrackId,
                               @Nullable String spexifyTrackTitle) {
    }

    public static VideoDto toDto(final Video video) {
        final SpexRef spex = video.getSpex();

        return new VideoDto(video.getId(), video.getUuid(), video.getTitle(), video.getYear(), video.getKind(),
                video.getPerformance(), video.getPerformanceDate() == null ? null : video.getPerformanceDate().toString(),
                video.getDescription(),
                spex.getId() == null ? null : new VideoSpexDto(spex.getId(), spex.getTitle(), spex.getYear(), spex.getRevival(),
                        spex.getCategoryId(), spex.getCategoryName()),
                video.getStatus(), video.getFailure(),
                video.getDurationMs() == null ? null : video.getDurationMs().doubleValue(), video.getWidth(), video.getHeight(),
                video.isPublished(), video.getPublishedAt(), video.getTrashedAt(), video.getOriginalName(),
                video.getOriginalSize() == null ? null : video.getOriginalSize().doubleValue(), video.isOriginalKept(),
                video.isHasThumbnails(), video.getPosterSeconds(),
                video.getRenditions().stream().map(r -> new RenditionDto(r.getHeight(), r.getWidth(), r.getBitrateKbps(), r.isCopied(), r.getSize())).toList(),
                video.getChapters().stream().map(c -> new ChapterDto(c.getStartSeconds(), c.getTitle(), c.getSpexifyTrackId(), c.getSpexifyTrackTitle())).toList(),
                video.getSubtitles().stream().map(s -> new SubtitleDto(s.getLanguage(), s.getLabel())).toList(),
                video.getCreatedAt(), video.getLastModifiedAt());
    }
}

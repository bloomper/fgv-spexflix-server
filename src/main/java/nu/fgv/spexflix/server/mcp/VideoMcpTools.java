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

import nu.fgv.spexflix.server.playback.PlaybackService;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.util.StoredText;
import nu.fgv.spexflix.server.util.error.ResourceNotFoundException;
import nu.fgv.spexflix.server.video.CreditService;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import nu.fgv.spexflix.server.video.VideoDtos.VideoFilter;
import nu.fgv.spexflix.server.video.VideoDtos.VideoSort;
import nu.fgv.spexflix.server.video.VideoDtos.VideoSpexDto;
import nu.fgv.spexflix.server.video.VideoService;
import nu.fgv.spexflix.server.video.VideoStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The catalogue: videos as the caller may see them, and the spex they are about. Never addresses for playing them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class VideoMcpTools {

    private final VideoService videoService;
    private final PlaybackService playbackService;
    private final CreditService creditService;
    private final CallerService callerService;
    private final McpToolSupport support;
    private final MessageSource messages;

    public VideoMcpTools(final VideoService videoService, final PlaybackService playbackService, final CreditService creditService,
                         final CallerService callerService, final McpToolSupport support, final MessageSource messages) {
        this.messages = messages;
        this.videoService = videoService;
        this.playbackService = playbackService;
        this.creditService = creditService;
        this.callerService = callerService;
        this.support = support;
    }

    static @Nullable Long seconds(final @Nullable Double durationMs) {
        return durationMs == null ? null : Math.round(durationMs / 1000);
    }

    /**
     * A video as the lists return it; its status only for editors.
     */
    McpVideo toMcp(final VideoDto video, final boolean editor) {
        return new McpVideo(video.id(), video.title(), video.year(), new McpKind(video.kind().id(), video.kind().nameSv(), video.kind().nameEn()),
                video.performance(), video.performanceDate(), video.spex().stream().map(McpSpex::of).toList(), seconds(video.durationMs()),
                video.subtitles().stream().map(subtitle -> new McpSubtitle(subtitle.language(), subtitle.label())).toList(),
                editor ? video.status() : null, editor ? video.published() : null, support.webUrl("/videos/" + video.id()));
    }

    private List<McpVideo> toMcp(final List<VideoDto> videos) {
        final boolean editor = callerService.current().isEditor();

        return videos.stream().map(video -> toMcp(video, editor)).toList();
    }

    @McpTool(name = "list_videos",
            description = "List the videos you can see, newest spex year first unless sorted otherwise, with title, year, kind, spex, duration and subtitles. "
                    + "Filter by spex (an original's id also lists its revivals' videos; list_spex gives the ids), year or years, category or kind. "
                    + "Editors can include unpublished, processing and failed videos, and see each video's status.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<McpVideo> listVideos(
            @McpToolParam(required = false, description = "Only videos about this spex, by its id in Spexipedia") final @Nullable UUID spexId,
            @McpToolParam(required = false, description = "Only videos from this year") final @Nullable Integer year,
            @McpToolParam(required = false, description = "Only videos from this year or later") final @Nullable Integer fromYear,
            @McpToolParam(required = false, description = "Only videos from this year or earlier") final @Nullable Integer toYear,
            @McpToolParam(required = false, description = "Only videos of this category, e.g. Chalmersspexet (list_spex gives the ids)") final @Nullable UUID categoryId,
            @McpToolParam(required = false, description = "Only videos of this kind, e.g. a whole show or a number (the videos give the ids)") final @Nullable UUID kindId,
            @McpToolParam(required = false, description = "Editors: also unpublished, processing and failed videos") final @Nullable Boolean includeUnpublished,
            @McpToolParam(required = false, description = "YEAR_DESC (default), YEAR_ASC, TITLE, PUBLISHED (most recently published first), RATING or LIKES") final @Nullable VideoSort sort) {
        return support.call(() -> new McpToolSupport.ListResult<>(toMcp(playbackService.browse(new VideoFilter(categoryId, year, spexId, kindId,
                includeUnpublished, fromYear, toYear, null, null, sort == null ? VideoSort.YEAR_DESC : sort)))));
    }

    @McpTool(name = "search_videos",
            description = "Search the videos you can see by words in their titles, spex, descriptions, performances or chapters; at most 50. "
                    + "Editors also find unpublished videos.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<McpVideo> searchVideos(@McpToolParam(description = "At least two characters") final String query) {
        return support.call(() -> new McpToolSupport.ListResult<>(toMcp(videoService.search(query))));
    }

    @McpTool(name = "get_video",
            description = "Get one video you can see: title, year, kind, spex, performance, description, duration, resolution, chapters, subtitles "
                    + "and credits, and for editors its status. Not the addresses for playing it: it is watched in Spexflix Web (webUrl).",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpVideoDetails getVideo(@McpToolParam(description = "A video you can see") final UUID videoId) {
        return support.call(() -> {
            final VideoDto video = videoService.video(videoId);

            if (video == null) {
                throw new ResourceNotFoundException("error.video.notFound", videoId);
            }

            final boolean editor = callerService.current().isEditor();

            return new McpVideoDetails(toMcp(video, editor), video.description(), video.height(), video.publishedAt(),
                    video.chapters().stream().map(chapter -> new McpChapter(chapter.startSeconds(), chapter.title(), chapter.spexifyTrackTitle())).toList(),
                    creditService.creditsOf(Set.of(videoId)).getOrDefault(videoId, List.of()).stream()
                            .map(credit -> new McpCredit(credit.name(), credit.role())).toList(),
                    editor && video.failure() != null ? StoredText.decode(video.failure()).in(messages, Locale.ENGLISH) : null, editor ? video.trashedAt() != null : null);
        });
    }

    @McpTool(name = "list_spex",
            description = "List the spex that have published videos, newest first, with their year, whether they are revivals (and of which original), "
                    + "their category and how many videos each has. The spex's ids are Spexipedia's.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<McpSpexWithVideos> listSpex() {
        return support.call(() -> {
            final Map<UUID, VideoSpexDto> spex = new LinkedHashMap<>();
            final Map<UUID, Integer> counts = new LinkedHashMap<>();

            for (final VideoDto video : videoService.videos(null)) {
                for (final VideoSpexDto ref : video.spex()) {
                    spex.putIfAbsent(ref.id(), ref);
                    counts.merge(ref.id(), 1, Integer::sum);
                }
            }
            return new McpToolSupport.ListResult<>(spex.values().stream()
                    .map(ref -> new McpSpexWithVideos(ref.id(), ref.title(), ref.year(), Boolean.TRUE.equals(ref.revival()), ref.parentId(),
                            ref.categoryId(), ref.categoryName(), counts.get(ref.id())))
                    .sorted(Comparator.comparing((McpSpexWithVideos item) -> Objects.requireNonNullElse(item.year(), 0)).reversed()
                            .thenComparing(item -> Objects.requireNonNullElse(item.title(), "")))
                    .toList());
        });
    }

    public record McpKind(UUID id, String nameSv, String nameEn) {
    }

    /**
     * A spex a video is about; a revival names its original.
     */
    public record McpSpex(UUID id, @Nullable String title, @Nullable Integer year, boolean revival,
                          @Nullable UUID originalId, @Nullable String categoryName) {

        static McpSpex of(final VideoSpexDto spex) {
            return new McpSpex(spex.id(), spex.title(), spex.year(), Boolean.TRUE.equals(spex.revival()), spex.parentId(), spex.categoryName());
        }
    }

    public record McpSubtitle(String language, String label) {
    }

    /**
     * A video as the lists return it. {@code status} and {@code published} only for editors.
     */
    public record McpVideo(UUID id, String title, @Nullable Integer year, McpKind kind, @Nullable String performance,
                           @Nullable String performanceDate, List<McpSpex> spex, @Nullable Long durationSeconds,
                           List<McpSubtitle> subtitles, @Nullable VideoStatus status, @Nullable Boolean published,
                           @Nullable String webUrl) {
    }

    public record McpChapter(int startSeconds, String title, @Nullable String spexifyTrackTitle) {
    }

    public record McpCredit(String name, @Nullable String role) {
    }

    /**
     * A video as {@code get_video} returns it. {@code failure} and {@code trashed} only for editors.
     */
    public record McpVideoDetails(McpVideo video, @Nullable String description, @Nullable Integer height,
                                  @Nullable Instant publishedAt, List<McpChapter> chapters, List<McpCredit> credits,
                                  @Nullable String failure, @Nullable Boolean trashed) {
    }

    public record McpSpexWithVideos(UUID id, @Nullable String title, @Nullable Integer year, boolean revival,
                                    @Nullable UUID originalId, @Nullable UUID categoryId, @Nullable String categoryName,
                                    int videoCount) {
    }
}

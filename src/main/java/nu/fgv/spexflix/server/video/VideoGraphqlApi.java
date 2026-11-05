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

import nu.fgv.spexflix.server.playback.PlaybackService;
import nu.fgv.spexflix.server.playback.WatchProgress;
import nu.fgv.spexflix.server.processing.ProcessingService;
import nu.fgv.spexflix.server.spex.SpexDto;
import nu.fgv.spexflix.server.spex.SpexService;
import nu.fgv.spexflix.server.video.VideoDtos.CategoryDto;
import nu.fgv.spexflix.server.video.VideoDtos.ChapterInput;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import nu.fgv.spexflix.server.video.VideoDtos.VideoFilter;
import nu.fgv.spexflix.server.video.VideoDtos.VideoInput;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GraphQL for videos and watching. A thin adapter: the services decide access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class VideoGraphqlApi {

    private final VideoService service;
    private final PlaybackService playbackService;
    private final ProcessingService processingService;
    private final SpexService spexService;

    public VideoGraphqlApi(final VideoService service, final PlaybackService playbackService,
                           final ProcessingService processingService, final SpexService spexService) {
        this.service = service;
        this.playbackService = playbackService;
        this.processingService = processingService;
        this.spexService = spexService;
    }

    @QueryMapping
    public List<VideoDto> videos(@Argument final @Nullable VideoFilter filter) {
        return playbackService.browse(filter);
    }

    @QueryMapping
    public List<VideoDto> likedVideos() {
        return playbackService.likedVideos();
    }

    @QueryMapping
    public List<VideoDto> topVideos(@Argument final PlaybackService.@Nullable TopBy by, @Argument final @Nullable VideoFilter filter,
                                    @Argument final @Nullable Integer limit) {
        return playbackService.top(by == null ? PlaybackService.TopBy.RATED : by, filter, limit == null ? 20 : limit);
    }

    @QueryMapping
    public @Nullable VideoDto video(@Argument final UUID id) {
        return service.video(id);
    }

    @QueryMapping
    public List<VideoDto> recentVideos(@Argument final @Nullable Integer limit) {
        return service.recent(limit == null ? 12 : limit);
    }

    @QueryMapping
    public List<VideoDto> continueWatching(@Argument final @Nullable Integer limit) {
        return playbackService.continueWatching(limit == null ? 12 : limit);
    }

    @QueryMapping
    public List<VideoDto> highestRated(@Argument final @Nullable Integer limit) {
        return playbackService.highestRated(limit == null ? 12 : limit);
    }

    @QueryMapping
    public List<VideoDto> watchList() {
        return playbackService.watchList();
    }

    @QueryMapping
    public List<CategoryDto> categories() {
        return service.categories();
    }

    @QueryMapping
    public List<Integer> years() {
        return service.years();
    }

    @QueryMapping
    public List<VideoDto> search(@Argument final String q) {
        return service.search(q);
    }

    @QueryMapping
    public PlaybackService.PlaybackDto playback(@Argument final UUID videoId) {
        return playbackService.playback(videoId);
    }

    @QueryMapping
    public List<SpexDto> spexSearch(@Argument final String q) {
        return spexService.search(q);
    }

    @QueryMapping
    public List<VideoDto> trash() {
        return service.trash();
    }

    @BatchMapping(typeName = "Video", field = "progressSeconds")
    public Map<VideoDto, @Nullable Integer> progressSeconds(final List<VideoDto> videos) {
        final PlaybackService.PersonalState state = playbackService.personal(videos.stream().map(VideoDto::id).toList());
        final Map<VideoDto, @Nullable Integer> result = new LinkedHashMap<>();

        videos.forEach(video -> {
            final WatchProgress progress = state.progress().get(video.id());

            result.put(video, progress == null ? null : progress.getPositionSeconds());
        });
        return result;
    }

    @BatchMapping(typeName = "Video", field = "finished")
    public Map<VideoDto, Boolean> finished(final List<VideoDto> videos) {
        final PlaybackService.PersonalState state = playbackService.personal(videos.stream().map(VideoDto::id).toList());
        final Map<VideoDto, Boolean> result = new LinkedHashMap<>();

        videos.forEach(video -> result.put(video, state.progress().containsKey(video.id()) && state.progress().get(video.id()).isFinished()));
        return result;
    }

    @BatchMapping(typeName = "Video", field = "myRating")
    public Map<VideoDto, Integer> myRating(final List<VideoDto> videos) {
        final PlaybackService.PersonalState state = playbackService.personal(videos.stream().map(VideoDto::id).toList());
        final Map<VideoDto, Integer> result = new LinkedHashMap<>();

        videos.forEach(video -> result.put(video, state.ratings().getOrDefault(video.id(), 0)));
        return result;
    }

    @BatchMapping(typeName = "Video", field = "averageRating")
    public Map<VideoDto, @Nullable Double> averageRating(final List<VideoDto> videos) {
        final Map<UUID, PlaybackService.RatingSummary> summaries = playbackService.ratingSummaries(videos.stream().map(VideoDto::id).toList());
        final Map<VideoDto, @Nullable Double> result = new LinkedHashMap<>();

        videos.forEach(video -> {
            final PlaybackService.RatingSummary summary = summaries.get(video.id());

            result.put(video, summary == null ? null : summary.average());
        });
        return result;
    }

    @BatchMapping(typeName = "Video", field = "ratingCount")
    public Map<VideoDto, Integer> ratingCount(final List<VideoDto> videos) {
        final Map<UUID, PlaybackService.RatingSummary> summaries = playbackService.ratingSummaries(videos.stream().map(VideoDto::id).toList());
        final Map<VideoDto, Integer> result = new LinkedHashMap<>();

        videos.forEach(video -> result.put(video, summaries.containsKey(video.id()) ? summaries.get(video.id()).count() : 0));
        return result;
    }

    @BatchMapping(typeName = "Video", field = "inWatchList")
    public Map<VideoDto, Boolean> inWatchList(final List<VideoDto> videos) {
        final PlaybackService.PersonalState state = playbackService.personal(videos.stream().map(VideoDto::id).toList());
        final Map<VideoDto, Boolean> result = new LinkedHashMap<>();

        videos.forEach(video -> result.put(video, state.watchList().contains(video.id())));
        return result;
    }

    @BatchMapping(typeName = "Video", field = "liked")
    public Map<VideoDto, Boolean> liked(final List<VideoDto> videos) {
        final PlaybackService.PersonalState state = playbackService.personal(videos.stream().map(VideoDto::id).toList());
        final Map<VideoDto, Boolean> result = new LinkedHashMap<>();

        videos.forEach(video -> result.put(video, state.likes().contains(video.id())));
        return result;
    }

    @BatchMapping(typeName = "Video", field = "likeCount")
    public Map<VideoDto, Integer> likeCount(final List<VideoDto> videos) {
        final Map<UUID, Integer> counts = playbackService.likeCounts(videos.stream().map(VideoDto::id).toList());
        final Map<VideoDto, Integer> result = new LinkedHashMap<>();

        videos.forEach(video -> result.put(video, counts.getOrDefault(video.id(), 0)));
        return result;
    }

    @BatchMapping(typeName = "Video", field = "watchCount")
    public Map<VideoDto, Integer> watchCount(final List<VideoDto> videos) {
        final Map<UUID, Integer> counts = playbackService.watchCounts(videos.stream().map(VideoDto::id).toList());
        final Map<VideoDto, Integer> result = new LinkedHashMap<>();

        videos.forEach(video -> result.put(video, counts.getOrDefault(video.id(), 0)));
        return result;
    }

    @BatchMapping(typeName = "Video", field = "job")
    public Map<VideoDto, ProcessingService.@Nullable JobDto> job(final List<VideoDto> videos) {
        final Map<UUID, ProcessingService.JobDto> jobs = processingService.latest(videos.stream().map(VideoDto::id).toList());
        final Map<VideoDto, ProcessingService.@Nullable JobDto> result = new LinkedHashMap<>();

        videos.forEach(video -> result.put(video, jobs.get(video.id())));
        return result;
    }

    @MutationMapping
    public VideoDto videoCreate(@Argument final VideoInput input) {
        return service.create(input);
    }

    @MutationMapping
    public VideoDto videoUpdate(@Argument final UUID id, @Argument final VideoInput input) {
        return service.update(id, input);
    }

    @MutationMapping
    public VideoDto videoSetFile(@Argument final UUID id, @Argument final String uploadId) {
        return service.setFile(id, uploadId);
    }

    @MutationMapping
    public VideoDto videoPublish(@Argument final UUID id, @Argument final boolean published) {
        return service.publish(id, published);
    }

    @MutationMapping
    public VideoDto videoTrash(@Argument final UUID id) {
        return service.trash(id, true);
    }

    @MutationMapping
    public VideoDto videoRestore(@Argument final UUID id) {
        return service.trash(id, false);
    }

    @MutationMapping
    public @Nullable Void videoDelete(@Argument final UUID id) {
        service.delete(id);
        return null;
    }

    @MutationMapping
    public @Nullable Void trashEmpty() {
        service.emptyTrash();
        return null;
    }

    @MutationMapping
    public VideoDto videoPosterFrame(@Argument final UUID id, @Argument final double seconds) {
        return service.posterFrame(id, seconds);
    }

    @MutationMapping
    public VideoDto videoReprocess(@Argument final UUID id) {
        return service.reprocess(id);
    }

    @MutationMapping
    public VideoDto chaptersSet(@Argument final UUID videoId, @Argument final List<ChapterInput> chapters) {
        return service.setChapters(videoId, chapters);
    }

    @MutationMapping
    public VideoDto subtitleRemove(@Argument final UUID videoId, @Argument final String language) {
        return service.subtitleRemove(videoId, language);
    }

    @MutationMapping
    public @Nullable Void progressSave(@Argument final UUID videoId, @Argument final int positionSeconds) {
        playbackService.saveProgress(videoId, positionSeconds);
        return null;
    }

    @MutationMapping
    public @Nullable Void progressClear(@Argument final UUID videoId) {
        playbackService.clearProgress(videoId);
        return null;
    }

    @MutationMapping
    public @Nullable Void videoMarkWatched(@Argument final UUID videoId) {
        playbackService.markWatched(videoId);
        return null;
    }

    @MutationMapping
    public @Nullable Void videoRate(@Argument final UUID videoId, @Argument final int rating) {
        playbackService.rate(videoId, rating);
        return null;
    }

    @MutationMapping
    public @Nullable Void videoLike(@Argument final UUID videoId, @Argument final boolean liked) {
        playbackService.like(videoId, liked);
        return null;
    }

    @MutationMapping
    public @Nullable Void watchListAdd(@Argument final UUID videoId) {
        playbackService.watchListAdd(videoId);
        return null;
    }

    @MutationMapping
    public @Nullable Void watchListRemove(@Argument final UUID videoId) {
        playbackService.watchListRemove(videoId);
        return null;
    }
}

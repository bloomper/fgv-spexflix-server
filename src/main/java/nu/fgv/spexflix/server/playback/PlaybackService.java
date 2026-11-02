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

package nu.fgv.spexflix.server.playback;

import nu.fgv.spexflix.server.config.SpexflixProperties;
import nu.fgv.spexflix.server.user.AppUser;
import nu.fgv.spexflix.server.user.Caller;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.util.error.BadRequestException;
import nu.fgv.spexflix.server.video.Video;
import nu.fgv.spexflix.server.video.VideoDtos;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import nu.fgv.spexflix.server.video.VideoRepository;
import nu.fgv.spexflix.server.video.VideoService;
import nu.fgv.spexflix.server.video.VideoStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Playing videos: signed addresses, progress, the watch list and ratings. Ratings are each user's own, 1 to 5;
 * every listed video also shows the average of everyone's.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class PlaybackService {

    /**
     * How close to the end a video counts as watched.
     */
    private static final double FINISHED = 0.95;

    private final VideoService videoService;
    private final VideoRepository videoRepository;
    private final WatchProgressRepository progressRepository;
    private final WatchListEntryRepository watchListRepository;
    private final VideoRatingRepository ratingRepository;
    private final CallerService callerService;
    private final PlaybackSigner signer;
    private final Duration margin;
    private final Clock clock;

    public PlaybackService(final VideoService videoService, final VideoRepository videoRepository,
                           final WatchProgressRepository progressRepository, final WatchListEntryRepository watchListRepository,
                           final VideoRatingRepository ratingRepository,
                           final CallerService callerService, final PlaybackSigner signer, final SpexflixProperties properties,
                           final Clock clock) {
        this.videoService = videoService;
        this.videoRepository = videoRepository;
        this.progressRepository = progressRepository;
        this.watchListRepository = watchListRepository;
        this.ratingRepository = ratingRepository;
        this.callerService = callerService;
        this.signer = signer;
        this.margin = properties.media().linkMargin();
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PlaybackDto playback(final UUID videoId) {
        final Video video = videoService.visible(videoId);

        if (video.getStatus() != VideoStatus.READY && video.getRenditions().isEmpty()) {
            throw new BadRequestException("error.video.notReady");
        }

        final Instant expires = clock.instant().plus(Duration.ofMillis(Objects.requireNonNullElse(video.getDurationMs(), 0L))).plus(margin);
        final String prefix = signer.prefix(video.getUuid().toString(), expires);

        return new PlaybackDto(prefix + video.getGeneration() + "/master.m3u8",
                video.isHasThumbnails() ? prefix + video.getGeneration() + "/thumbs/thumbs.vtt" : null,
                video.getSubtitles().stream()
                        .map(subtitle -> new PlaybackSubtitleDto(subtitle.getLanguage(), subtitle.getLabel(),
                                prefix + "subtitles/" + subtitle.getLanguage() + ".vtt"))
                        .toList(),
                expires);
    }

    @Transactional
    public void saveProgress(final UUID videoId, final int positionSeconds) {
        final Video video = videoService.visible(videoId);
        final AppUser user = currentUser();
        final WatchProgress progress = progressRepository.findByUserIdAndVideoId(user.getId(), video.getId()).orElseGet(() -> {
            final WatchProgress created = new WatchProgress();

            created.setUserId(user.getId());
            created.setVideoId(video.getId());
            return created;
        });
        final long durationSeconds = Objects.requireNonNullElse(video.getDurationMs(), 0L) / 1000;
        final int position = (int) Math.clamp(positionSeconds, 0, Math.max(durationSeconds, 0));

        progress.setPositionSeconds(position);
        progress.setFinished(durationSeconds > 0 && position >= durationSeconds * FINISHED);
        progress.setUpdatedAt(clock.instant());
        progressRepository.save(progress);
    }

    @Transactional(readOnly = true)
    public List<VideoDto> continueWatching(final int limit) {
        final AppUser user = currentUser();
        final List<Long> ids = progressRepository.findByUserIdAndFinishedFalseOrderByUpdatedAtDesc(user.getId()).stream()
                .filter(progress -> progress.getPositionSeconds() > 0)
                .map(WatchProgress::getVideoId)
                .toList();

        return listedInOrder(ids).stream().limit(Math.clamp(limit, 1, 50)).toList();
    }

    @Transactional(readOnly = true)
    public List<VideoDto> watchList() {
        final AppUser user = currentUser();

        return listedInOrder(watchListRepository.findByUserIdOrderByAddedAtDesc(user.getId()).stream().map(WatchListEntry::getVideoId).toList());
    }

    @Transactional
    public void watchListAdd(final UUID videoId) {
        final Video video = videoService.visible(videoId);
        final AppUser user = currentUser();

        if (watchListRepository.findByUserIdAndVideoId(user.getId(), video.getId()).isEmpty()) {
            final WatchListEntry entry = new WatchListEntry();

            entry.setUserId(user.getId());
            entry.setVideoId(video.getId());
            entry.setAddedAt(clock.instant());
            watchListRepository.save(entry);
        }
    }

    @Transactional
    public void watchListRemove(final UUID videoId) {
        final AppUser user = currentUser();

        videoRepository.findByUuid(videoId)
                .flatMap(video -> watchListRepository.findByUserIdAndVideoId(user.getId(), video.getId()))
                .ifPresent(watchListRepository::delete);
    }

    /**
     * Rates a video from 1 to 5; 0 removes the caller's rating.
     */
    @Transactional
    public void rate(final UUID videoId, final int rating) {
        if (rating < 0 || rating > 5) {
            throw new BadRequestException("error.rating.invalid");
        }

        final Video video = videoService.visible(videoId);
        final AppUser user = currentUser();
        final Optional<VideoRating> existing = ratingRepository.findByUserIdAndVideoId(user.getId(), video.getId());

        if (rating == 0) {
            existing.ifPresent(ratingRepository::delete);
            return;
        }

        final VideoRating entry = existing.orElseGet(() -> {
            final VideoRating created = new VideoRating();

            created.setUserId(user.getId());
            created.setVideoId(video.getId());
            return created;
        });

        entry.setRating(rating);
        entry.setRatedAt(clock.instant());
        ratingRepository.save(entry);
    }

    /**
     * The listed videos the caller rated, highest first, then most recently rated.
     */
    @Transactional(readOnly = true)
    public List<VideoDto> highestRated(final int limit) {
        final AppUser user = currentUser();

        return listedInOrder(ratingRepository.findByUserIdOrderByRatingDescRatedAtDesc(user.getId()).stream()
                .map(VideoRating::getVideoId).toList()).stream().limit(Math.clamp(limit, 1, 50)).toList();
    }

    /**
     * Everyone's average rating and number of ratings for some videos; videos nobody rated are left out.
     */
    @Transactional(readOnly = true)
    public Map<UUID, RatingSummary> ratingSummaries(final Collection<UUID> videoIds) {
        if (videoIds.isEmpty()) {
            return Map.of();
        }

        final Map<Long, UUID> uuids = videoRepository.findByUuidIn(videoIds).stream()
                .collect(Collectors.toMap(Video::getId, Video::getUuid));
        final Map<UUID, RatingSummary> summaries = new HashMap<>();

        if (uuids.isEmpty()) {
            return summaries;
        }
        for (final Object[] row : ratingRepository.summarize(uuids.keySet())) {
            summaries.put(uuids.get((Long) row[0]), new RatingSummary(((Number) row[1]).doubleValue(), ((Number) row[2]).intValue()));
        }
        return summaries;
    }

    /**
     * The caller's progress, watch list and ratings for some videos, for the list fields of {@code Video}.
     */
    @Transactional
    public PersonalState personal(final Collection<UUID> videoIds) {
        final Caller caller = callerService.currentIfAny().orElse(null);

        if (caller == null || !caller.isUser() || videoIds.isEmpty()) {
            return new PersonalState(Map.of(), Set.of(), Map.of());
        }

        final AppUser user = callerService.record(caller);
        final Map<Long, UUID> uuids = videoRepository.findByUuidIn(videoIds).stream()
                .collect(Collectors.toMap(Video::getId, Video::getUuid));
        final Map<UUID, WatchProgress> progress = new HashMap<>();

        progressRepository.findByUserIdAndVideoIdIn(user.getId(), uuids.keySet()).forEach(entry -> progress.put(uuids.get(entry.getVideoId()), entry));
        final Map<UUID, Integer> ratings = new HashMap<>();

        ratingRepository.findByUserIdAndVideoIdIn(user.getId(), uuids.keySet()).forEach(entry -> ratings.put(uuids.get(entry.getVideoId()), entry.getRating()));
        return new PersonalState(progress, watchListRepository.findByUserIdAndVideoIdIn(user.getId(), uuids.keySet()).stream()
                .map(entry -> uuids.get(entry.getVideoId())).collect(Collectors.toSet()), ratings);
    }

    private List<VideoDto> listedInOrder(final List<Long> ids) {
        final Map<Long, Video> videos = videoRepository.findAllById(ids).stream()
                .filter(Video::isListed)
                .collect(Collectors.toMap(Video::getId, video -> video));

        return ids.stream().map(videos::get).filter(Objects::nonNull).map(VideoDtos::toDto).toList();
    }

    private AppUser currentUser() {
        final Caller caller = callerService.current();

        caller.requireUser();
        return callerService.record(caller);
    }

    public record PlaybackDto(String masterUrl, @Nullable String thumbnailsUrl, List<PlaybackSubtitleDto> subtitles,
                              Instant expiresAt) {
    }

    public record PlaybackSubtitleDto(String language, String label, String url) {
    }

    public record PersonalState(Map<UUID, WatchProgress> progress, Set<UUID> watchList, Map<UUID, Integer> ratings) {
    }

    public record RatingSummary(double average, int count) {
    }
}

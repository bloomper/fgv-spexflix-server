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
import nu.fgv.spexflix.server.video.VideoDtos.VideoFilter;
import nu.fgv.spexflix.server.video.VideoDtos.VideoSort;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Playing videos: signed addresses, progress, the watch list, likes and ratings. Ratings are each user's own, 1 to 5;
 * every listed video also shows the average of everyone's, and the top lists rank videos by everyone's ratings, likes
 * and views.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class PlaybackService {

    /**
     * How many average votes a video's rating is weighed against in the top lists, so that a single 5 does not top
     * them: the weighted average is {@code (PRIOR * mean + sum) / (PRIOR + count)}, where mean is the average of every
     * rating.
     */
    static final int PRIOR = 2;
    /**
     * How close to the end a video counts as watched.
     */
    private static final double FINISHED = 0.95;
    private static final int MAX_TOP = 100;

    private final VideoService videoService;
    private final VideoRepository videoRepository;
    private final WatchProgressRepository progressRepository;
    private final WatchListEntryRepository watchListRepository;
    private final VideoRatingRepository ratingRepository;
    private final VideoLikeRepository likeRepository;
    private final CallerService callerService;
    private final PlaybackSigner signer;
    private final Duration margin;
    private final Clock clock;

    public PlaybackService(final VideoService videoService, final VideoRepository videoRepository,
                           final WatchProgressRepository progressRepository, final WatchListEntryRepository watchListRepository,
                           final VideoRatingRepository ratingRepository, final VideoLikeRepository likeRepository,
                           final CallerService callerService, final PlaybackSigner signer, final SpexflixProperties properties,
                           final Clock clock) {
        this.videoService = videoService;
        this.videoRepository = videoRepository;
        this.progressRepository = progressRepository;
        this.watchListRepository = watchListRepository;
        this.ratingRepository = ratingRepository;
        this.likeRepository = likeRepository;
        this.callerService = callerService;
        this.signer = signer;
        this.margin = properties.media().linkMargin();
        this.clock = clock;
    }

    /**
     * Weighted averages from rows of id, sum and count: each average drawn towards the mean of all ratings.
     */
    static Map<Long, Double> weighted(final List<Object[]> totals) {
        double sum = 0;
        long count = 0;

        for (final Object[] row : totals) {
            sum += ((Number) row[1]).doubleValue();
            count += ((Number) row[2]).longValue();
        }

        final double mean = count == 0 ? 0 : sum / count;
        final Map<Long, Double> scores = new HashMap<>();

        for (final Object[] row : totals) {
            scores.put((Long) row[0], (PRIOR * mean + ((Number) row[1]).doubleValue()) / (PRIOR + ((Number) row[2]).doubleValue()));
        }
        return scores;
    }

    private static Map<Long, Double> counts(final List<Object[]> rows) {
        final Map<Long, Double> counts = new HashMap<>();

        rows.forEach(row -> counts.put((Long) row[0], ((Number) row[1]).doubleValue()));
        return counts;
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

    /**
     * Forgets where the caller is in a video: off continue watching, and unwatched.
     */
    @Transactional
    public void clearProgress(final UUID videoId) {
        final AppUser user = currentUser();

        videoRepository.findByUuid(videoId)
                .flatMap(video -> progressRepository.findByUserIdAndVideoId(user.getId(), video.getId()))
                .ifPresent(progressRepository::delete);
    }

    /**
     * Marks a video as watched to the end, at position 0 so that it starts from the beginning the next time.
     */
    @Transactional
    public void markWatched(final UUID videoId) {
        final Video video = videoService.visible(videoId);
        final AppUser user = currentUser();
        final WatchProgress progress = progressRepository.findByUserIdAndVideoId(user.getId(), video.getId()).orElseGet(() -> {
            final WatchProgress created = new WatchProgress();

            created.setUserId(user.getId());
            created.setVideoId(video.getId());
            return created;
        });

        progress.setPositionSeconds(0);
        progress.setFinished(true);
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
     * Likes a video, or takes the like back.
     */
    @Transactional
    public void like(final UUID videoId, final boolean liked) {
        final AppUser user = currentUser();

        if (!liked) {
            videoRepository.findByUuid(videoId)
                    .flatMap(video -> likeRepository.findByUserIdAndVideoId(user.getId(), video.getId()))
                    .ifPresent(likeRepository::delete);
            return;
        }

        final Video video = videoService.visible(videoId);

        if (likeRepository.findByUserIdAndVideoId(user.getId(), video.getId()).isEmpty()) {
            final VideoLike like = new VideoLike();

            like.setUserId(user.getId());
            like.setVideoId(video.getId());
            like.setLikedAt(clock.instant());
            likeRepository.save(like);
        }
    }

    /**
     * The listed videos the caller likes, most recently liked first.
     */
    @Transactional(readOnly = true)
    public List<VideoDto> likedVideos() {
        final AppUser user = currentUser();

        return listedInOrder(likeRepository.findByUserIdOrderByLikedAtDesc(user.getId()).stream().map(VideoLike::getVideoId).toList());
    }

    /**
     * How many like each of some videos; videos nobody likes are left out.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> likeCounts(final Collection<UUID> videoIds) {
        return countsOf(videoIds, likeRepository::count);
    }

    /**
     * How many have watched each of some videos to the end; videos nobody finished are left out.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> watchCounts(final Collection<UUID> videoIds) {
        return countsOf(videoIds, progressRepository::countFinished);
    }

    private Map<UUID, Integer> countsOf(final Collection<UUID> videoIds,
                                        final Function<Collection<Long>, List<Object[]>> query) {
        if (videoIds.isEmpty()) {
            return Map.of();
        }

        final Map<Long, UUID> uuids = videoRepository.findByUuidIn(videoIds).stream()
                .collect(Collectors.toMap(Video::getId, Video::getUuid));
        final Map<UUID, Integer> counts = new HashMap<>();

        if (!uuids.isEmpty()) {
            for (final Object[] row : query.apply(uuids.keySet())) {
                counts.put(uuids.get((Long) row[0]), ((Number) row[1]).intValue());
            }
        }
        return counts;
    }

    /**
     * The videos the filter lists, also narrowed to what the caller likes or has not watched, and in the asked order.
     */
    @Transactional
    public List<VideoDto> browse(final @Nullable VideoFilter filter) {
        List<VideoDto> videos = videoService.videos(filter);

        if (filter == null) {
            return videos;
        }
        if (Boolean.TRUE.equals(filter.liked()) || Boolean.TRUE.equals(filter.unwatched())) {
            final AppUser user = currentUser();
            final Map<UUID, Long> ids = ids(videos);
            final Set<Long> liked = Boolean.TRUE.equals(filter.liked()) && !ids.isEmpty()
                    ? likeRepository.findByUserIdAndVideoIdIn(user.getId(), ids.values()).stream().map(VideoLike::getVideoId).collect(Collectors.toSet())
                    : Set.of();
            final Set<Long> finished = Boolean.TRUE.equals(filter.unwatched())
                    ? progressRepository.findByUserIdAndFinishedTrue(user.getId()).stream().map(WatchProgress::getVideoId).collect(Collectors.toSet())
                    : Set.of();

            videos = videos.stream()
                    .filter(video -> !Boolean.TRUE.equals(filter.liked()) || liked.contains(ids.get(video.id())))
                    .filter(video -> !Boolean.TRUE.equals(filter.unwatched()) || !finished.contains(ids.get(video.id())))
                    .toList();
        }
        return sort(videos, filter.sort());
    }

    /**
     * The listed videos the filter lists, ranked by everyone: by weighted average rating, by how many like them or by
     * how many have watched them to the end. Videos without any are left out.
     */
    @Transactional(readOnly = true)
    public List<VideoDto> top(final TopBy by, final @Nullable VideoFilter filter, final int limit) {
        final List<VideoDto> candidates = videoService.videos(filter == null ? null
                : new VideoFilter(filter.categoryId(), filter.year(), filter.spexId(), filter.kindId(), false,
                filter.fromYear(), filter.toYear(), null, null, null));
        final Map<UUID, Long> ids = ids(candidates);
        final Map<Long, Double> scores = switch (by) {
            case RATED -> ratingScores();
            case LIKED -> counts(likeRepository.countAll());
            case WATCHED -> counts(progressRepository.countFinished());
        };

        return candidates.stream()
                .filter(video -> scores.containsKey(ids.get(video.id())))
                .sorted(Comparator.comparingDouble((VideoDto video) -> scores.get(ids.get(video.id()))).reversed())
                .limit(Math.clamp(limit, 1, MAX_TOP))
                .toList();
    }

    private List<VideoDto> sort(final List<VideoDto> videos, final @Nullable VideoSort sort) {
        if (sort == null || sort == VideoSort.YEAR_DESC) {
            return videos;
        }

        final Comparator<VideoDto> order = switch (sort) {
            case YEAR_DESC -> (first, second) -> 0;
            case YEAR_ASC -> Comparator.comparing(VideoDto::year, Comparator.nullsLast(Comparator.naturalOrder()));
            case TITLE -> Comparator.comparing(video -> video.title().toLowerCase(java.util.Locale.ROOT));
            case PUBLISHED ->
                    Comparator.comparing(VideoDto::publishedAt, Comparator.nullsLast(Comparator.reverseOrder()));
            case RATING -> byScore(videos, ratingScores());
            case LIKES -> byScore(videos, counts(likeRepository.countAll()));
        };

        // The list comes newest year first, then by title, which a stable sort keeps for ties
        return videos.stream().sorted(order).toList();
    }

    private Comparator<VideoDto> byScore(final List<VideoDto> videos, final Map<Long, Double> scores) {
        final Map<UUID, Long> ids = ids(videos);

        return Comparator.comparingDouble((VideoDto video) -> scores.getOrDefault(ids.get(video.id()), -1.0)).reversed();
    }

    /**
     * Every rated video's weighted average rating, by video id.
     */
    private Map<Long, Double> ratingScores() {
        return weighted(ratingRepository.totals());
    }

    private Map<UUID, Long> ids(final List<VideoDto> videos) {
        if (videos.isEmpty()) {
            return Map.of();
        }
        return videoRepository.findByUuidIn(videos.stream().map(VideoDto::id).toList()).stream()
                .collect(Collectors.toMap(Video::getUuid, Video::getId));
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
     * The caller's progress, watch list, ratings and likes for some videos, for the list fields of {@code Video}.
     */
    @Transactional
    public PersonalState personal(final Collection<UUID> videoIds) {
        final Caller caller = callerService.currentIfAny().orElse(null);

        if (caller == null || !caller.isUser() || videoIds.isEmpty()) {
            return new PersonalState(Map.of(), Set.of(), Map.of(), Set.of());
        }

        final AppUser user = callerService.record(caller);
        final Map<Long, UUID> uuids = videoRepository.findByUuidIn(videoIds).stream()
                .collect(Collectors.toMap(Video::getId, Video::getUuid));
        final Map<UUID, WatchProgress> progress = new HashMap<>();

        progressRepository.findByUserIdAndVideoIdIn(user.getId(), uuids.keySet()).forEach(entry -> progress.put(uuids.get(entry.getVideoId()), entry));
        final Map<UUID, Integer> ratings = new HashMap<>();

        ratingRepository.findByUserIdAndVideoIdIn(user.getId(), uuids.keySet()).forEach(entry -> ratings.put(uuids.get(entry.getVideoId()), entry.getRating()));
        return new PersonalState(progress, watchListRepository.findByUserIdAndVideoIdIn(user.getId(), uuids.keySet()).stream()
                .map(entry -> uuids.get(entry.getVideoId())).collect(Collectors.toSet()), ratings,
                likeRepository.findByUserIdAndVideoIdIn(user.getId(), uuids.keySet()).stream()
                        .map(entry -> uuids.get(entry.getVideoId())).collect(Collectors.toSet()));
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

    /**
     * What a top list ranks by.
     */
    public enum TopBy {
        /**
         * Everyone's ratings, as a weighted average.
         */
        RATED,
        /**
         * How many like the video.
         */
        LIKED,
        /**
         * How many have watched it to the end.
         */
        WATCHED
    }

    public record PlaybackDto(String masterUrl, @Nullable String thumbnailsUrl, List<PlaybackSubtitleDto> subtitles,
                              Instant expiresAt) {
    }

    public record PlaybackSubtitleDto(String language, String label, String url) {
    }

    public record PersonalState(Map<UUID, WatchProgress> progress, Set<UUID> watchList, Map<UUID, Integer> ratings,
                                Set<UUID> likes) {
    }

    public record RatingSummary(double average, int count) {
    }
}

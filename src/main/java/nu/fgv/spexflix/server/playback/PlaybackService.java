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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Playing videos: signed addresses, progress and the watch list.
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
    private final CallerService callerService;
    private final PlaybackSigner signer;
    private final Duration margin;
    private final Clock clock;

    public PlaybackService(final VideoService videoService, final VideoRepository videoRepository,
                           final WatchProgressRepository progressRepository, final WatchListEntryRepository watchListRepository,
                           final CallerService callerService, final PlaybackSigner signer, final SpexflixProperties properties,
                           final Clock clock) {
        this.videoService = videoService;
        this.videoRepository = videoRepository;
        this.progressRepository = progressRepository;
        this.watchListRepository = watchListRepository;
        this.callerService = callerService;
        this.signer = signer;
        this.margin = properties.media().linkMargin();
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PlaybackDto playback(final Long videoId) {
        final Video video = videoService.visible(videoId);

        if (video.getStatus() != VideoStatus.READY && video.getRenditions().isEmpty()) {
            throw new BadRequestException("error.video.notReady");
        }

        final Instant expires = clock.instant().plus(Duration.ofMillis(Objects.requireNonNullElse(video.getDurationMs(), 0L))).plus(margin);
        final String prefix = signer.prefix(video.getUuid(), expires);

        return new PlaybackDto(prefix + video.getGeneration() + "/master.m3u8",
                video.isHasThumbnails() ? prefix + video.getGeneration() + "/thumbs/thumbs.vtt" : null,
                video.getSubtitles().stream()
                        .map(subtitle -> new PlaybackSubtitleDto(subtitle.getLanguage(), subtitle.getLabel(),
                                prefix + "subtitles/" + subtitle.getLanguage() + ".vtt"))
                        .toList(),
                expires);
    }

    @Transactional
    public void saveProgress(final Long videoId, final int positionSeconds) {
        final Video video = videoService.visible(videoId);
        final AppUser user = currentUser();
        final WatchProgress progress = progressRepository.findByUserIdAndVideoId(user.getId(), videoId).orElseGet(() -> {
            final WatchProgress created = new WatchProgress();

            created.setUserId(user.getId());
            created.setVideoId(videoId);
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
    public void watchListAdd(final Long videoId) {
        videoService.visible(videoId);

        final AppUser user = currentUser();

        if (watchListRepository.findByUserIdAndVideoId(user.getId(), videoId).isEmpty()) {
            final WatchListEntry entry = new WatchListEntry();

            entry.setUserId(user.getId());
            entry.setVideoId(videoId);
            entry.setAddedAt(clock.instant());
            watchListRepository.save(entry);
        }
    }

    @Transactional
    public void watchListRemove(final Long videoId) {
        final AppUser user = currentUser();

        watchListRepository.findByUserIdAndVideoId(user.getId(), videoId).ifPresent(watchListRepository::delete);
    }

    /**
     * The caller's progress and watch list for some videos, for the list fields of {@code Video}.
     */
    @Transactional
    public PersonalState personal(final Collection<Long> videoIds) {
        final Caller caller = callerService.currentIfAny().orElse(null);

        if (caller == null || !caller.isUser() || videoIds.isEmpty()) {
            return new PersonalState(Map.of(), Set.of());
        }

        final AppUser user = callerService.record(caller);
        final Map<Long, WatchProgress> progress = new HashMap<>();

        progressRepository.findByUserIdAndVideoIdIn(user.getId(), videoIds).forEach(entry -> progress.put(entry.getVideoId(), entry));
        return new PersonalState(progress, watchListRepository.findByUserIdAndVideoIdIn(user.getId(), videoIds).stream()
                .map(WatchListEntry::getVideoId).collect(Collectors.toSet()));
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

    public record PersonalState(Map<Long, WatchProgress> progress, Set<Long> watchList) {
    }
}

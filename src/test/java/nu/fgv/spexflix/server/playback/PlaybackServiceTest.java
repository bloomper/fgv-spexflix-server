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

import nu.fgv.spexflix.server.TestCallers;
import nu.fgv.spexflix.server.config.SpexflixProperties;
import nu.fgv.spexflix.server.user.AppUser;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.util.error.BadRequestException;
import nu.fgv.spexflix.server.video.Rendition;
import nu.fgv.spexflix.server.video.Subtitle;
import nu.fgv.spexflix.server.video.Video;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import nu.fgv.spexflix.server.video.VideoKind;
import nu.fgv.spexflix.server.video.VideoRepository;
import nu.fgv.spexflix.server.video.VideoService;
import nu.fgv.spexflix.server.video.VideoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static nu.fgv.spexflix.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Signed playback addresses, where viewers are in videos, and their watch lists.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class PlaybackServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    @Mock
    private VideoService videoService;
    @Mock
    private VideoRepository videoRepository;
    @Mock
    private WatchProgressRepository progressRepository;
    @Mock
    private WatchListEntryRepository watchListRepository;
    @Mock
    private VideoRatingRepository ratingRepository;
    @Mock
    private CallerService callerService;
    private PlaybackService service;
    private Video video;
    private AppUser tina;

    @BeforeEach
    void setUp() {
        final SpexflixProperties properties = new SpexflixProperties(null, null, null,
                new SpexflixProperties.Media(Path.of("/media"), DataSize.ofGigabytes(1), "https://media.test", "key", Duration.ofHours(4)),
                null, null, null, null);

        service = new PlaybackService(videoService, videoRepository, progressRepository, watchListRepository, ratingRepository, callerService,
                new PlaybackSigner(properties), properties, Clock.fixed(NOW, ZoneOffset.UTC));
        video = new Video();
        video.setId(1L);
        video.setUuid(uuid(1));
        video.setTitle("Cleopatra");
        video.setKind(new VideoKind());
        video.getKind().setUuid(uuid(101));
        video.getKind().setNameSv("Föreställning");
        video.getKind().setNameEn("Show");
        video.setStatus(VideoStatus.READY);
        video.setPublished(true);
        video.setDurationMs(600_000L);
        video.setGeneration(2);
        tina = new AppUser();
        tina.setId(5L);
        lenient().when(videoService.visible(uuid(1))).thenReturn(video);
        lenient().when(callerService.current()).thenReturn(TestCallers.viewer());
        lenient().when(callerService.record(any())).thenReturn(tina);
    }

    @Test
    void playbackIsSignedForTheVideosLengthPlusTheMargin() {
        final Subtitle subtitle = new Subtitle();

        subtitle.setLanguage("sv");
        subtitle.setLabel("Svenska");
        video.getSubtitles().add(subtitle);
        video.setHasThumbnails(true);

        final PlaybackService.PlaybackDto playback = service.playback(uuid(1));

        assertThat(playback.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)).plus(Duration.ofHours(4)));
        assertThat(playback.masterUrl()).startsWith("https://media.test/v/" + playback.expiresAt().getEpochSecond() + "/")
                .endsWith("/" + video.getUuid() + "/2/master.m3u8");
        assertThat(playback.thumbnailsUrl()).endsWith("/2/thumbs/thumbs.vtt");
        assertThat(playback.subtitles()).singleElement().satisfies(sub -> assertThat(sub.url()).endsWith("/subtitles/sv.vtt"));
    }

    @Test
    void aVideoWithoutRenditionsCannotBePlayedButAReplacementInProgressCan() {
        video.setStatus(VideoStatus.PROCESSING);
        assertThatThrownBy(() -> service.playback(uuid(1))).isInstanceOf(BadRequestException.class);

        video.getRenditions().add(new Rendition());
        assertThat(service.playback(uuid(1)).thumbnailsUrl()).isNull();
    }

    @Test
    void progressIsKeptWithinTheVideoAndCountsAsFinishedNearTheEnd() {
        final ArgumentCaptor<WatchProgress> saved = ArgumentCaptor.forClass(WatchProgress.class);

        when(progressRepository.findByUserIdAndVideoId(5L, 1L)).thenReturn(Optional.empty());
        service.saveProgress(uuid(1), 100);
        service.saveProgress(uuid(1), 590);
        service.saveProgress(uuid(1), 9999);
        verify(progressRepository, org.mockito.Mockito.times(3)).save(saved.capture());

        assertThat(saved.getAllValues()).extracting(WatchProgress::getPositionSeconds).containsExactly(100, 590, 600);
        assertThat(saved.getAllValues()).extracting(WatchProgress::isFinished).containsExactly(false, true, true);
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void continueWatchingListsStartedVideosThatAreStillListed() {
        final WatchProgress started = new WatchProgress();
        final WatchProgress atStart = new WatchProgress();
        final Video gone = new Video();

        started.setVideoId(1L);
        started.setPositionSeconds(30);
        atStart.setVideoId(2L);
        gone.setId(3L);
        final WatchProgress unpublished = new WatchProgress();

        unpublished.setVideoId(3L);
        unpublished.setPositionSeconds(5);
        when(progressRepository.findByUserIdAndFinishedFalseOrderByUpdatedAtDesc(5L)).thenReturn(List.of(unpublished, started, atStart));
        when(videoRepository.findAllById(List.of(3L, 1L))).thenReturn(List.of(gone, video));

        assertThat(service.continueWatching(10)).extracting(VideoDto::title).containsExactly("Cleopatra");
    }

    @Test
    void theWatchListIsAddedToOnceAndRemovedFrom() {
        final WatchListEntry entry = new WatchListEntry();

        when(watchListRepository.findByUserIdAndVideoId(5L, 1L)).thenReturn(Optional.empty()).thenReturn(Optional.of(entry));
        service.watchListAdd(uuid(1));
        service.watchListAdd(uuid(1));
        verify(watchListRepository, org.mockito.Mockito.times(1)).save(any());

        when(videoRepository.findByUuid(uuid(1))).thenReturn(Optional.of(video));
        service.watchListRemove(uuid(1));
        verify(watchListRepository).delete(entry);
    }

    @Test
    void personalStateIsEmptyWithoutAUser() {
        when(callerService.currentIfAny()).thenReturn(Optional.empty());

        assertThat(service.personal(Set.of(uuid(1))).watchList()).isEmpty();
        verify(progressRepository, never()).findByUserIdAndVideoIdIn(any(), any());
    }

    @Test
    void aRatingIsSavedChangedAndRemoved() {
        final VideoRating existing = new VideoRating();

        when(ratingRepository.findByUserIdAndVideoId(5L, 1L)).thenReturn(Optional.empty()).thenReturn(Optional.of(existing));
        service.rate(uuid(1), 4);
        verify(ratingRepository).save(argThat(rating ->
                rating.getUserId() == 5L && rating.getVideoId() == 1L && rating.getRating() == 4 && NOW.equals(rating.getRatedAt())));

        service.rate(uuid(1), 0);
        verify(ratingRepository).delete(existing);
    }

    @Test
    void aRatingIsOneToFive() {
        assertThatThrownBy(() -> service.rate(uuid(1), 6)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.rate(uuid(1), -1)).isInstanceOf(BadRequestException.class);
        verify(ratingRepository, never()).save(any());
    }

    @Test
    void everyonesRatingsAreAveraged() {
        when(videoRepository.findByUuidIn(List.of(uuid(1)))).thenReturn(List.of(video));
        when(ratingRepository.summarize(Set.of(1L))).thenReturn(List.<Object[]>of(new Object[]{1L, 3.5d, 2L}));

        assertThat(service.ratingSummaries(List.of(uuid(1)))).containsEntry(uuid(1), new PlaybackService.RatingSummary(3.5, 2));
    }

    @Test
    void markingAVideoWatchedStartsItFromTheBeginningNextTime() {
        final WatchProgress existing = new WatchProgress();

        existing.setPositionSeconds(300);
        when(progressRepository.findByUserIdAndVideoId(5L, 1L)).thenReturn(Optional.of(existing));
        service.markWatched(uuid(1));

        assertThat(existing.isFinished()).isTrue();
        assertThat(existing.getPositionSeconds()).isZero();
        assertThat(existing.getUpdatedAt()).isEqualTo(NOW);
        verify(progressRepository).save(existing);
    }

    @Test
    void clearingProgressForgetsWhereTheViewerWas() {
        final WatchProgress existing = new WatchProgress();

        when(videoRepository.findByUuid(uuid(1))).thenReturn(Optional.of(video));
        when(progressRepository.findByUserIdAndVideoId(5L, 1L)).thenReturn(Optional.of(existing));
        service.clearProgress(uuid(1));

        verify(progressRepository).delete(existing);
    }
}

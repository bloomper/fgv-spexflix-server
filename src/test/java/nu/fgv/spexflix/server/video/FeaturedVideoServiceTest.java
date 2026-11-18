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

import nu.fgv.spexflix.server.TestCallers;
import nu.fgv.spexflix.server.settings.Settings;
import nu.fgv.spexflix.server.settings.SettingsService;
import nu.fgv.spexflix.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static nu.fgv.spexflix.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Which video the start page features: the picked one while it is listed, else the newest show, else the newest video.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class FeaturedVideoServiceTest {

    private static final VideoKind SHOW = VideoServiceTest.kind(1, "Föreställning", "Show");
    private static final VideoKind TRAILER = VideoServiceTest.kind(2, "Trailer", "Trailer");

    @Mock
    private VideoRepository videoRepository;
    @Mock
    private VideoKindRepository kindRepository;
    @Mock
    private SettingsService settingsService;
    @Mock
    private CallerService callerService;
    private final Settings settings = new Settings();
    private FeaturedVideoService service;

    private static Video video(final long id, final VideoKind kind, final @Nullable Integer year, final String publishedAt) {
        final Video video = new Video();

        video.setId(id);
        video.setUuid(uuid(id));
        video.setTitle("Video " + id);
        video.setYear(year);
        video.setKind(kind);
        video.setStatus(VideoStatus.READY);
        video.setPublished(true);
        video.setPublishedAt(Instant.parse(publishedAt));
        return video;
    }

    @BeforeEach
    void setUp() {
        service = new FeaturedVideoService(videoRepository, kindRepository, settingsService, callerService);
        lenient().when(callerService.current()).thenReturn(TestCallers.viewer());
        lenient().when(settingsService.current()).thenReturn(settings);
        lenient().when(kindRepository.findFirstByOrderByPositionAscIdAsc()).thenReturn(Optional.of(SHOW));
    }

    @Test
    void thePickedVideoIsFeaturedWhileItIsListed() {
        final Video picked = video(5, TRAILER, 2001, "2026-01-01T10:00:00Z");

        settings.setFeaturedVideoId(uuid(5));
        when(videoRepository.findByUuid(uuid(5))).thenReturn(Optional.of(picked));

        assertThat(service.featured()).extracting(VideoDtos.VideoDto::id).isEqualTo(uuid(5));

        picked.setPublished(false);
        when(videoRepository.findListed()).thenReturn(List.of(video(6, SHOW, 2020, "2026-01-01T10:00:00Z")));

        assertThat(service.featured()).extracting(VideoDtos.VideoDto::id).isEqualTo(uuid(6));
    }

    @Test
    void withoutAPickTheNewestShowByYearThenPublishingIsFeatured() {
        when(videoRepository.findListed()).thenReturn(List.of(
                video(1, SHOW, 2024, "2026-01-01T10:00:00Z"),
                video(2, TRAILER, 2026, "2026-03-01T10:00:00Z"),
                video(3, SHOW, 2025, "2026-01-01T10:00:00Z"),
                video(4, SHOW, 2025, "2026-02-01T10:00:00Z"),
                video(7, SHOW, null, "2026-04-01T10:00:00Z")));

        assertThat(service.featured()).extracting(VideoDtos.VideoDto::id).isEqualTo(uuid(4));
    }

    @Test
    void withoutAShowTheNewestVideoIsFeaturedAndWithoutVideosNone() {
        when(videoRepository.findListed()).thenReturn(List.of(video(2, TRAILER, 2026, "2026-03-01T10:00:00Z")));
        when(videoRepository.findRecent(any(Pageable.class))).thenReturn(List.of(video(2, TRAILER, 2026, "2026-03-01T10:00:00Z")));

        assertThat(service.featured()).extracting(VideoDtos.VideoDto::id).isEqualTo(uuid(2));

        when(videoRepository.findListed()).thenReturn(List.of());
        when(videoRepository.findRecent(any(Pageable.class))).thenReturn(List.of());

        assertThat(service.featured()).isNull();
    }

    @Test
    void onlyUsersSeeTheFeaturedVideo() {
        when(callerService.current()).thenReturn(TestCallers.noAccess());

        assertThatThrownBy(() -> service.featured()).isInstanceOf(AccessDeniedException.class);
    }
}

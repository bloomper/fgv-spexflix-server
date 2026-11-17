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

import nu.fgv.spexflix.server.settings.SettingsService;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * The video at the top of the start page: the one an admin picked while it is listed, else the newest show (the
 * newest listed video of the default kind, by year and then by when it was published), else the most recently
 * published video.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class FeaturedVideoService {

    private static final Comparator<Video> NEWEST = Comparator
            .comparing(Video::getYear, Comparator.nullsFirst(Comparator.<Integer>naturalOrder()))
            .thenComparing(Video::getPublishedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()));

    private final VideoRepository videoRepository;
    private final VideoKindRepository kindRepository;
    private final SettingsService settingsService;
    private final CallerService callerService;

    public FeaturedVideoService(final VideoRepository videoRepository, final VideoKindRepository kindRepository,
                                final SettingsService settingsService, final CallerService callerService) {
        this.videoRepository = videoRepository;
        this.kindRepository = kindRepository;
        this.settingsService = settingsService;
        this.callerService = callerService;
    }

    /**
     * The featured video, or null when no video is listed.
     */
    @Transactional(readOnly = true)
    public @Nullable VideoDto featured() {
        callerService.current().requireUser();

        final UUID picked = settingsService.current().getFeaturedVideoId();

        if (picked != null) {
            final Video video = videoRepository.findByUuid(picked).filter(Video::isListed).orElse(null);

            if (video != null) {
                return VideoDtos.toDto(video);
            }
        }

        final UUID defaultKind = kindRepository.findFirstByOrderByPositionAscIdAsc().map(VideoKind::getUuid).orElse(null);

        return videoRepository.findListed().stream()
                .filter(video -> Objects.equals(video.getKind().getUuid(), defaultKind))
                .max(NEWEST)
                .or(() -> videoRepository.findRecent(PageRequest.of(0, 1)).stream().findFirst())
                .map(VideoDtos::toDto)
                .orElse(null);
    }
}

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

package nu.fgv.spexflix.server.spex;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import nu.fgv.spexflix.server.config.CacheConfig;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.util.error.SpexregisterUnavailableException;
import nu.fgv.spexflix.server.video.SpexRef;
import nu.fgv.spexflix.server.video.Video;
import nu.fgv.spexflix.server.video.VideoRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picking a spex for a video, posters, and the daily refresh of the spex titles, years and categories copied onto
 * videos.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class SpexService {

    private static final int MAX_RESULTS = 30;

    private final SpexregisterClient client;
    private final VideoRepository videoRepository;
    private final CallerService callerService;
    private final Clock clock;

    public SpexService(final SpexregisterClient client, final VideoRepository videoRepository,
                       final CallerService callerService, final Clock clock) {
        this.client = client;
        this.videoRepository = videoRepository;
        this.callerService = callerService;
        this.clock = clock;
    }

    /**
     * Spex whose title or year matches, for editors picking the spex of a video.
     */
    public List<SpexDto> search(final String query) {
        callerService.current().requireEditor();

        final String q = query.strip().toLowerCase(Locale.ROOT);

        try {
            return client.allSpex().stream()
                    .filter(spex -> q.isEmpty()
                            || (spex.title() != null && spex.title().toLowerCase(Locale.ROOT).contains(q))
                            || (spex.year() != null && spex.year().toString().equals(q)))
                    .sorted(Comparator.comparing((SpexDto spex) -> spex.year() == null ? 0 : spex.year()).reversed())
                    .limit(MAX_RESULTS)
                    .toList();
        } catch (final SpexregisterClient.SpexregisterCallException e) {
            throw new SpexregisterUnavailableException(e);
        }
    }

    public SpexDto byId(final UUID id) {
        try {
            return client.spexById(id).orElseThrow(() -> new nu.fgv.spexflix.server.util.error.BadRequestException("error.spex.notFound", id));
        } catch (final SpexregisterClient.SpexregisterCallException e) {
            throw new SpexregisterUnavailableException(e);
        }
    }

    @Cacheable(cacheNames = CacheConfig.SPEX_POSTERS)
    public Optional<SpexregisterClient.Poster> poster(final UUID spexId) {
        try {
            return client.poster(spexId);
        } catch (final SpexregisterClient.SpexregisterCallException e) {
            log.debug("Could not read the poster of spex {}", spexId, e);
            return Optional.empty();
        }
    }

    public void copy(final SpexDto spex, final SpexRef ref) {
        ref.setId(spex.id());
        ref.setTitle(spex.title());
        ref.setYear(spex.year());
        ref.setRevival(spex.revival());
        ref.setCategoryId(spex.categoryId());
        ref.setCategoryName(spex.categoryName());
        ref.setRefreshedAt(clock.instant());
    }

    /**
     * Refreshes the copied spex data of every video, at night.
     */
    @Scheduled(cron = "0 15 3 * * *", zone = "Europe/Stockholm")
    @SchedulerLock(name = "spex-refresh", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void refresh() {
        final List<UUID> ids = videoRepository.findSpexIds();

        if (ids.isEmpty()) {
            return;
        }
        try {
            final Map<UUID, SpexDto> spex = client.allSpex().stream().collect(Collectors.toMap(SpexDto::id, Function.identity(), (a, _) -> a));

            for (final Video video : videoRepository.findBySpexIdIn(ids)) {
                final SpexDto current = spex.get(video.getSpex().getId());

                if (current != null) {
                    copy(current, video.getSpex());
                } else if (StringUtils.hasText(video.getSpex().getTitle())) {
                    log.warn("Spex {} of video {} is no longer in Spexregister", video.getSpex().getId(), video.getId());
                }
            }
        } catch (final SpexregisterClient.SpexregisterCallException e) {
            log.warn("Could not refresh spex from Spexregister: {}", e.getMessage());
        }
    }
}

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

import nu.fgv.spexflix.server.audit.AuditService;
import nu.fgv.spexflix.server.user.Caller;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.util.error.BadRequestException;
import nu.fgv.spexflix.server.util.error.ResourceNotFoundException;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Collections of videos, and what plays after a video.
 *
 * <p>Listeners see a collection when at least one of its videos is listed, and only its listed videos. EDITOR creates
 * and changes collections; removing one is a permanent delete and therefore ADMIN's. The videos stay.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class CollectionService {

    private static final int MAX_TITLE = 255;
    private static final int MAX_DESCRIPTION = 4000;

    /**
     * Videos of one spex in the order they are played one after another: by kind, then by performance date and title.
     */
    private static final Comparator<Video> SPEX_ORDER = Comparator
            .comparingInt((Video video) -> video.getKind().getPosition())
            .thenComparing(Video::getPerformanceDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
            .thenComparing(Video::getTitle)
            .thenComparing(Video::getId);

    private final VideoCollectionRepository repository;
    private final VideoRepository videoRepository;
    private final CallerService callerService;
    private final AuditService auditService;

    public CollectionService(final VideoCollectionRepository repository, final VideoRepository videoRepository,
                             final CallerService callerService, final AuditService auditService) {
        this.repository = repository;
        this.videoRepository = videoRepository;
        this.callerService = callerService;
        this.auditService = auditService;
    }

    private static boolean visible(final Video video, final Caller caller) {
        return caller.isEditor() ? !video.isTrashed() : video.isListed();
    }

    private static @Nullable String blankToNull(final @Nullable String value, final int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        final String stripped = value.strip();

        return stripped.length() > max ? stripped.substring(0, max) : stripped;
    }

    private static CollectionDto toDto(final VideoCollection collection, final Caller caller) {
        return new CollectionDto(collection.getUuid(), collection.getTitle(), collection.getDescription(),
                collection.getVideos().stream().filter(video -> visible(video, caller)).map(VideoDtos::toDto).toList(),
                collection.getCreatedAt(), collection.getLastModifiedAt());
    }

    /**
     * Listeners get the collections with a listed video; EDITOR gets every collection.
     */
    @Transactional(readOnly = true)
    public List<CollectionDto> collections() {
        final Caller caller = callerService.current();

        caller.requireUser();
        return repository.findAllByOrderByTitleAsc().stream()
                .map(collection -> toDto(collection, caller))
                .filter(collection -> caller.isEditor() || !collection.videos().isEmpty())
                .toList();
    }

    @Transactional(readOnly = true)
    public @Nullable CollectionDto collection(final UUID id) {
        final Caller caller = callerService.current();

        caller.requireUser();
        return repository.findByUuid(id)
                .map(collection -> toDto(collection, caller))
                .filter(collection -> caller.isEditor() || !collection.videos().isEmpty())
                .orElse(null);
    }

    /**
     * The collections each video is in, by title, for the caller.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<CollectionRefDto>> collectionsOf(final Collection<UUID> videoIds) {
        final Caller caller = callerService.current();
        final Map<UUID, List<CollectionRefDto>> result = new LinkedHashMap<>();

        caller.requireUser();
        videoIds.forEach(id -> result.put(id, new ArrayList<>()));
        for (final VideoCollection collection : repository.findContaining(videoIds)) {
            final CollectionRefDto ref = new CollectionRefDto(collection.getUuid(), collection.getTitle());

            collection.getVideos().stream()
                    .filter(video -> visible(video, caller))
                    .map(Video::getUuid)
                    .filter(result::containsKey)
                    .forEach(id -> result.get(id).add(ref));
        }
        return result;
    }

    /**
     * What plays after a video: the next listed video in the first collection (by title) that has one after it, else
     * the next listed video of the same spex. Null when nothing follows.
     */
    @Transactional(readOnly = true)
    public @Nullable VideoDto next(final UUID videoId) {
        final Caller caller = callerService.current();

        caller.requireUser();

        final Video current = videoRepository.findByUuid(videoId).orElse(null);

        if (current == null) {
            return null;
        }
        for (final VideoCollection collection : repository.findContaining(List.of(videoId))) {
            final List<Video> videos = collection.getVideos().stream()
                    .filter(video -> video.isListed() || video.getId().equals(current.getId()))
                    .toList();
            final int index = videos.indexOf(current);

            if (index >= 0 && index + 1 < videos.size()) {
                return VideoDtos.toDto(videos.get(index + 1));
            }
        }

        final SpexRef main = current.mainSpex();

        if (main == null) {
            return null;
        }

        final List<Video> siblings = videoRepository.findAboutSpex(List.of(main.familyId())).stream()
                .filter(video -> video.isListed() || video.getId().equals(current.getId()))
                .sorted(SPEX_ORDER)
                .toList();
        final int index = siblings.indexOf(current);

        return index >= 0 && index + 1 < siblings.size() ? VideoDtos.toDto(siblings.get(index + 1)) : null;
    }

    @Transactional
    public CollectionDto create(final CollectionInput input) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final VideoCollection collection = new VideoCollection();

        apply(collection, input);
        repository.save(collection);
        auditService.record("CREATE", "COLLECTION", collection.getUuid(), "Skapade samlingen " + collection.getTitle());
        return toDto(collection, caller);
    }

    @Transactional
    public CollectionDto update(final UUID id, final CollectionInput input) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final VideoCollection collection = find(id);

        apply(collection, input);
        auditService.record("UPDATE", "COLLECTION", collection.getUuid(), "Ändrade samlingen " + collection.getTitle());
        return toDto(collection, caller);
    }

    @Transactional
    public void delete(final UUID id) {
        callerService.current().requireAdmin();

        final VideoCollection collection = find(id);

        repository.delete(collection);
        auditService.record("DELETE", "COLLECTION", collection.getUuid(), "Tog bort samlingen " + collection.getTitle());
    }

    private void apply(final VideoCollection collection, final CollectionInput input) {
        final String title = blankToNull(input.title(), MAX_TITLE);

        if (title == null) {
            throw new BadRequestException("error.collection.invalid");
        }

        final Set<UUID> ids = new LinkedHashSet<>(Objects.requireNonNullElse(input.videoIds(), List.of()));
        final Map<UUID, Video> videos = videoRepository.findByUuidIn(ids).stream()
                .collect(Collectors.toMap(Video::getUuid, Function.identity()));

        if (videos.size() != ids.size() || videos.values().stream().anyMatch(Video::isTrashed)) {
            throw new BadRequestException("error.collection.videos");
        }
        collection.setTitle(title);
        collection.setDescription(blankToNull(input.description(), MAX_DESCRIPTION));
        collection.getVideos().clear();
        ids.forEach(videoId -> collection.getVideos().add(videos.get(videoId)));
    }

    private VideoCollection find(final UUID id) {
        return repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.collection.notFound", id));
    }

    public record CollectionDto(UUID id, String title, @Nullable String description, List<VideoDto> videos,
                                Instant createdAt, @Nullable Instant lastModifiedAt) {
    }

    public record CollectionRefDto(UUID id, String title) {
    }

    public record CollectionInput(String title, @Nullable String description, @Nullable List<UUID> videoIds) {
    }
}

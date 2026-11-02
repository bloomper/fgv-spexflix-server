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
import nu.fgv.spexflix.server.util.error.ConflictException;
import nu.fgv.spexflix.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The video kinds: everyone reads them, admins add, rename, reorder and delete them. A kind that videos use, trashed
 * ones included, cannot be deleted, and neither can the last one.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class VideoKindService {

    static final int MAX_NAME = 100;

    private final VideoKindRepository repository;
    private final CallerService callerService;
    private final AuditService auditService;

    public VideoKindService(final VideoKindRepository repository, final CallerService callerService,
                            final AuditService auditService) {
        this.repository = repository;
        this.callerService = callerService;
        this.auditService = auditService;
    }

    static VideoKindDto toDto(final VideoKind kind) {
        return new VideoKindDto(kind.getUuid(), kind.getNameSv(), kind.getNameEn(), kind.getPosition());
    }

    private static String name(final @Nullable String value) {
        if (!StringUtils.hasText(value) || value.strip().length() > MAX_NAME) {
            throw new BadRequestException("error.videoKind.invalid");
        }
        return value.strip();
    }

    @Transactional(readOnly = true)
    public List<VideoKindDto> kinds() {
        callerService.current().requireUser();
        return repository.findAllByOrderByPositionAscIdAsc().stream().map(VideoKindService::toDto).toList();
    }

    /**
     * Whether each kind is the default, the first by position.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Boolean> defaults(final Collection<UUID> ids) {
        final UUID first = repository.findFirstByOrderByPositionAscIdAsc().map(VideoKind::getUuid).orElse(null);

        return ids.stream().distinct().collect(Collectors.toMap(Function.identity(), id -> id.equals(first)));
    }

    /**
     * How many videos, trashed ones included, have each kind.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> videoCounts(final Collection<UUID> ids) {
        final Map<Long, UUID> uuids = repository.findAll().stream().collect(Collectors.toMap(VideoKind::getId, VideoKind::getUuid));
        final Map<UUID, Integer> counts = new HashMap<>();

        for (final Object[] row : repository.countVideosByKind()) {
            counts.put(uuids.get((Long) row[0]), ((Number) row[1]).intValue());
        }
        return ids.stream().distinct().collect(Collectors.toMap(Function.identity(), id -> counts.getOrDefault(id, 0)));
    }

    /**
     * The kind for a video: the given one, or the default when none is given.
     */
    @Transactional(readOnly = true)
    public VideoKind kindOrDefault(final @Nullable UUID id) {
        if (id == null) {
            return repository.findFirstByOrderByPositionAscIdAsc().orElseThrow(() -> new ResourceNotFoundException("error.videoKind.notFound", ""));
        }
        return kind(id);
    }

    @Transactional
    public VideoKindDto create(final VideoKindInput input) {
        callerService.current().requireAdmin();

        final VideoKind kind = new VideoKind();

        kind.setNameSv(name(input.nameSv()));
        kind.setNameEn(name(input.nameEn()));
        kind.setPosition(repository.findAllByOrderByPositionAscIdAsc().stream().mapToInt(VideoKind::getPosition).max().orElse(0) + 1);
        repository.save(kind);
        auditService.record("CREATE", "VIDEO_KIND", kind.getUuid(), "Skapade filmsorten " + kind.getNameSv());
        return toDto(kind);
    }

    @Transactional
    public VideoKindDto update(final UUID id, final VideoKindInput input) {
        callerService.current().requireAdmin();

        final VideoKind kind = kind(id);

        kind.setNameSv(name(input.nameSv()));
        kind.setNameEn(name(input.nameEn()));
        auditService.record("UPDATE", "VIDEO_KIND", kind.getUuid(), "Ändrade filmsorten " + kind.getNameSv());
        return toDto(kind);
    }

    @Transactional
    public List<VideoKindDto> reorder(final List<UUID> ids) {
        final Caller caller = callerService.current();

        caller.requireAdmin();

        final List<VideoKind> kinds = repository.findAll();
        final Map<UUID, VideoKind> byId = kinds.stream().collect(Collectors.toMap(VideoKind::getUuid, Function.identity()));

        if (ids.size() != kinds.size() || !byId.keySet().equals(new HashSet<>(ids))) {
            throw new BadRequestException("error.videoKind.reorder");
        }
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).setPosition(i + 1);
        }
        auditService.record("UPDATE", "VIDEO_KIND", null, "Ändrade ordningen på filmsorterna");
        return ids.stream().map(byId::get).map(VideoKindService::toDto).toList();
    }

    @Transactional
    public void delete(final UUID id) {
        callerService.current().requireAdmin();

        final VideoKind kind = kind(id);
        final long videos = repository.countVideos(kind);

        if (videos > 0) {
            throw new ConflictException("error.videoKind.inUse", videos);
        }
        if (repository.count() <= 1) {
            throw new BadRequestException("error.videoKind.last");
        }
        repository.delete(kind);
        auditService.record("DELETE", "VIDEO_KIND", kind.getUuid(), "Raderade filmsorten " + kind.getNameSv());
    }

    private VideoKind kind(final UUID id) {
        return repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.videoKind.notFound", id));
    }

    public record VideoKindDto(UUID id, String nameSv, String nameEn, int position) {
    }

    public record VideoKindInput(@Nullable String nameSv, @Nullable String nameEn) {
    }
}

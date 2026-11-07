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

package nu.fgv.spexflix.server.processing;

import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.video.VideoRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The processing queue and storage, as editors and admins see them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ProcessingService {

    private final ProcessingJobRepository jobRepository;
    private final VideoRepository videoRepository;
    private final MediaStorage storage;
    private final CallerService callerService;

    public ProcessingService(final ProcessingJobRepository jobRepository, final VideoRepository videoRepository,
                             final MediaStorage storage, final CallerService callerService) {
        this.jobRepository = jobRepository;
        this.videoRepository = videoRepository;
        this.storage = storage;
        this.callerService = callerService;
    }

    private static JobDto toDto(final ProcessingJob job) {
        return new JobDto(job.getId(), job.getVideo().getUuid(), job.getVideo().getTitle(), job.getStatus().name(), job.getProgress(),
                job.getAttempts(), job.getError(), job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt());
    }

    /**
     * Running and waiting jobs first, then the latest finished ones.
     */
    @Transactional(readOnly = true)
    public List<JobDto> queue() {
        callerService.current().requireEditor();
        return jobRepository.findRecent(PageRequest.of(0, 50)).stream()
                .sorted((a, b) -> Boolean.compare(b.getFinishedAt() == null, a.getFinishedAt() == null))
                .map(ProcessingService::toDto)
                .toList();
    }

    /**
     * The latest job of each video, for editors.
     */
    @Transactional(readOnly = true)
    public Map<UUID, JobDto> latest(final Collection<UUID> videoIds) {
        final Map<UUID, JobDto> jobs = new HashMap<>();

        if (!videoIds.isEmpty() && callerService.currentIfAny().map(caller -> caller.isEditor()).orElse(false)) {
            jobRepository.findLatestByVideoUuidIn(videoIds).stream()
                    .sorted(Comparator.comparing(ProcessingJob::getId))
                    .forEach(job -> jobs.put(job.getVideo().getUuid(), toDto(job)));
        }
        return jobs;
    }

    @Transactional(readOnly = true)
    public StorageDto storage() {
        callerService.current().requireAdmin();
        try {
            return new StorageDto(storage.size("originals"), storage.size("hls"), storage.freeSpace(), videoRepository.count());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record JobDto(Long id, UUID videoId, String videoTitle, String status, int progress, int attempts,
                         @Nullable String error, Instant createdAt, @Nullable Instant startedAt,
                         @Nullable Instant finishedAt) {
    }

    public record StorageDto(double originalsBytes, double renditionsBytes, double freeBytes, long videoCount) {
    }
}

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

import nu.fgv.spexflix.server.TestCallers;
import nu.fgv.spexflix.server.config.SpexflixProperties;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.video.Video;
import nu.fgv.spexflix.server.video.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static nu.fgv.spexflix.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The processing queue and the storage, as editors and admins see them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class ProcessingServiceTest {

    @TempDir
    private Path root;
    @Mock
    private ProcessingJobRepository jobRepository;
    @Mock
    private VideoRepository videoRepository;
    @Mock
    private CallerService callerService;
    private ProcessingService service;

    private static ProcessingJob job(final long id, final Instant finishedAt) {
        final Video video = new Video();
        final ProcessingJob job = new ProcessingJob();

        video.setId(id);
        video.setUuid(uuid(id));
        video.setTitle("Video " + id);
        job.setId(id);
        job.setVideo(video);
        job.setCreatedAt(Instant.parse("2026-11-01T10:00:00Z"));
        job.setFinishedAt(finishedAt);
        return job;
    }

    @BeforeEach
    void setUp() {
        service = new ProcessingService(jobRepository, videoRepository, new MediaStorage(new SpexflixProperties(null, null, null,
                new SpexflixProperties.Media(root, DataSize.ofGigabytes(1), null, null, null), null, null, null, null)), callerService);
    }

    @Test
    void theQueueShowsUnfinishedJobsFirstToEditors() {
        when(callerService.current()).thenReturn(TestCallers.editor());
        when(jobRepository.findRecent(any())).thenReturn(List.of(job(1L, Instant.now()), job(2L, null)));

        assertThat(service.queue()).extracting(ProcessingService.JobDto::videoId).containsExactly(uuid(2), uuid(1));

        when(callerService.current()).thenReturn(TestCallers.viewer());
        assertThatThrownBy(() -> service.queue()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theLatestJobsAreOnlyForEditors() {
        when(callerService.currentIfAny()).thenReturn(Optional.of(TestCallers.viewer()));
        assertThat(service.latest(List.of(uuid(1)))).isEmpty();

        when(callerService.currentIfAny()).thenReturn(Optional.of(TestCallers.editor()));
        final ProcessingJob first = job(1L, null);
        final ProcessingJob second = job(2L, null);

        when(videoRepository.findByUuidIn(List.of(uuid(1), uuid(2)))).thenReturn(List.of(first.getVideo(), second.getVideo()));
        when(jobRepository.findFirstByVideoIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(first));
        assertThat(service.latest(List.of(uuid(1), uuid(2)))).containsOnlyKeys(uuid(1));
    }

    @Test
    void adminsSeeHowMuchTheOriginalsAndRenditionsTakeUp() throws Exception {
        when(callerService.current()).thenReturn(TestCallers.admin());
        when(videoRepository.count()).thenReturn(3L);
        Files.write(root.resolve("originals").resolve("a.mp4"), new byte[100]);
        Files.createDirectories(root.resolve("hls").resolve("x"));
        Files.write(root.resolve("hls").resolve("x").resolve("seg.ts"), new byte[40]);

        final ProcessingService.StorageDto storage = service.storage();

        assertThat(storage.originalsBytes()).isEqualTo(100);
        assertThat(storage.renditionsBytes()).isEqualTo(40);
        assertThat(storage.freeBytes()).isPositive();
        assertThat(storage.videoCount()).isEqualTo(3);

        when(callerService.current()).thenReturn(TestCallers.editor());
        assertThatThrownBy(() -> service.storage()).isInstanceOf(AccessDeniedException.class);
    }
}

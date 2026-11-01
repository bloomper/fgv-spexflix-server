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

import nu.fgv.spexflix.server.TestClock;
import nu.fgv.spexflix.server.config.SpexflixProperties;
import nu.fgv.spexflix.server.settings.Settings;
import nu.fgv.spexflix.server.settings.SettingsService;
import nu.fgv.spexflix.server.video.Video;
import nu.fgv.spexflix.server.video.VideoRepository;
import nu.fgv.spexflix.server.video.VideoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The worker: claiming jobs, leases, replacing generations only when complete, and failures.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class ProcessingWorkerTest {

    private static final Transcoder.Output OUTPUT = new Transcoder.Output(
            new VideoProbe.Result("h264", 1920, 1080, 25, false, "aac", 7_200_000),
            List.of(new Transcoder.Made(new RenditionPlan.Step(1080, 1920, 5000, true), 4800, 1000),
                    new Transcoder.Made(new RenditionPlan.Step(720, 1280, 3000, false), 2900, 500)),
            true);
    private final TestClock clock = new TestClock(Instant.parse("2026-11-01T10:00:00Z"));
    @TempDir
    private Path root;
    @Mock
    private ProcessingJobRepository jobRepository;
    @Mock
    private VideoRepository videoRepository;
    @Mock
    private Transcoder transcoder;
    @Mock
    private SettingsService settingsService;
    @Mock
    private PlatformTransactionManager transactionManager;
    private MediaStorage storage;
    private ProcessingWorker worker;
    private Video video;
    private ProcessingJob job;
    private Settings settings;

    private ProcessingWorker worker(final boolean enabled) {
        final SpexflixProperties properties = new SpexflixProperties(null, null, null,
                new SpexflixProperties.Media(root, DataSize.ofGigabytes(1), "https://media.test", "key", Duration.ofHours(4)),
                new SpexflixProperties.Worker(enabled, 10), null, null, null);

        storage = new MediaStorage(properties);
        return new ProcessingWorker(properties, jobRepository, videoRepository, storage, transcoder, settingsService,
                new TransactionTemplate(transactionManager), clock);
    }

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        worker = worker(true);
        video = new Video();
        video.setId(1L);
        video.setTitle("Cleopatra");
        video.setStatus(VideoStatus.PROCESSING);
        video.setOriginalExtension("mp4");
        Files.createDirectories(storage.originals(video));
        Files.write(storage.original(video), new byte[]{1});
        job = new ProcessingJob();
        job.setId(9L);
        job.setVideo(video);
        job.setSource(storage.root().relativize(storage.original(video)).toString());
        settings = new Settings();
        lenient().when(settingsService.current()).thenReturn(settings);
        lenient().when(jobRepository.findById(9L)).thenReturn(Optional.of(job));
        lenient().when(videoRepository.findById(1L)).thenReturn(Optional.of(video));
    }

    @Test
    void aDisabledWorkerDoesNothing() {
        worker(false).tick();

        verify(jobRepository, never()).findFirstByStatusOrderByCreatedAtAsc(any());
    }

    @Test
    void aQueuedJobIsProcessedIntoTheNextGeneration() throws Exception {
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.QUEUED)).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
        when(transcoder.transcode(eq(storage.original(video)), eq(storage.generation(video, 1)), any(), any())).thenAnswer(invocation -> {
            invocation.<IntConsumer>getArgument(3).accept(100);
            return OUTPUT;
        });
        Files.createDirectories(storage.generation(video, 0));

        worker.tick();

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getProgress()).isEqualTo(100);
        assertThat(video.getStatus()).isEqualTo(VideoStatus.READY);
        assertThat(video.getGeneration()).isEqualTo(1);
        assertThat(video.getDurationMs()).isEqualTo(7_200_000);
        assertThat(video.isHasThumbnails()).isTrue();
        assertThat(video.getRenditions()).extracting(rendition -> rendition.getHeight() + (rendition.isCopied() ? "c" : "")).containsExactly("1080c", "720");
        assertThat(storage.generation(video, 0)).doesNotExist();
        assertThat(storage.original(video)).exists();
    }

    @Test
    void aReplacementBecomesTheOriginalWhenItIsDone() throws Exception {
        final Path pending = Files.write(storage.originals(video).resolve("pending-1.mov"), new byte[]{2});

        job.setSource(storage.root().relativize(pending).toString());
        video.setStatus(VideoStatus.READY);
        when(transcoder.transcode(any(), any(), any(), any())).thenReturn(OUTPUT);

        worker.run(9L);

        assertThat(storage.original(video)).hasBinaryContent(new byte[]{2});
        assertThat(pending).doesNotExist();
    }

    @Test
    void withoutKeepingOriginalsTheOriginalIsDeletedAfterProcessing() throws Exception {
        settings.setKeepOriginals(false);
        when(transcoder.transcode(any(), any(), any(), any())).thenReturn(OUTPUT);

        worker.run(9L);

        assertThat(storage.original(video)).doesNotExist();
        assertThat(video.isOriginalKept()).isFalse();
    }

    @Test
    void aFailureIsRecordedAndAReadyVideoKeepsPlaying() throws Exception {
        when(transcoder.transcode(any(), any(), any(), any())).thenThrow(new Transcoder.NotVideoException());

        worker.run(9L);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(video.getStatus()).isEqualTo(VideoStatus.FAILED);
        assertThat(video.getFailure()).isNotBlank();

        video.setStatus(VideoStatus.READY);
        worker.run(9L);
        assertThat(video.getStatus()).isEqualTo(VideoStatus.READY);
    }

    @Test
    void aJobWhoseLeaseRanOutIsQueuedAgainAndFailsAfterThreeTries() {
        final ProcessingJob lost = new ProcessingJob();

        lost.setVideo(video);
        lost.setStatus(JobStatus.RUNNING);
        lost.setAttempts(1);
        when(jobRepository.findByStatusAndLeaseUntilBefore(JobStatus.RUNNING, clock.instant())).thenReturn(List.of(lost));
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.QUEUED)).thenReturn(Optional.empty());

        worker.tick();
        assertThat(lost.getStatus()).isEqualTo(JobStatus.QUEUED);

        lost.setStatus(JobStatus.RUNNING);
        lost.setAttempts(3);
        worker.tick();
        assertThat(lost.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(lost.getError()).isEqualTo("Stopped 3 times");
    }
}

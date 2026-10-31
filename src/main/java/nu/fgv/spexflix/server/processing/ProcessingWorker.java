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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexflix.server.config.SpexflixProperties;
import nu.fgv.spexflix.server.settings.SettingsService;
import nu.fgv.spexflix.server.video.Rendition;
import nu.fgv.spexflix.server.video.Video;
import nu.fgv.spexflix.server.video.VideoRepository;
import nu.fgv.spexflix.server.video.VideoStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Processes one video at a time, in the worker container only ({@code spexflix.worker.enabled}). A job's lease is
 * renewed while it runs; a job whose lease ran out, because the worker stopped, is queued again and starts over.
 *
 * <p>The new generation replaces the old one only when it is complete, so a video that is being replaced keeps
 * playing its old renditions until then.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class ProcessingWorker {

    static final Duration LEASE = Duration.ofMinutes(10);
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ERROR = 2000;

    private final boolean enabled;
    private final ProcessingJobRepository jobRepository;
    private final VideoRepository videoRepository;
    private final MediaStorage storage;
    private final Transcoder transcoder;
    private final SettingsService settingsService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ProcessingWorker(final SpexflixProperties properties, final ProcessingJobRepository jobRepository,
                            final VideoRepository videoRepository, final MediaStorage storage, final Transcoder transcoder,
                            final SettingsService settingsService, final TransactionTemplate transactionTemplate,
                            final Clock clock) {
        this.enabled = properties.worker().enabled();
        this.jobRepository = jobRepository;
        this.videoRepository = videoRepository;
        this.storage = storage;
        this.transcoder = transcoder;
        this.settingsService = settingsService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 5, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    public void tick() {
        if (!enabled) {
            return;
        }
        requeueExpired();

        Long jobId;

        while ((jobId = claim()) != null) {
            run(jobId);
        }
    }

    private void requeueExpired() {
        transactionTemplate.executeWithoutResult(_ -> {
            for (final ProcessingJob job : jobRepository.findByStatusAndLeaseUntilBefore(JobStatus.RUNNING, clock.instant())) {
                log.info("Job {} of video {} lost its worker; queueing it again", job.getId(), job.getVideo().getId());
                job.setStatus(job.getAttempts() >= MAX_ATTEMPTS ? JobStatus.FAILED : JobStatus.QUEUED);
                job.setLeaseUntil(null);
                if (job.getStatus() == JobStatus.FAILED) {
                    job.setError("Stopped " + MAX_ATTEMPTS + " times");
                }
            }
        });
    }

    private Long claim() {
        return transactionTemplate.execute(_ -> jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.QUEUED).map(job -> {
            final Instant now = clock.instant();

            job.setStatus(JobStatus.RUNNING);
            job.setAttempts(job.getAttempts() + 1);
            job.setStartedAt(now);
            job.setLeaseUntil(now.plus(LEASE));
            job.setProgress(0);
            job.setError(null);
            job.getVideo().setStatus(job.getVideo().getStatus() == VideoStatus.READY ? VideoStatus.READY : VideoStatus.PROCESSING);
            return job.getId();
        }).orElse(null));
    }

    /**
     * Processes a job now, in this thread. The sample data import uses it to make videos ready at once.
     */
    public void run(final Long jobId) {
        final Work work = transactionTemplate.execute(_ -> {
            final ProcessingJob job = jobRepository.findById(jobId).orElseThrow();
            final Video video = job.getVideo();

            return new Work(video.getId(), storage.root().resolve(job.getSource()), video.getGeneration() + 1,
                    storage.generation(video, video.getGeneration() + 1), video.getPosterSeconds());
        });
        final AtomicReference<Instant> lastSaved = new AtomicReference<>(Instant.EPOCH);

        try {
            final Transcoder.Output output = transcoder.transcode(work.source(), work.target(), work.posterSeconds(), progress -> {
                final Instant now = clock.instant();

                if (lastSaved.get().plusSeconds(5).isBefore(now) || progress == 100) {
                    lastSaved.set(now);
                    transactionTemplate.executeWithoutResult(_ -> jobRepository.findById(jobId).ifPresent(job -> {
                        job.setProgress(progress);
                        job.setLeaseUntil(now.plus(LEASE));
                    }));
                }
            });

            succeeded(jobId, work, output);
        } catch (final IOException | RuntimeException e) {
            log.warn("Processing job {} of video {} failed: {}", jobId, work.videoId(), e.getMessage());
            failed(jobId, e);
        }
    }

    private void succeeded(final Long jobId, final Work work, final Transcoder.Output output) {
        transactionTemplate.executeWithoutResult(_ -> {
            final ProcessingJob job = jobRepository.findById(jobId).orElseThrow();
            final Video video = videoRepository.findById(work.videoId()).orElseThrow();

            try {
                final Path original = video.getOriginalExtension() == null ? null : storage.original(video);

                if (original != null && !work.source().equals(original)) {
                    // A replacement: it becomes the original
                    Files.move(work.source(), original, StandardCopyOption.REPLACE_EXISTING);
                }
                storage.deleteOtherGenerations(video, work.generation());
                if (!settingsService.current().isKeepOriginals() && original != null) {
                    Files.deleteIfExists(original);
                    video.setOriginalKept(false);
                }
            } catch (final IOException e) {
                log.warn("Could not tidy up after job {}: {}", jobId, e.getMessage());
            }
            video.setGeneration(work.generation());
            video.setStatus(VideoStatus.READY);
            video.setFailure(null);
            video.setDurationMs(output.probe().durationMs());
            video.setWidth(output.probe().width());
            video.setHeight(output.probe().height());
            video.setHasThumbnails(output.thumbnails());
            video.getRenditions().clear();
            for (final Transcoder.Made made : output.renditions()) {
                final Rendition rendition = new Rendition();

                rendition.setVideo(video);
                rendition.setHeight(made.step().height());
                rendition.setWidth(made.step().width());
                rendition.setBitrateKbps(made.bitrateKbps());
                rendition.setCopied(made.step().copy());
                rendition.setSize(made.size());
                video.getRenditions().add(rendition);
            }
            job.setStatus(JobStatus.DONE);
            job.setProgress(100);
            job.setFinishedAt(clock.instant());
            job.setLeaseUntil(null);
        });
    }

    private void failed(final Long jobId, final Exception e) {
        transactionTemplate.executeWithoutResult(_ -> {
            final ProcessingJob job = jobRepository.findById(jobId).orElseThrow();
            final Video video = job.getVideo();
            final String message = e instanceof Transcoder.NotVideoException ? e.getMessage()
                    : String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            final String error = message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR) : message;

            job.setStatus(JobStatus.FAILED);
            job.setError(error);
            job.setFinishedAt(clock.instant());
            job.setLeaseUntil(null);
            // A video being replaced keeps its old renditions
            if (video.getStatus() != VideoStatus.READY) {
                video.setStatus(VideoStatus.FAILED);
            }
            video.setFailure(error);
            try {
                storage.deleteOtherGenerations(video, video.getGeneration());
            } catch (final IOException ignored) {
                // left for the next run
            }
        });
    }

    private record Work(Long videoId, Path source, int generation, Path target, Double posterSeconds) {
    }
}

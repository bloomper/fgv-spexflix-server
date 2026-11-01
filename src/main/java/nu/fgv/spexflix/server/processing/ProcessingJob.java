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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.spexflix.server.video.Video;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Processing one source file of a video into a new generation. Kept in the database, so jobs survive restarts; one
 * that was running when the worker stopped starts over when its lease runs out.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "processing_job")
@Getter
@Setter
public class ProcessingJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JobStatus status = JobStatus.QUEUED;

    /**
     * The file to process, relative to the media root.
     */
    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "progress", nullable = false)
    private int progress;

    @Column(name = "step")
    private @Nullable String step;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "error")
    private @Nullable String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private @Nullable Instant startedAt;

    @Column(name = "finished_at")
    private @Nullable Instant finishedAt;

    @Column(name = "lease_until")
    private @Nullable Instant leaseUntil;
}

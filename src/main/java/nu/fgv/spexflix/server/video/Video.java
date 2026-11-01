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

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.spexflix.server.util.AbstractAuditable;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A recording. Its uuid names its folders: the original under {@code originals/<uuid>/} and the processed files under
 * {@code hls/<uuid>/<generation>/}. A new generation is made when the file is replaced or reprocessed, and the old one
 * is deleted when the new one is ready, so viewers never see a half-processed video.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "video")
@Getter
@Setter
public class Video extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid = UUID.randomUUID().toString();

    @Embedded
    private SpexRef spex = new SpexRef();

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "year")
    private @Nullable Integer year;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private VideoKind kind = VideoKind.FULL_SHOW;

    @Column(name = "performance")
    private @Nullable String performance;

    @Column(name = "performance_date")
    private @Nullable LocalDate performanceDate;

    @Column(name = "description")
    private @Nullable String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private VideoStatus status = VideoStatus.UPLOADING;

    @Column(name = "failure")
    private @Nullable String failure;

    @Column(name = "original_name")
    private @Nullable String originalName;

    @Column(name = "original_extension")
    private @Nullable String originalExtension;

    @Column(name = "original_size")
    private @Nullable Long originalSize;

    @Column(name = "original_kept", nullable = false)
    private boolean originalKept = true;

    @Column(name = "duration_ms")
    private @Nullable Long durationMs;

    @Column(name = "width")
    private @Nullable Integer width;

    @Column(name = "height")
    private @Nullable Integer height;

    @Column(name = "generation", nullable = false)
    private int generation;

    @Column(name = "has_thumbnails", nullable = false)
    private boolean hasThumbnails;

    @Column(name = "poster_seconds")
    private @Nullable Double posterSeconds;

    @Column(name = "poster_uploaded", nullable = false)
    private boolean posterUploaded;

    @Column(name = "published", nullable = false)
    private boolean published;

    @Column(name = "published_at")
    private @Nullable Instant publishedAt;

    @Column(name = "trashed_at")
    private @Nullable Instant trashedAt;

    @OneToMany(mappedBy = "video", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("height DESC")
    private List<Rendition> renditions = new ArrayList<>();

    @OneToMany(mappedBy = "video", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startSeconds ASC")
    private List<Chapter> chapters = new ArrayList<>();

    @OneToMany(mappedBy = "video", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("language ASC")
    private List<Subtitle> subtitles = new ArrayList<>();

    /**
     * The spex, never null: Hibernate loads an embedded value whose columns are all null as null.
     */
    public SpexRef getSpex() {
        if (spex == null) {
            spex = new SpexRef();
        }
        return spex;
    }

    public boolean isTrashed() {
        return trashedAt != null;
    }

    /**
     * Visible to viewers: published, processed and not in the trash.
     */
    public boolean isListed() {
        return published && status == VideoStatus.READY && trashedAt == null;
    }
}

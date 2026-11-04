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
package nu.fgv.spexflix.server.playlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.spexflix.server.util.Uuids;
import nu.fgv.spexflix.server.video.Video;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A whole video in a playlist, or a clip of it: a chapter from its start to the next chapter's start (or the end),
 * titled as the chapter was when it was added, since editing chapters replaces them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "playlist_item")
@Getter
@Setter
public class PlaylistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "playlist_id", nullable = false)
    private Playlist playlist;

    @Column(name = "position", nullable = false)
    private int position;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;

    @Column(name = "start_seconds")
    private @Nullable Integer startSeconds;

    @Column(name = "end_seconds")
    private @Nullable Integer endSeconds;

    @Column(name = "title")
    private @Nullable String title;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    public boolean isClip() {
        return startSeconds != null;
    }
}

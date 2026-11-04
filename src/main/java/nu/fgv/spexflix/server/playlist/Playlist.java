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

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.spexflix.server.util.Uuids;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A user's own list of whole videos and clips, in an order of their own. Others can play it when it is public.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "playlist")
@Getter
@Setter
public class Playlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private @Nullable String description;

    @Column(name = "public", nullable = false)
    private boolean shared;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "playlist", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<PlaylistItem> items = new ArrayList<>();

    /**
     * Numbers the items by their place in the list.
     */
    public void renumber() {
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setPosition(i);
        }
    }
}

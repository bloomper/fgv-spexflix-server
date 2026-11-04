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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Playlist}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface PlaylistRepository extends JpaRepository<Playlist, Long> {

    Optional<Playlist> findByUuid(UUID uuid);

    /**
     * The user's own playlists and everyone's public ones.
     */
    @Query("SELECT p FROM Playlist p WHERE p.userId = :userId OR p.shared = TRUE")
    List<Playlist> findVisible(@Param("userId") Long userId);

    long countByUserId(Long userId);
}

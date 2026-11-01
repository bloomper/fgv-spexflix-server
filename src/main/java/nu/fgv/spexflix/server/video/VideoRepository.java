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

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Video}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface VideoRepository extends JpaRepository<Video, Long> {

    Optional<Video> findByUuid(UUID uuid);

    List<Video> findByUuidIn(Collection<UUID> uuids);

    @Query("SELECT v FROM Video v WHERE v.trashedAt IS NULL ORDER BY v.year DESC, v.title ASC")
    List<Video> findNotTrashed();

    @Query("""
            SELECT v FROM Video v WHERE v.trashedAt IS NULL AND v.published = TRUE AND v.status = nu.fgv.spexflix.server.video.VideoStatus.READY
            ORDER BY v.year DESC, v.title ASC""")
    List<Video> findListed();

    @Query("""
            SELECT v FROM Video v WHERE v.trashedAt IS NULL AND v.published = TRUE AND v.status = nu.fgv.spexflix.server.video.VideoStatus.READY
            ORDER BY v.publishedAt DESC""")
    List<Video> findRecent(Pageable pageable);

    List<Video> findByTrashedAtIsNotNullOrderByTrashedAtDesc();

    @Query("SELECT DISTINCT v.spex.id FROM Video v WHERE v.spex.id IS NOT NULL")
    List<UUID> findSpexIds();

    List<Video> findBySpexIdIn(Collection<UUID> spexIds);

    @Query("""
            SELECT DISTINCT v FROM Video v LEFT JOIN v.chapters c
            WHERE v.trashedAt IS NULL AND (:editor = TRUE OR (v.published = TRUE AND v.status = nu.fgv.spexflix.server.video.VideoStatus.READY))
              AND (LOWER(v.title) LIKE :q OR LOWER(v.spex.title) LIKE :q OR LOWER(v.description) LIKE :q
                   OR LOWER(v.performance) LIKE :q OR LOWER(c.title) LIKE :q)
            ORDER BY v.year DESC, v.title ASC""")
    List<Video> search(@Param("q") String pattern, @Param("editor") boolean editor);
}

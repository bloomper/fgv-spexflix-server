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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link Chapter}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface ChapterRepository extends JpaRepository<Chapter, Long> {

    /**
     * Chapters of listed videos linked to any of the tracks.
     */
    @Query("""
            SELECT c FROM Chapter c JOIN FETCH c.video v
            WHERE c.spexifyTrackId IN :trackIds AND v.trashedAt IS NULL AND v.published = TRUE
              AND v.status = nu.fgv.spexflix.server.video.VideoStatus.READY
            ORDER BY v.year DESC, c.startSeconds ASC""")
    List<Chapter> findListedByTracks(@Param("trackIds") Collection<UUID> trackIds);
}

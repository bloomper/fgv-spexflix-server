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

package nu.fgv.spexflix.server.playback;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link WatchProgress}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface WatchProgressRepository extends JpaRepository<WatchProgress, Long> {

    Optional<WatchProgress> findByUserIdAndVideoId(Long userId, Long videoId);

    List<WatchProgress> findByUserIdAndVideoIdIn(Long userId, Collection<Long> videoIds);

    List<WatchProgress> findByUserIdAndFinishedFalseOrderByUpdatedAtDesc(Long userId);

    List<WatchProgress> findByUserIdAndFinishedTrue(Long userId);

    /**
     * How many have watched each video to the end, as rows of video id and count.
     */
    @Query("SELECT p.videoId, COUNT(p) FROM WatchProgress p WHERE p.finished = TRUE GROUP BY p.videoId")
    List<Object[]> countFinished();

    /**
     * The same as {@link #countFinished()} for some videos.
     */
    @Query("SELECT p.videoId, COUNT(p) FROM WatchProgress p WHERE p.finished = TRUE AND p.videoId IN :videoIds GROUP BY p.videoId")
    List<Object[]> countFinished(@Param("videoIds") Collection<Long> videoIds);
}

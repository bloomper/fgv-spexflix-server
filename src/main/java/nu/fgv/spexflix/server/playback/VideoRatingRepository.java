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
 * Repository for {@link VideoRating}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface VideoRatingRepository extends JpaRepository<VideoRating, Long> {

    Optional<VideoRating> findByUserIdAndVideoId(Long userId, Long videoId);

    List<VideoRating> findByUserIdAndVideoIdIn(Long userId, Collection<Long> videoIds);

    List<VideoRating> findByUserIdOrderByRatingDescRatedAtDesc(Long userId);

    /**
     * The average rating and the number of ratings of each video, as rows of video id, average and count.
     */
    @Query("SELECT r.videoId, AVG(r.rating), COUNT(r) FROM VideoRating r WHERE r.videoId IN :videoIds GROUP BY r.videoId")
    List<Object[]> summarize(@Param("videoIds") Collection<Long> videoIds);

    /**
     * The sum and number of ratings of every rated video, as rows of video id, sum and count.
     */
    @Query("SELECT r.videoId, SUM(r.rating), COUNT(r) FROM VideoRating r GROUP BY r.videoId")
    List<Object[]> totals();
}

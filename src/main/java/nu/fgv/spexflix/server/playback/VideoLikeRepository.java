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
 * Repository for {@link VideoLike}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface VideoLikeRepository extends JpaRepository<VideoLike, Long> {

    Optional<VideoLike> findByUserIdAndVideoId(Long userId, Long videoId);

    List<VideoLike> findByUserIdAndVideoIdIn(Long userId, Collection<Long> videoIds);

    List<VideoLike> findByUserIdOrderByLikedAtDesc(Long userId);

    /**
     * How many like each video, as rows of video id and count.
     */
    @Query("SELECT l.videoId, COUNT(l) FROM VideoLike l WHERE l.videoId IN :videoIds GROUP BY l.videoId")
    List<Object[]> count(@Param("videoIds") Collection<Long> videoIds);

    /**
     * How many like each liked video, as rows of video id and count.
     */
    @Query("SELECT l.videoId, COUNT(l) FROM VideoLike l GROUP BY l.videoId")
    List<Object[]> countAll();
}

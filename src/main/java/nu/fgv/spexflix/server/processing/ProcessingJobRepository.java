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

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ProcessingJob}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, Long> {

    Optional<ProcessingJob> findFirstByStatusOrderByCreatedAtAsc(JobStatus status);

    List<ProcessingJob> findByStatusAndLeaseUntilBefore(JobStatus status, Instant before);

    List<ProcessingJob> findByStatusInOrderByCreatedAtAsc(Collection<JobStatus> statuses);

    @Query("SELECT j FROM ProcessingJob j JOIN FETCH j.video ORDER BY j.createdAt DESC")
    List<ProcessingJob> findRecent(Pageable pageable);

    Optional<ProcessingJob> findFirstByVideoIdOrderByCreatedAtDesc(Long videoId);

    boolean existsByVideoIdAndStatusIn(Long videoId, Collection<JobStatus> statuses);

    @Query("SELECT j FROM ProcessingJob j WHERE j.video.id = :videoId AND j.status IN :statuses")
    List<ProcessingJob> findActive(@Param("videoId") Long videoId, @Param("statuses") Collection<JobStatus> statuses);
}

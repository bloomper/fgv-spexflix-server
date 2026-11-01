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

package nu.fgv.spexflix.server.config;

import nu.fgv.spexflix.server.AbstractIntegrationTest;
import nu.fgv.spexflix.server.upload.UploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scheduled jobs that run in both {@code spexflix-api} and {@code spexflix-worker} skip their run while the other
 * container holds the job's lock.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class SchedulerLockIntegrationTest extends AbstractIntegrationTest {

    private static final String LOCK = "upload-clean-up";

    @Autowired
    private UploadService uploadService;

    @Test
    void cleanUpSkipsWhileAnotherContainerHoldsTheLock() {
        insertAbandonedUpload();
        jdbcClient.sql("INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES (?, ?, ?, 'spexflix-worker')")
                .params(LOCK, Instant.now().plus(10, ChronoUnit.MINUTES), Instant.now())
                .update();

        uploadService.cleanUp();

        assertThat(uploadSessions()).isEqualTo(1);
    }

    @Test
    void cleanUpRunsAndReleasesTheLock() {
        insertAbandonedUpload();

        uploadService.cleanUp();

        assertThat(uploadSessions()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM shedlock WHERE name = ? AND lock_until <= CURRENT_TIMESTAMP(3)")
                .param(LOCK).query(Integer.class).single()).isEqualTo(1);
    }

    private void insertAbandonedUpload() {
        jdbcClient.sql("""
                        INSERT INTO upload_session (id, owner, length, upload_offset, file_name, created_at)
                        VALUES ('00000000-0000-0000-0000-000000000001', 'someone', 10, 0, 'old.mp4', ?)""")
                .param(Instant.now().minus(2, ChronoUnit.DAYS))
                .update();
    }

    private int uploadSessions() {
        return jdbcClient.sql("SELECT COUNT(*) FROM upload_session").query(Integer.class).single();
    }
}

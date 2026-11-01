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

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on scheduled jobs: the nightly refresh of spex data, the clean-up of abandoned uploads and, in the worker,
 * video processing.
 *
 * <p>{@code spexflix-api} and {@code spexflix-worker} run the same image, so jobs that must not run twice at once take
 * a ShedLock lock in the {@code shedlock} table. The database's clock decides when a lock expires, so the containers'
 * clocks don't matter. The processing worker needs no lock: it claims jobs with leases of its own.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(final JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbcTemplate)
                .usingDbTime()
                .build());
    }
}

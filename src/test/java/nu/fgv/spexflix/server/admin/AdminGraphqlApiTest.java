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

package nu.fgv.spexflix.server.admin;

import nu.fgv.spexflix.server.audit.AuditEntry;
import nu.fgv.spexflix.server.audit.AuditService;
import nu.fgv.spexflix.server.processing.ProcessingService;
import nu.fgv.spexflix.server.settings.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The audit log's page answers with the page and size it was read with, not those asked for.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class AdminGraphqlApiTest {

    @Mock
    private SettingsService settingsService;
    @Mock
    private AuditService auditService;
    @Mock
    private ProcessingService processingService;

    @Test
    void theAuditLogAnswersWithThePageItWasClampedTo() {
        final AdminGraphqlApi api = new AdminGraphqlApi(settingsService, auditService, processingService);

        when(auditService.page(-3, 100_000, null)).thenReturn(new PageImpl<AuditEntry>(List.of(), PageRequest.of(0, 200), 0));

        final AdminGraphqlApi.AuditPageDto page = api.auditLog(-3, 100_000, null);

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(200);
    }
}

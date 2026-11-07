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
import nu.fgv.spexflix.server.audit.AuditFilter;
import nu.fgv.spexflix.server.audit.AuditService;
import nu.fgv.spexflix.server.processing.ProcessingService;
import nu.fgv.spexflix.server.settings.SettingsService;
import nu.fgv.spexflix.server.settings.SettingsService.SettingsDto;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.time.Instant;
import java.util.List;

/**
 * GraphQL for settings, the processing queue, storage and the audit log.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class AdminGraphqlApi {

    private final SettingsService settingsService;
    private final AuditService auditService;
    private final ProcessingService processingService;

    public AdminGraphqlApi(final SettingsService settingsService, final AuditService auditService,
                           final ProcessingService processingService) {
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.processingService = processingService;
    }

    @QueryMapping
    public SettingsDto settings() {
        return settingsService.read();
    }

    @MutationMapping
    public SettingsDto settingsUpdate(@Argument final SettingsDto input) {
        return settingsService.update(input);
    }

    @QueryMapping
    public List<ProcessingService.JobDto> processingQueue() {
        return processingService.queue();
    }

    @QueryMapping
    public ProcessingService.StorageDto storage() {
        return processingService.storage();
    }

    @QueryMapping
    public AuditPageDto auditLog(@Argument final @Nullable Integer page, @Argument final @Nullable Integer size,
                                 @Argument final @Nullable AuditFilter filter) {
        final int p = page == null ? 0 : page;
        final int s = size == null ? 50 : size;
        final Page<AuditEntry> entries = auditService.page(p, s, filter);

        return new AuditPageDto(entries.getContent().stream()
                .map(entry -> new AuditLogEntryDto(entry.getId(), entry.getAt(), entry.getActorSubject(), entry.getActorName(),
                        entry.getAction(), entry.getEntityType(), entry.getEntityId(), entry.getSummary()))
                .toList(), entries.getTotalElements(), entries.getNumber(), entries.getSize());
    }

    @QueryMapping
    public List<AuditActorDto> auditActors() {
        return auditService.actors().entrySet().stream().map(actor -> new AuditActorDto(actor.getKey(), actor.getValue())).toList();
    }

    public record AuditPageDto(List<AuditLogEntryDto> entries, long totalCount, int page, int size) {
    }

    public record AuditLogEntryDto(Long id, Instant at, String actorId, @Nullable String actorName, String action,
                                   String entityType, @Nullable String entityId, String summary) {
    }

    public record AuditActorDto(String id, @Nullable String name) {
    }
}

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

package nu.fgv.spexflix.server.settings;

import nu.fgv.spexflix.server.TestCallers;
import nu.fgv.spexflix.server.TestClock;
import nu.fgv.spexflix.server.audit.AuditService;
import nu.fgv.spexflix.server.util.StoredText;
import nu.fgv.spexflix.server.settings.SettingsService.SettingsDto;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.util.error.BadRequestException;
import nu.fgv.spexflix.server.video.Video;
import nu.fgv.spexflix.server.video.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;

import static nu.fgv.spexflix.server.util.TestUuids.uuid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The settings admins keep, and their limits.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock
    private SettingsRepository repository;
    @Mock
    private VideoRepository videoRepository;
    @Mock
    private CallerService callerService;
    @Mock
    private AuditService auditService;
    private SettingsService service;

    @BeforeEach
    void setUp() {
        service = new SettingsService(repository, videoRepository, callerService, auditService, new TestClock(Instant.parse("2026-11-01T10:00:00Z")));
        lenient().when(repository.findById(Settings.ID)).thenReturn(Optional.empty());
    }

    @Test
    void withoutSavedSettingsTheDefaultsApply() {
        when(callerService.current()).thenReturn(TestCallers.viewer());

        assertThat(service.read()).isEqualTo(new SettingsDto(null, null, null, 50, true, true));
    }

    @Test
    void anAdminSavesTrimmedTextsAndTheChangeIsAudited() {
        when(callerService.current()).thenReturn(TestCallers.admin());

        assertThat(service.update(new SettingsDto("  Välkommen!  ", " ", null, 80, false, false)))
                .isEqualTo(new SettingsDto("Välkommen!", null, null, 80, false, false));
        verify(auditService).record("UPDATE", "SETTINGS", null, StoredText.of("audit.settings.update"));
    }

    @Test
    void theUploadLimitMustBeBetween1And500Gb() {
        when(callerService.current()).thenReturn(TestCallers.admin());

        assertThatThrownBy(() -> service.update(new SettingsDto(null, null, null, 0, true, true))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.update(new SettingsDto(null, null, null, 501, true, true))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void onlyAdminsChangeTheSettings() {
        when(callerService.current()).thenReturn(TestCallers.editor());

        assertThatThrownBy(() -> service.update(new SettingsDto(null, null, null, 80, true, true))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theFeaturedVideoMustExistOutsideTheTrash() {
        final Video ready = new Video();
        final Video trashed = new Video();

        trashed.setTrashedAt(Instant.parse("2026-10-01T10:00:00Z"));
        when(callerService.current()).thenReturn(TestCallers.admin());
        when(videoRepository.findByUuid(uuid(1))).thenReturn(Optional.of(ready));
        when(videoRepository.findByUuid(uuid(2))).thenReturn(Optional.of(trashed));
        when(videoRepository.findByUuid(uuid(3))).thenReturn(Optional.empty());

        assertThat(service.update(new SettingsDto(null, null, uuid(1), 50, true, false)).featuredVideoId()).isEqualTo(uuid(1));
        assertThatThrownBy(() -> service.update(new SettingsDto(null, null, uuid(2), 50, true, true))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.update(new SettingsDto(null, null, uuid(3), 50, true, true))).isInstanceOf(BadRequestException.class);
    }
}

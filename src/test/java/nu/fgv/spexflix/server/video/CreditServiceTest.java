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

import nu.fgv.spexflix.server.TestCallers;
import nu.fgv.spexflix.server.audit.AuditService;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.util.error.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static nu.fgv.spexflix.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Who takes part in the videos.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class CreditServiceTest {

    @Mock
    private VideoRepository videoRepository;
    @Mock
    private CallerService callerService;
    @Mock
    private AuditService auditService;
    private CreditService service;

    private static Video video(final long id, final int year, final Credit... credits) {
        final Video video = new Video();
        final VideoKind kind = new VideoKind();

        kind.setUuid(uuid(100));
        kind.setNameSv("Föreställning");
        kind.setNameEn("Show");
        video.setId(id);
        video.setUuid(uuid((int) id));
        video.setTitle("Film " + id);
        video.setYear(year);
        video.setKind(kind);
        video.setStatus(VideoStatus.READY);
        video.setPublished(true);
        video.getCredits().addAll(List.of(credits));
        return video;
    }

    @BeforeEach
    void setUp() {
        service = new CreditService(videoRepository, callerService, auditService);
        lenient().when(callerService.current()).thenReturn(TestCallers.viewer());
    }

    @Test
    void namesAreCountedWithoutRegardToCaseAndShownAsFirstWritten() {
        when(videoRepository.findListed()).thenReturn(List.of(
                video(1, 2024, new Credit("Anna Andersson", "Kleopatra"), new Credit("Bertil", "Regi")),
                video(2, 2023, new Credit(" anna andersson", "Caesar"))));

        assertThat(service.credits()).extracting(CreditService.CreditCountDto::name, CreditService.CreditCountDto::videoCount)
                .containsExactly(tuple("Anna Andersson", 2), tuple("Bertil", 1));
        assertThat(service.creditVideos("ANNA ANDERSSON"))
                .extracting(entry -> entry.video().id(), CreditService.CreditedVideoDto::role)
                .containsExactly(tuple(uuid(1), "Kleopatra"), tuple(uuid(2), "Caesar"));
    }

    @Test
    void editorsReplaceCreditsInOrderAndRowsWithoutANameAreLeftOut() {
        final Video video = video(1, 2024, new Credit("Gammal", null));

        when(callerService.current()).thenReturn(TestCallers.editor());
        when(videoRepository.findByUuid(video.getUuid())).thenReturn(Optional.of(video));

        service.setCredits(video.getUuid(), List.of(new CreditService.CreditInput("  Cecilia   Ceder ", " "),
                new CreditService.CreditInput(" ", "Ljus"), new CreditService.CreditInput("David", "Ljud")));

        assertThat(video.getCredits()).extracting(Credit::getName, Credit::getRole)
                .containsExactly(tuple("Cecilia Ceder", null), tuple("David", "Ljud"));
    }

    @Test
    void listenersCannotChangeCreditsAndThereIsALimit() {
        assertThatThrownBy(() -> service.setCredits(uuid(1), List.of())).isInstanceOf(AccessDeniedException.class);

        final Video video = video(1, 2024);

        when(callerService.current()).thenReturn(TestCallers.editor());
        when(videoRepository.findByUuid(video.getUuid())).thenReturn(Optional.of(video));
        assertThatThrownBy(() -> service.setCredits(video.getUuid(),
                Collections.nCopies(CreditService.MAX_CREDITS + 1, new CreditService.CreditInput("Någon", null))))
                .isInstanceOf(BadRequestException.class);
    }
}

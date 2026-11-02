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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static nu.fgv.spexflix.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Collections, and what plays after a video.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class CollectionServiceTest {

    private static final UUID SPEX = uuid(900);
    @Mock
    private VideoCollectionRepository repository;
    @Mock
    private VideoRepository videoRepository;
    @Mock
    private CallerService callerService;
    @Mock
    private AuditService auditService;
    private CollectionService service;
    private VideoKind show;
    private VideoKind trailer;

    @BeforeEach
    void setUp() {
        service = new CollectionService(repository, videoRepository, callerService, auditService);
        show = kind(1);
        trailer = kind(2);
        lenient().when(callerService.current()).thenReturn(TestCallers.viewer());
    }

    private static VideoKind kind(final int position) {
        final VideoKind kind = new VideoKind();

        kind.setUuid(uuid(100 + position));
        kind.setNameSv("Sort " + position);
        kind.setNameEn("Kind " + position);
        kind.setPosition(position);
        return kind;
    }

    private Video video(final long id, final VideoKind kind, final LocalDate performed, final boolean listed) {
        final Video video = new Video();

        video.setId(id);
        video.setUuid(uuid((int) id));
        video.setTitle("Film " + id);
        video.setKind(kind);
        video.setPerformanceDate(performed);
        final SpexRef ref = new SpexRef();

        ref.setId(SPEX);
        video.getSpex().add(ref);
        video.setStatus(VideoStatus.READY);
        video.setPublished(listed);
        lenient().when(videoRepository.findByUuid(video.getUuid())).thenReturn(Optional.of(video));
        return video;
    }

    @Test
    void theNextVideoOfASpexFollowsKindThenPerformanceDate() {
        final Video trailerVideo = video(1, trailer, LocalDate.of(2024, 1, 1), true);
        final Video late = video(2, show, LocalDate.of(2024, 3, 2), true);
        final Video early = video(3, show, LocalDate.of(2024, 3, 1), true);
        final Video unlisted = video(4, show, LocalDate.of(2024, 3, 1), false);

        when(videoRepository.findAboutSpex(List.of(SPEX))).thenReturn(List.of(trailerVideo, late, unlisted, early));

        assertThat(service.next(early.getUuid())).satisfies(next -> assertThat(next.id()).isEqualTo(late.getUuid()));
        assertThat(service.next(late.getUuid())).satisfies(next -> assertThat(next.id()).isEqualTo(trailerVideo.getUuid()));
        assertThat(service.next(trailerVideo.getUuid())).isNull();
    }

    @Test
    void aCollectionDecidesTheNextVideoBeforeTheSpex() {
        final Video first = video(1, show, LocalDate.of(2024, 1, 1), true);
        final Video hidden = video(2, show, LocalDate.of(2024, 1, 2), false);
        final Video last = video(3, show, LocalDate.of(2023, 1, 1), true);
        final VideoCollection collection = new VideoCollection();

        collection.getVideos().addAll(List.of(first, hidden, last));
        when(repository.findContaining(List.of(first.getUuid()))).thenReturn(List.of(collection));

        assertThat(service.next(first.getUuid())).satisfies(next -> assertThat(next.id()).isEqualTo(last.getUuid()));
    }

    @Test
    void viewersCannotChangeCollectionsAndEditorsCannotDeleteThem() {
        final var input = new CollectionService.CollectionInput("Alla år", null, List.of());

        assertThatThrownBy(() -> service.create(input)).isInstanceOf(AccessDeniedException.class);
        when(callerService.current()).thenReturn(TestCallers.editor());
        assertThatThrownBy(() -> service.delete(uuid(1))).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void aCollectionNeedsATitleAndVideosOutsideTheTrash() {
        final Video trashed = video(1, show, null, true);

        trashed.setTrashedAt(Instant.now());
        when(callerService.current()).thenReturn(TestCallers.editor());
        when(videoRepository.findByUuidIn(any())).thenReturn(List.of(trashed));

        assertThatThrownBy(() -> service.create(new CollectionService.CollectionInput(" ", null, List.of())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(new CollectionService.CollectionInput("Alla år", null, List.of(trashed.getUuid()))))
                .isInstanceOf(BadRequestException.class);
    }
}

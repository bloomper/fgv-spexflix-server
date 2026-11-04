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
package nu.fgv.spexflix.server.playlist;

import nu.fgv.spexflix.server.TestCallers;
import nu.fgv.spexflix.server.user.AppUser;
import nu.fgv.spexflix.server.user.AppUserRepository;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.util.error.BadRequestException;
import nu.fgv.spexflix.server.util.error.ResourceNotFoundException;
import nu.fgv.spexflix.server.video.Chapter;
import nu.fgv.spexflix.server.video.Video;
import nu.fgv.spexflix.server.video.VideoKind;
import nu.fgv.spexflix.server.video.VideoService;
import nu.fgv.spexflix.server.video.VideoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static nu.fgv.spexflix.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Playlists of whole videos and clips.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class PlaylistServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    @Mock
    private PlaylistRepository repository;
    @Mock
    private VideoService videoService;
    @Mock
    private CallerService callerService;
    @Mock
    private AppUserRepository userRepository;
    private PlaylistService service;
    private AppUser tina;
    private Video video;
    private Playlist playlist;

    @BeforeEach
    void setUp() {
        service = new PlaylistService(repository, videoService, callerService, userRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        tina = new AppUser();
        tina.setId(5L);
        tina.setUsername("tina@spexflix.test");
        tina.setDisplayName("Tina");
        video = new Video();
        video.setId(1L);
        video.setUuid(uuid(1));
        video.setTitle("Cleopatra");
        video.setKind(new VideoKind());
        video.setStatus(VideoStatus.READY);
        video.setPublished(true);
        video.setDurationMs(600_000L);
        video.getChapters().add(chapter(0, "Ouvertyr"));
        video.getChapters().add(chapter(95, "Kärleksduetten"));
        playlist = new Playlist();
        playlist.setId(3L);
        playlist.setUuid(uuid(3));
        playlist.setUserId(5L);
        playlist.setName("Favoriter");
        playlist.setCreatedAt(NOW);
        playlist.setUpdatedAt(NOW);
        lenient().when(callerService.current()).thenReturn(TestCallers.viewer());
        lenient().when(callerService.record(any())).thenReturn(tina);
        lenient().when(videoService.visible(uuid(1))).thenReturn(video);
        lenient().when(repository.findByUuid(uuid(3))).thenReturn(Optional.of(playlist));
    }

    private Chapter chapter(final int start, final String title) {
        final Chapter chapter = new Chapter();

        chapter.setVideo(video);
        chapter.setStartSeconds(start);
        chapter.setTitle(title);
        return chapter;
    }

    @Test
    void aWholeVideoAndAChapterClipAreAdded() {
        service.addItem(uuid(3), uuid(1), null);

        final PlaylistService.PlaylistDto result = service.addItem(uuid(3), uuid(1), 95);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().getFirst().startSeconds()).isNull();
        assertThat(result.items().get(1)).satisfies(clip -> {
            assertThat(clip.startSeconds()).isEqualTo(95);
            // The last chapter runs to the end of the video
            assertThat(clip.endSeconds()).isEqualTo(600);
            assertThat(clip.title()).isEqualTo("Kärleksduetten");
            assertThat(clip.durationSeconds()).isEqualTo(505);
        });
        assertThat(result.durationSeconds()).isEqualTo(1105);
        assertThat(playlist.getItems()).extracting(PlaylistItem::getPosition).containsExactly(0, 1);
    }

    @Test
    void aClipEndsWhereTheNextChapterStarts() {
        assertThat(service.addItem(uuid(3), uuid(1), 0).items().getFirst().endSeconds()).isEqualTo(95);
    }

    @Test
    void aClipMustStartAtAChapter() {
        assertThatThrownBy(() -> service.addItem(uuid(3), uuid(1), 30)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void onlyTheOwnerChangesAPlaylist() {
        playlist.setUserId(6L);
        playlist.setShared(true);

        assertThatThrownBy(() -> service.addItem(uuid(3), uuid(1), null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(uuid(3))).isInstanceOf(ResourceNotFoundException.class);
        // A public one can still be played
        assertThat(service.playlist(uuid(3))).isNotNull().satisfies(dto -> assertThat(dto.owned()).isFalse());
    }

    @Test
    void othersPrivatePlaylistsAreHidden() {
        playlist.setUserId(6L);

        assertThat(service.playlist(uuid(3))).isNull();
    }

    @Test
    void itemsAreReorderedAndRemoved() {
        service.addItem(uuid(3), uuid(1), 0);
        service.addItem(uuid(3), uuid(1), 95);

        final PlaylistItem first = playlist.getItems().get(0);
        final PlaylistItem second = playlist.getItems().get(1);

        assertThat(service.reorder(uuid(3), List.of(second.getUuid())).items()).extracting(PlaylistService.PlaylistItemDto::title)
                .containsExactly("Kärleksduetten", "Ouvertyr");
        assertThatThrownBy(() -> service.reorder(uuid(3), List.of(first.getUuid(), first.getUuid()))).isInstanceOf(BadRequestException.class);
        assertThat(service.removeItem(uuid(3), second.getUuid()).items()).extracting(PlaylistService.PlaylistItemDto::title)
                .containsExactly("Ouvertyr");
    }

    @Test
    void unlistedVideosAreLeftOut() {
        service.addItem(uuid(3), uuid(1), null);
        video.setPublished(false);

        assertThat(service.playlist(uuid(3)).items()).isEmpty();
    }

    @Test
    void aPlaylistNeedsAName() {
        assertThatThrownBy(() -> service.create(new PlaylistService.PlaylistInput(" ", null, false))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void theCallersOwnPlaylistsComeFirst() {
        final Playlist other = new Playlist();

        other.setUuid(uuid(4));
        other.setUserId(6L);
        other.setName("Alla tiders");
        other.setShared(true);
        other.setCreatedAt(NOW);
        other.setUpdatedAt(NOW);
        when(repository.findVisible(5L)).thenReturn(List.of(other, playlist));
        when(userRepository.findAllById(any())).thenReturn(List.of(tina));

        assertThat(service.playlists()).extracting(PlaylistService.PlaylistDto::name).containsExactly("Favoriter", "Alla tiders");
    }
}

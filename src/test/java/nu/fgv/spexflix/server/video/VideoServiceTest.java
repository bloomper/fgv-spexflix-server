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
import nu.fgv.spexflix.server.config.SpexflixProperties;
import nu.fgv.spexflix.server.processing.MediaStorage;
import nu.fgv.spexflix.server.processing.ProcessingJob;
import nu.fgv.spexflix.server.processing.ProcessingJobRepository;
import nu.fgv.spexflix.server.processing.Transcoder;
import nu.fgv.spexflix.server.spex.SpexDto;
import nu.fgv.spexflix.server.spex.SpexService;
import nu.fgv.spexflix.server.upload.UploadService;
import nu.fgv.spexflix.server.user.Caller;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.util.error.BadRequestException;
import nu.fgv.spexflix.server.util.error.ResourceNotFoundException;
import nu.fgv.spexflix.server.video.VideoDtos.ChapterInput;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import nu.fgv.spexflix.server.video.VideoDtos.VideoFilter;
import nu.fgv.spexflix.server.video.VideoDtos.VideoInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who sees which videos, and the rules for editing them: files, publishing, posters, chapters, subtitles and the trash.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class VideoServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final SpexDto CLEOPATRA = new SpexDto(7L, "Cleopatra", 2024, false, null, 1L, "Chalmersspexet");

    private final List<Video> videos = new ArrayList<>();
    @Mock
    private VideoRepository videoRepository;
    @Mock
    private ProcessingJobRepository jobRepository;
    @Mock
    private CallerService callerService;
    @Mock
    private SpexService spexService;
    @Mock
    private UploadService uploadService;
    @Mock
    private Transcoder transcoder;
    @Mock
    private AuditService auditService;
    @TempDir
    private Path root;
    private MediaStorage storage;
    private VideoService service;
    private Video ready;
    private Video waiting;

    private static Video video(final long id, final String title, final VideoStatus status, final boolean published) {
        final Video video = new Video();

        video.setId(id);
        video.setTitle(title);
        video.setYear(2024);
        video.setKind(VideoKind.FULL_SHOW);
        video.setStatus(status);
        video.setPublished(published);
        return video;
    }

    private void as(final Caller caller) {
        when(callerService.current()).thenReturn(caller);
    }

    @BeforeEach
    void setUp() {
        storage = new MediaStorage(new SpexflixProperties(null, null, null,
                new SpexflixProperties.Media(root, DataSize.ofGigabytes(1), "https://media.test", "key", null), null, null, null, null));
        service = new VideoService(videoRepository, jobRepository, callerService, spexService, uploadService, storage, transcoder,
                auditService, Clock.fixed(NOW, ZoneOffset.UTC));
        ready = video(1L, "Cleopatra", VideoStatus.READY, true);
        waiting = video(2L, "Väntar", VideoStatus.UPLOADING, false);
        videos.addAll(List.of(ready, waiting));
        lenient().when(videoRepository.findById(anyLong())).thenAnswer(invocation ->
                videos.stream().filter(video -> video.getId().equals(invocation.getArgument(0))).findFirst());
        lenient().doAnswer(invocation -> {
            final SpexDto spex = invocation.getArgument(0);
            final SpexRef ref = invocation.getArgument(1);

            ref.setId(spex.id());
            ref.setTitle(spex.title());
            ref.setYear(spex.year());
            return null;
        }).when(spexService).copy(any(), any());
    }

    // ---------------------------------------------------------------------------------------------------- reading

    @Test
    void viewersSeeOnlyListedVideosAndEditorsTheRestWhenTheyAsk() {
        as(TestCallers.viewer());
        when(videoRepository.findListed()).thenReturn(List.of(ready));

        assertThat(service.videos(new VideoFilter(null, null, null, null, true))).extracting(VideoDto::title).containsExactly("Cleopatra");
        assertThat(service.video(2L)).isNull();
        assertThatThrownBy(() -> service.visible(2L)).isInstanceOf(ResourceNotFoundException.class);

        as(TestCallers.editor());
        when(videoRepository.findNotTrashed()).thenReturn(videos);
        assertThat(service.videos(new VideoFilter(null, null, null, null, true))).hasSize(2);
        assertThat(service.visible(2L).getTitle()).isEqualTo("Väntar");
    }

    @Test
    void videosAreFilteredByCategoryYearSpexAndKind() {
        as(TestCallers.viewer());
        ready.getSpex().setId(7L);
        ready.getSpex().setCategoryId(1L);
        when(videoRepository.findListed()).thenReturn(List.of(ready));

        assertThat(service.videos(new VideoFilter(1L, 2024, 7L, VideoKind.FULL_SHOW, null))).hasSize(1);
        assertThat(service.videos(new VideoFilter(null, null, null, VideoKind.TRAILER, null))).isEmpty();
        assertThat(service.videos(new VideoFilter(2L, null, null, null, null))).isEmpty();
    }

    @Test
    void categoriesAndYearsComeFromListedVideos() {
        as(TestCallers.viewer());

        final Video older = video(3L, "Nobel", VideoStatus.READY, true);

        older.setYear(1975);
        ready.getSpex().setCategoryId(1L);
        ready.getSpex().setCategoryName("Chalmersspexet");
        older.getSpex().setCategoryId(1L);
        older.getSpex().setCategoryName("Chalmersspexet");
        when(videoRepository.findListed()).thenReturn(List.of(ready, older));

        assertThat(service.categories()).extracting(category -> category.name() + ":" + category.videoCount()).containsExactly("Chalmersspexet:2");
        assertThat(service.years()).containsExactly(2024, 1975);
    }

    @Test
    void searchNeedsTwoCharactersAndEscapesWildcards() {
        as(TestCallers.viewer());

        assertThat(service.search("c")).isEmpty();
        service.search("50%");
        verify(videoRepository).search("%50\\%%", false);
    }

    @Test
    void withoutUserNothingIsRead() {
        as(TestCallers.noAccess());

        assertThatThrownBy(() -> service.videos(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.recent(5)).isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------------------------------------------- editing

    @Test
    void aNewVideoTakesTitleAndYearFromItsSpexAndChecksTheDate() {
        as(TestCallers.editor());
        when(spexService.byId(7L)).thenReturn(CLEOPATRA);

        final VideoDto created = service.create(new VideoInput(7L, null, null, VideoKind.FULL_SHOW, " Premiären ", "2024-02-14", null));

        assertThat(created.title()).isEqualTo("Cleopatra");
        assertThat(created.year()).isEqualTo(2024);
        assertThat(created.performance()).isEqualTo("Premiären");
        assertThat(created.performanceDate()).isEqualTo(LocalDate.of(2024, 2, 14).toString());
        assertThatThrownBy(() -> service.create(new VideoInput(7L, null, null, VideoKind.FULL_SHOW, null, "14/2", null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(new VideoInput(null, " ", null, VideoKind.OTHER, null, null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void viewersCannotEditAndTrashedVideosCannotBeEdited() {
        as(TestCallers.viewer());
        assertThatThrownBy(() -> service.publish(1L, false)).isInstanceOf(AccessDeniedException.class);

        as(TestCallers.editor());
        ready.setTrashedAt(NOW);
        assertThatThrownBy(() -> service.publish(1L, false)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.publish(99L, false)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void onlyReadyVideosArePublishedAndTheDateIsSetOnce() {
        as(TestCallers.editor());
        assertThatThrownBy(() -> service.publish(2L, true)).isInstanceOf(BadRequestException.class);

        ready.setPublished(false);
        service.publish(1L, true);
        assertThat(ready.getPublishedAt()).isEqualTo(NOW);
        verify(auditService).record("PUBLISH", "VIDEO", 1L, "Publicerade Cleopatra");
    }

    @Test
    void theFirstFileBecomesTheOriginalAndIsQueued() throws Exception {
        as(TestCallers.editor());

        final Path upload = Files.write(root.resolve("upload"), new byte[]{1, 2, 3});

        when(uploadService.claim("u-1", TestCallers.editor())).thenReturn(upload);
        when(uploadService.fileName("u-1")).thenReturn("Cleopatra 2024.MKV");

        final VideoDto result = service.setFile(2L, "u-1");

        assertThat(result.status()).isEqualTo(VideoStatus.PROCESSING);
        assertThat(storage.original(waiting)).hasBinaryContent(new byte[]{1, 2, 3});
        assertThat(waiting.getOriginalName()).isEqualTo("Cleopatra 2024.MKV");
        assertThat(waiting.getOriginalSize()).isEqualTo(3);

        final ArgumentCaptor<ProcessingJob> job = ArgumentCaptor.forClass(ProcessingJob.class);

        verify(jobRepository).save(job.capture());
        assertThat(job.getValue().getSource()).isEqualTo("originals/" + waiting.getUuid() + "/original.mkv");
        verify(uploadService).release("u-1");
    }

    @Test
    void aReplacementWaitsBesideTheOriginalAndTheVideoKeepsPlaying() throws Exception {
        as(TestCallers.editor());
        ready.setOriginalExtension("mp4");

        final Path upload = Files.write(root.resolve("upload"), new byte[]{4});

        when(uploadService.claim("u-2", TestCallers.editor())).thenReturn(upload);
        when(uploadService.fileName("u-2")).thenReturn("ny.mov");

        assertThat(service.setFile(1L, "u-2").status()).isEqualTo(VideoStatus.READY);
        assertThat(storage.originals(ready).resolve("pending-" + NOW.toEpochMilli() + ".mov")).exists();
        verify(auditService).record("REPLACE", "VIDEO", 1L, "Bytte filen för Cleopatra");
    }

    @Test
    void aFileIsNotTakenWhileTheVideoIsBeingProcessed() {
        as(TestCallers.editor());
        when(jobRepository.existsByVideoIdAndStatusIn(eq(2L), any())).thenReturn(true);

        assertThatThrownBy(() -> service.setFile(2L, "u-1")).isInstanceOf(BadRequestException.class);
        verify(uploadService, never()).claim(any(), any());
    }

    @Test
    void onlyAdminsReprocessAndOnlyWithAnOriginal() throws Exception {
        as(TestCallers.editor());
        assertThatThrownBy(() -> service.reprocess(1L)).isInstanceOf(AccessDeniedException.class);

        as(TestCallers.admin());
        assertThatThrownBy(() -> service.reprocess(1L)).isInstanceOf(BadRequestException.class);

        ready.setOriginalExtension("mp4");
        Files.createDirectories(storage.originals(ready));
        Files.write(storage.original(ready), new byte[]{1});
        service.reprocess(1L);
        verify(jobRepository).save(any(ProcessingJob.class));
        assertThat(ready.getStatus()).isEqualTo(VideoStatus.READY);
    }

    @Test
    void thePosterIsTakenFromTheOriginalOrElseTheTopRendition() throws Exception {
        as(TestCallers.editor());
        assertThatThrownBy(() -> service.posterFrame(2L, 10)).isInstanceOf(BadRequestException.class);

        final Rendition rendition = new Rendition();

        rendition.setHeight(720);
        ready.getRenditions().add(rendition);
        ready.setOriginalKept(false);
        Files.createDirectories(storage.hls(ready));
        Files.write(storage.uploadedPoster(ready), new byte[]{1});

        service.posterFrame(1L, 12.5);

        verify(transcoder).poster(storage.generation(ready, 0).resolve("720/index.m3u8"), storage.poster(ready), 12.5);
        assertThat(storage.uploadedPoster(ready)).doesNotExist();
        assertThat(ready.getPosterSeconds()).isEqualTo(12.5);
        assertThat(ready.isPosterUploaded()).isFalse();
    }

    @Test
    void anUploadedPosterIsWritten() {
        as(TestCallers.editor());

        service.posterUpload(1L, new byte[]{(byte) 0xFF, (byte) 0xD8});

        assertThat(storage.uploadedPoster(ready)).exists();
        assertThat(ready.isPosterUploaded()).isTrue();
    }

    @Test
    void chaptersAreReplacedSortedAndValidated() {
        as(TestCallers.editor());

        service.setChapters(1L, List.of(new ChapterInput(90, " Finalen ", null, null), new ChapterInput(0, "Ouvertyr", 12L, "Ouvertyr")));

        assertThat(ready.getChapters()).extracting(Chapter::getTitle).containsExactly("Ouvertyr", "Finalen");
        assertThat(ready.getChapters().getFirst().getSpexifyTrackId()).isEqualTo(12L);
        assertThatThrownBy(() -> service.setChapters(1L, List.of(new ChapterInput(-1, "Fel", null, null)))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.setChapters(1L, List.of(new ChapterInput(1, " ", null, null)))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void subtitlesAreStoredPerLanguageAndReplaced() {
        as(TestCallers.editor());

        service.subtitleSet(1L, "sv", "Svenska", "WEBVTT".getBytes());
        service.subtitleSet(1L, "sv", " Svenska (ny) ", "WEBVTT\n\n".getBytes());

        assertThat(ready.getSubtitles()).extracting(Subtitle::getLabel).containsExactly("Svenska (ny)");
        assertThat(storage.subtitle(ready, "sv")).hasBinaryContent("WEBVTT\n\n".getBytes());
        assertThatThrownBy(() -> service.subtitleSet(1L, "svenska", "Svenska", new byte[0])).isInstanceOf(BadRequestException.class);

        service.subtitleRemove(1L, "sv");
        assertThat(ready.getSubtitles()).isEmpty();
        assertThat(storage.subtitle(ready, "sv")).doesNotExist();
    }

    @Test
    void onlyAdminsDeleteAndOnlyFromTheTrash() throws Exception {
        as(TestCallers.admin());
        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(BadRequestException.class);

        Files.createDirectories(storage.hls(ready));
        service.trash(1L, true);
        service.delete(1L);

        assertThat(storage.hls(ready)).doesNotExist();
        verify(videoRepository).delete(ready);
    }

    @Test
    void emptyingTheTrashDeletesEveryTrashedVideo() {
        as(TestCallers.admin());
        when(videoRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc()).thenReturn(List.of(ready, waiting));

        service.emptyTrash();

        verify(videoRepository).delete(ready);
        verify(videoRepository).delete(waiting);
    }
}

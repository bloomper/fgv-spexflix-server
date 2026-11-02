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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexflix.server.audit.AuditService;
import nu.fgv.spexflix.server.processing.JobStatus;
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
import nu.fgv.spexflix.server.video.VideoDtos.CategoryDto;
import nu.fgv.spexflix.server.video.VideoDtos.ChapterInput;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import nu.fgv.spexflix.server.video.VideoDtos.VideoFilter;
import nu.fgv.spexflix.server.video.VideoDtos.VideoInput;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The catalogue of videos: reading them as the caller may see them, and editing them. Files are taken from finished
 * uploads and queued for the worker; nothing is transcoded here.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class VideoService {

    private static final int MAX_TEXT = 255;
    private static final int MAX_CHAPTERS = 200;
    private static final int MAX_SPEX = 20;

    private final VideoRepository videoRepository;
    private final VideoKindService kindService;
    private final ProcessingJobRepository jobRepository;
    private final CallerService callerService;
    private final SpexService spexService;
    private final UploadService uploadService;
    private final MediaStorage storage;
    private final Transcoder transcoder;
    private final AuditService auditService;
    private final Clock clock;

    public VideoService(final VideoRepository videoRepository, final VideoKindService kindService,
                        final ProcessingJobRepository jobRepository,
                        final CallerService callerService, final SpexService spexService, final UploadService uploadService,
                        final MediaStorage storage, final Transcoder transcoder, final AuditService auditService,
                        final Clock clock) {
        this.videoRepository = videoRepository;
        this.kindService = kindService;
        this.jobRepository = jobRepository;
        this.callerService = callerService;
        this.spexService = spexService;
        this.uploadService = uploadService;
        this.storage = storage;
        this.transcoder = transcoder;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------------------------------- reading

    private static String limit(final String value) {
        return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) : value;
    }

    @Transactional(readOnly = true)
    public List<VideoDto> videos(final @Nullable VideoFilter filter) {
        final Caller caller = callerService.current();

        caller.requireUser();

        final boolean all = filter != null && Boolean.TRUE.equals(filter.includeUnpublished()) && caller.isEditor();

        return (all ? videoRepository.findNotTrashed() : videoRepository.findListed()).stream()
                .filter(video -> filter == null || filter.categoryId() == null || filter.categoryId().equals(categoryId(video)))
                .filter(video -> filter == null || filter.year() == null || filter.year().equals(video.getYear()))
                .filter(video -> filter == null || filter.spexId() == null || video.isAbout(filter.spexId()))
                .filter(video -> filter == null || filter.kindId() == null || filter.kindId().equals(video.getKind().getUuid()))
                .map(VideoDtos::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public @Nullable VideoDto video(final UUID id) {
        final Caller caller = callerService.current();

        caller.requireUser();
        return videoRepository.findByUuid(id)
                .filter(video -> video.isListed() || caller.isEditor())
                .map(VideoDtos::toDto)
                .orElse(null);
    }

    /**
     * A video the caller may see, for playback and posters.
     */
    @Transactional(readOnly = true)
    public Video visible(final UUID id) {
        final Caller caller = callerService.current();

        caller.requireUser();

        final Video video = videoRepository.findByUuid(id)
                .filter(candidate -> candidate.isListed() || caller.isEditor())
                .orElseThrow(() -> new ResourceNotFoundException("error.video.notFound", id));

        video.getSubtitles().size();
        return video;
    }

    @Transactional(readOnly = true)
    public List<VideoDto> recent(final int limit) {
        callerService.current().requireUser();
        return videoRepository.findRecent(PageRequest.of(0, Math.clamp(limit, 1, 50))).stream().map(VideoDtos::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> categories() {
        callerService.current().requireUser();

        final Map<UUID, String> names = new HashMap<>();
        final Map<UUID, Integer> counts = new HashMap<>();

        for (final Video video : videoRepository.findListed()) {
            final UUID categoryId = categoryId(video);

            if (categoryId != null) {
                names.put(categoryId, Objects.requireNonNullElse(video.mainSpex().getCategoryName(), "?"));
                counts.merge(categoryId, 1, Integer::sum);
            }
        }
        return names.entrySet().stream()
                .map(entry -> new CategoryDto(entry.getKey(), entry.getValue(), counts.get(entry.getKey())))
                .sorted(Comparator.comparing(CategoryDto::videoCount).reversed().thenComparing(CategoryDto::name))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Integer> years() {
        callerService.current().requireUser();
        return videoRepository.findListed().stream().map(Video::getYear).filter(Objects::nonNull).distinct()
                .sorted(Comparator.reverseOrder()).toList();
    }

    @Transactional(readOnly = true)
    public List<VideoDto> search(final String query) {
        final Caller caller = callerService.current();

        caller.requireUser();

        final String q = query.strip().toLowerCase(Locale.ROOT);

        if (q.length() < 2) {
            return List.of();
        }
        return videoRepository.search("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", caller.isEditor())
                .stream().limit(50).map(VideoDtos::toDto).toList();
    }

    // ---------------------------------------------------------------------------------------------------- editing

    @Transactional(readOnly = true)
    public List<VideoDto> trash() {
        callerService.current().requireEditor();
        return videoRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc().stream().map(VideoDtos::toDto).toList();
    }

    @Transactional
    public VideoDto create(final VideoInput input) {
        callerService.current().requireEditor();

        final Video video = new Video();

        apply(video, input);
        videoRepository.save(video);
        auditService.record("CREATE", "VIDEO", video.getUuid(), "Skapade filmen " + video.getTitle());
        return VideoDtos.toDto(video);
    }

    @Transactional
    public VideoDto update(final UUID id, final VideoInput input) {
        callerService.current().requireEditor();

        final Video video = editable(id);

        apply(video, input);
        auditService.record("UPDATE", "VIDEO", video.getUuid(), "Ändrade filmen " + video.getTitle());
        return VideoDtos.toDto(video);
    }

    private static @Nullable UUID categoryId(final Video video) {
        return video.mainSpex() == null ? null : video.mainSpex().getCategoryId();
    }

    private void apply(final Video video, final VideoInput input) {
        final List<UUID> ids = input.spexIds() == null ? List.of() : input.spexIds().stream().distinct().toList();
        final Map<UUID, SpexRef> current = video.getSpex().stream()
                .filter(ref -> ref.getTitle() != null)
                .collect(Collectors.toMap(SpexRef::getId, Function.identity(), (a, _) -> a));
        final List<SpexRef> refs = new ArrayList<>();

        if (ids.size() > MAX_SPEX) {
            throw new BadRequestException("error.video.tooManySpex", MAX_SPEX);
        }
        for (final UUID id : ids) {
            SpexRef ref = current.get(id);

            if (ref == null) {
                ref = new SpexRef();
                spexService.copy(spexService.byId(id), ref);
            }
            refs.add(ref);
        }
        video.getSpex().clear();
        video.getSpex().addAll(refs);

        final SpexRef main = video.mainSpex();
        final String title = StringUtils.hasText(input.title()) ? input.title().strip() : main != null ? main.getTitle() : null;

        if (title == null) {
            throw new BadRequestException("error.video.titleRequired");
        }
        video.setTitle(limit(title));
        video.setYear(input.year() != null ? input.year() : main != null ? main.getYear() : null);
        video.setKind(input.kindId() == null && video.getKind() != null ? video.getKind() : kindService.kindOrDefault(input.kindId()));
        video.setPerformance(StringUtils.hasText(input.performance()) ? limit(input.performance().strip()) : null);
        video.setDescription(StringUtils.hasText(input.description()) ? input.description().strip() : null);
        try {
            video.setPerformanceDate(StringUtils.hasText(input.performanceDate()) ? LocalDate.parse(input.performanceDate().strip()) : null);
        } catch (final DateTimeParseException e) {
            throw new BadRequestException("error.video.date");
        }
    }

    /**
     * Takes a finished upload as the video's file, its first or a replacement, and queues it. A replacement waits
     * beside the original until the worker has processed it; the old renditions play until then.
     */
    @Transactional
    public VideoDto setFile(final UUID id, final String uploadId) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final Video video = editable(id);

        if (jobRepository.existsByVideoIdAndStatusIn(video.getId(), EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING))) {
            throw new BadRequestException("error.video.processing");
        }

        final Path upload = uploadService.claim(uploadId, caller);
        final String name = uploadService.fileName(uploadId);
        final String extension = MediaStorage.extensionOf(name.isEmpty() ? "video.bin" : name);
        final boolean first = video.getOriginalExtension() == null || video.getStatus() != VideoStatus.READY;
        final ProcessingJob job = new ProcessingJob();
        final Path target;

        try {
            Files.createDirectories(storage.originals(video));
            if (first) {
                if (video.getOriginalExtension() != null) {
                    Files.deleteIfExists(storage.original(video));
                }
                video.setOriginalExtension(extension);
                target = storage.original(video);
            } else {
                target = storage.originals(video).resolve("pending-" + clock.millis() + "." + extension);
            }
            video.setOriginalName(limit(name.isEmpty() ? "video." + extension : name));
            video.setOriginalSize(Files.size(upload));
            video.setOriginalKept(true);
            Files.move(upload, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        uploadService.release(uploadId);
        if (first) {
            video.setStatus(VideoStatus.PROCESSING);
            video.setFailure(null);
        }
        job.setVideo(video);
        job.setSource(storage.root().relativize(target).toString());
        job.setCreatedAt(clock.instant());
        jobRepository.save(job);
        auditService.record(first ? "UPLOAD" : "REPLACE", "VIDEO", video.getUuid(),
                (first ? "Laddade upp filen till " : "Bytte filen för ") + video.getTitle());
        return VideoDtos.toDto(video);
    }

    @Transactional
    public VideoDto reprocess(final UUID id) {
        callerService.current().requireAdmin();

        final Video video = editable(id);

        if (video.getOriginalExtension() == null || !Files.isRegularFile(storage.original(video))) {
            throw new BadRequestException("error.video.noOriginal");
        }
        if (jobRepository.existsByVideoIdAndStatusIn(video.getId(), EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING))) {
            throw new BadRequestException("error.video.processing");
        }

        final ProcessingJob job = new ProcessingJob();

        job.setVideo(video);
        job.setSource(storage.root().relativize(storage.original(video)).toString());
        job.setCreatedAt(clock.instant());
        jobRepository.save(job);
        if (video.getStatus() != VideoStatus.READY) {
            video.setStatus(VideoStatus.PROCESSING);
        }
        auditService.record("REPROCESS", "VIDEO", video.getUuid(), "Bearbetar om " + video.getTitle());
        return VideoDtos.toDto(video);
    }

    @Transactional
    public VideoDto publish(final UUID id, final boolean published) {
        callerService.current().requireEditor();

        final Video video = editable(id);

        if (published && video.getStatus() != VideoStatus.READY) {
            throw new BadRequestException("error.video.notReady");
        }
        video.setPublished(published);
        if (published && video.getPublishedAt() == null) {
            video.setPublishedAt(clock.instant());
        }
        auditService.record(published ? "PUBLISH" : "UNPUBLISH", "VIDEO", video.getUuid(),
                (published ? "Publicerade " : "Avpublicerade ") + video.getTitle());
        return VideoDtos.toDto(video);
    }

    @Transactional
    public VideoDto trash(final UUID id, final boolean trashed) {
        callerService.current().requireEditor();

        final Video video = videoRepository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.video.notFound", id));

        video.setTrashedAt(trashed ? clock.instant() : null);
        auditService.record(trashed ? "TRASH" : "RESTORE", "VIDEO", video.getUuid(),
                (trashed ? "Lade i papperskorgen: " : "Återställde ") + video.getTitle());
        return VideoDtos.toDto(video);
    }

    @Transactional
    public void delete(final UUID id) {
        callerService.current().requireAdmin();

        final Video video = videoRepository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.video.notFound", id));

        if (!video.isTrashed()) {
            throw new BadRequestException("error.video.notTrashed");
        }
        deleteVideo(video);
    }

    @Transactional
    public void emptyTrash() {
        callerService.current().requireAdmin();
        videoRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc().forEach(this::deleteVideo);
    }

    private void deleteVideo(final Video video) {
        try {
            storage.deleteVideo(video);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        auditService.record("DELETE", "VIDEO", video.getUuid(), "Raderade " + video.getTitle());
        videoRepository.delete(video);
    }

    /**
     * Takes the poster from a point in the video, from the original or, when it is not kept, the top rendition.
     */
    @Transactional
    public VideoDto posterFrame(final UUID id, final double seconds) {
        callerService.current().requireEditor();

        final Video video = editable(id);

        if (video.getStatus() != VideoStatus.READY || video.getRenditions().isEmpty()) {
            throw new BadRequestException("error.video.notReady");
        }

        final Path source = video.isOriginalKept() && Files.isRegularFile(storage.original(video))
                ? storage.original(video)
                : storage.generation(video, video.getGeneration()).resolve(video.getRenditions().getFirst().getHeight() + "/index.m3u8");

        try {
            transcoder.poster(source, storage.poster(video), seconds);
            Files.deleteIfExists(storage.uploadedPoster(video));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        video.setPosterSeconds(seconds);
        video.setPosterUploaded(false);
        auditService.record("UPDATE", "VIDEO", video.getUuid(), "Bytte affischbild för " + video.getTitle());
        return VideoDtos.toDto(video);
    }

    @Transactional
    public VideoDto posterUpload(final UUID id, final byte[] jpeg) {
        callerService.current().requireEditor();

        final Video video = editable(id);

        try {
            Files.createDirectories(storage.hls(video));
            Files.write(storage.uploadedPoster(video), jpeg);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        video.setPosterUploaded(true);
        auditService.record("UPDATE", "VIDEO", video.getUuid(), "Laddade upp affischbild för " + video.getTitle());
        return VideoDtos.toDto(video);
    }

    @Transactional
    public VideoDto setChapters(final UUID id, final List<ChapterInput> inputs) {
        callerService.current().requireEditor();

        final Video video = editable(id);

        if (inputs.size() > MAX_CHAPTERS || inputs.stream().anyMatch(input -> input.startSeconds() < 0 || !StringUtils.hasText(input.title()))) {
            throw new BadRequestException("error.chapter.invalid");
        }
        video.getChapters().clear();
        inputs.stream().sorted(Comparator.comparingInt(ChapterInput::startSeconds)).forEach(input -> {
            final Chapter chapter = new Chapter();

            chapter.setVideo(video);
            chapter.setStartSeconds(input.startSeconds());
            chapter.setTitle(limit(input.title().strip()));
            chapter.setSpexifyTrackId(input.spexifyTrackId());
            chapter.setSpexifyTrackTitle(input.spexifyTrackTitle() == null ? null : limit(input.spexifyTrackTitle()));
            video.getChapters().add(chapter);
        });
        auditService.record("UPDATE", "VIDEO", video.getUuid(), "Ändrade kapitlen i " + video.getTitle());
        return VideoDtos.toDto(video);
    }

    @Transactional
    public VideoDto subtitleSet(final UUID id, final String language, final String label, final byte[] vtt) {
        callerService.current().requireEditor();

        final Video video = editable(id);

        if (!language.matches("[a-z]{2,3}(-[A-Za-z]{2,4})?") || !StringUtils.hasText(label)) {
            throw new BadRequestException("error.subtitle.invalid");
        }
        try {
            final Path file = storage.subtitle(video, language);

            Files.createDirectories(file.getParent());
            Files.write(file, vtt);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }

        final Subtitle subtitle = video.getSubtitles().stream().filter(s -> s.getLanguage().equals(language)).findFirst().orElseGet(() -> {
            final Subtitle created = new Subtitle();

            created.setVideo(video);
            created.setLanguage(language);
            video.getSubtitles().add(created);
            return created;
        });

        subtitle.setLabel(limit(label.strip()));
        auditService.record("UPDATE", "VIDEO", video.getUuid(), "Laddade upp undertexter (" + language + ") till " + video.getTitle());
        return VideoDtos.toDto(video);
    }

    @Transactional
    public VideoDto subtitleRemove(final UUID id, final String language) {
        callerService.current().requireEditor();

        final Video video = editable(id);

        video.getSubtitles().removeIf(subtitle -> subtitle.getLanguage().equals(language));
        try {
            Files.deleteIfExists(storage.subtitle(video, language.replaceAll("[^a-zA-Z-]", "")));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        auditService.record("UPDATE", "VIDEO", video.getUuid(), "Tog bort undertexter (" + language + ") från " + video.getTitle());
        return VideoDtos.toDto(video);
    }

    private Video editable(final UUID id) {
        final Video video = videoRepository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.video.notFound", id));

        if (video.isTrashed()) {
            throw new BadRequestException("error.video.trashed");
        }
        return video;
    }
}

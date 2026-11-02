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

package nu.fgv.spexflix.server.util.data;

import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexflix.server.audit.AuditEntry;
import nu.fgv.spexflix.server.audit.AuditEntryRepository;
import nu.fgv.spexflix.server.config.SpexflixProperties;
import nu.fgv.spexflix.server.playback.WatchListEntry;
import nu.fgv.spexflix.server.playback.WatchListEntryRepository;
import nu.fgv.spexflix.server.playback.WatchProgress;
import nu.fgv.spexflix.server.playback.WatchProgressRepository;
import nu.fgv.spexflix.server.processing.Ffmpeg;
import nu.fgv.spexflix.server.processing.JobStatus;
import nu.fgv.spexflix.server.processing.MediaStorage;
import nu.fgv.spexflix.server.processing.ProcessingJob;
import nu.fgv.spexflix.server.processing.ProcessingJobRepository;
import nu.fgv.spexflix.server.processing.ProcessingWorker;
import nu.fgv.spexflix.server.spex.SpexDto;
import nu.fgv.spexflix.server.spex.SpexService;
import nu.fgv.spexflix.server.spex.SpexregisterClient;
import nu.fgv.spexflix.server.spexify.SpexifyClient;
import nu.fgv.spexflix.server.user.AppUser;
import nu.fgv.spexflix.server.user.AppUserRepository;
import nu.fgv.spexflix.server.util.security.SecurityUtil;
import nu.fgv.spexflix.server.video.Chapter;
import nu.fgv.spexflix.server.video.Subtitle;
import nu.fgv.spexflix.server.video.Video;
import nu.fgv.spexflix.server.video.VideoKind;
import nu.fgv.spexflix.server.video.VideoKindRepository;
import nu.fgv.spexflix.server.video.VideoRepository;
import nu.fgv.spexflix.server.video.VideoStatus;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Replaces everything with sample data at every start while {@code spexflix.data.import-sample-data} is set: the
 * database, the media folder and the sample users in Keycloak. Built on Spexregister's sample spex, so Spexregister must
 * run with its sample data too; chapters link to Spexify's sample tracks when Spexify runs with its sample data.
 *
 * <p>The originals are queued for the worker, so a fresh start shows videos in every status. With
 * {@code spexflix.data.sample-data-preprocessed} each distinct original is processed here instead, once, and every
 * video is ready at once.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class SampleDataImporter {

    // Spexregister's sample users, which its sample data import creates; they get roles here
    static final String ADMIN = "admin@fgv.nu";
    static final String EDITOR = "editor@fgv.nu";
    static final String USER = "user@fgv.nu";
    // The only user of the applications' own, shared by Spexify's and Spexflix's sample data: an account without access
    static final String NO_ACCESS = "no-access@fgv.nu";
    private static final int SPEXREGISTER_ATTEMPTS = 30;
    private static final Duration SPEXREGISTER_RETRY_INTERVAL = Duration.ofSeconds(10);
    private static final List<String> TABLES = List.of("audit_entry", "video_rating", "watch_list_entry", "watch_progress", "processing_job",
            "chapter", "subtitle", "rendition", "video", "video_kind", "upload_session", "app_user");
    private static final List<String> CHAPTERS = List.of("Ouvertyr", "Öppningskören", "Kärleksduetten", "Hovets klagan",
            "Rövarvisan", "Ödets nummer", "Finalen", "Extranumret");
    private static final String SUBTITLES_SV = """
            WEBVTT
            
            00:00:01.000 --> 00:00:04.000
            Här står vi nu på scenen
            
            00:00:05.000 --> 00:00:08.000
            med kronor och med svärd.
            """;
    private static final String SUBTITLES_EN = """
            WEBVTT
            
            00:00:01.000 --> 00:00:04.000
            Here we stand upon the stage
            
            00:00:05.000 --> 00:00:08.000
            with crowns and with swords.
            """;

    private final SpexflixProperties properties;
    private final Environment environment;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final SpexregisterClient spexregisterClient;
    private final SpexService spexService;
    private final SpexifyClient spexifyClient;
    private final Keycloak keycloak;
    private final MediaStorage storage;
    private final Ffmpeg ffmpeg;
    private final ProcessingWorker worker;
    private final VideoRepository videoRepository;
    private final VideoKindRepository videoKindRepository;
    private final ProcessingJobRepository jobRepository;
    private final AppUserRepository userRepository;
    private final WatchProgressRepository progressRepository;
    private final WatchListEntryRepository watchListRepository;
    private final AuditEntryRepository auditRepository;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public SampleDataImporter(final SpexflixProperties properties, final Environment environment, final JdbcTemplate jdbcTemplate,
                              final TransactionTemplate transactionTemplate, final SpexregisterClient spexregisterClient,
                              final SpexService spexService, final SpexifyClient spexifyClient, final Keycloak keycloak,
                              final MediaStorage storage, final Ffmpeg ffmpeg, final ProcessingWorker worker,
                              final VideoRepository videoRepository, final VideoKindRepository videoKindRepository,
                              final ProcessingJobRepository jobRepository,
                              final AppUserRepository userRepository, final WatchProgressRepository progressRepository,
                              final WatchListEntryRepository watchListRepository, final AuditEntryRepository auditRepository,
                              final Clock clock) {
        this.properties = properties;
        this.environment = environment;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.spexregisterClient = spexregisterClient;
        this.spexService = spexService;
        this.spexifyClient = spexifyClient;
        this.keycloak = keycloak;
        this.storage = storage;
        this.ffmpeg = ffmpeg;
        this.worker = worker;
        this.videoRepository = videoRepository;
        this.videoKindRepository = videoKindRepository;
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
        this.progressRepository = progressRepository;
        this.watchListRepository = watchListRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    private static UserRepresentation spexregisterUser(final RealmResource realm, final String email) {
        return realm.users().searchByEmail(email, true).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Spexregister's sample user " + email
                        + " is missing; start Spexregister with its sample data first"));
    }

    private static void addSubtitle(final Video video, final String language, final String label) {
        final Subtitle subtitle = new Subtitle();

        subtitle.setVideo(video);
        subtitle.setLanguage(language);
        subtitle.setLabel(label);
        video.getSubtitles().add(subtitle);
    }

    /**
     * The kinds a new database starts with, as in the migration: show (the default), number, trailer, behind the
     * scenes and other.
     */
    private List<VideoKind> createKinds() {
        final String[][] names = {{"Föreställning", "Show"}, {"Nummer", "Number"}, {"Trailer", "Trailer"},
                {"Bakom kulisserna", "Behind the scenes"}, {"Övrigt", "Other"}};
        final List<VideoKind> kinds = new ArrayList<>();

        for (int i = 0; i < names.length; i++) {
            final VideoKind kind = new VideoKind();

            kind.setUuid(UUID.fromString("00000000-0000-7000-8000-00000000b00" + (i + 1)));
            kind.setNameSv(names[i][0]);
            kind.setNameEn(names[i][1]);
            kind.setPosition(i + 1);
            kind.setCreatedBy("system");
            kinds.add(videoKindRepository.save(kind));
        }
        return kinds;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onReady() throws IOException {
        if (!properties.data().importSampleData()) {
            return;
        }
        if (Arrays.asList(environment.getActiveProfiles()).contains("production")) {
            throw new IllegalStateException("Refusing to import sample data in the production profile");
        }
        importSampleData();
    }

    // ---------------------------------------------------------------------------------------------------- users

    public void importSampleData() throws IOException {
        final long started = System.nanoTime();
        final boolean large = "large".equalsIgnoreCase(properties.data().sampleDataSize());
        final boolean preprocessed = properties.data().sampleDataPreprocessed();

        log.info("Importing {} sample data{}", large ? "large" : "small", preprocessed ? ", processed at once" : "");
        // Truncating resets the ids, so sample ids are the same on every run and links between the applications hold
        jdbcTemplate.execute((java.sql.Connection connection) -> {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (final String table : TABLES) {
                    statement.execute("TRUNCATE TABLE " + table);
                }
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
        storage.wipe();

        final List<SpexDto> spex = spexWithRetry();
        final RealmResource realm = keycloak.realm(properties.keycloak().realm());
        final Map<String, AppUser> users = NotificationSuspender.whileSuspended(realm, () -> createUsers(realm));
        final Path sourcesFolder = Files.createTempDirectory("spexflix-sample-");

        try {
            final List<SampleVideos.Source> sources = SampleVideos.generate(ffmpeg, sourcesFolder, large ? 12 : 60);
            final int count = large ? 300 : 15;
            final Map<String, Long> processedBy = new HashMap<>();
            final List<Long> jobs = new ArrayList<>();
            final List<VideoKind> kinds = Objects.requireNonNull(transactionTemplate.execute(_ -> createKinds()));

            for (int i = 0; i < count; i++) {
                final int index = i;
                final SampleVideos.Source source = sources.get(index == 13 ? 4 : index % 4);
                final boolean copyProcessed = preprocessed && processedBy.containsKey(source.key());
                final Long jobId = transactionTemplate.execute(_ -> createVideo(index, spex, kinds, source, !copyProcessed));

                if (copyProcessed) {
                    copyFrom(processedBy.get(source.key()), index);
                } else if (preprocessed && jobId != null) {
                    worker.run(claim(jobId));
                    transactionTemplate.executeWithoutResult(_ -> videoRepository.findById(jobRepository.findById(jobId).orElseThrow().getVideo().getId())
                            .filter(video -> video.getStatus() == VideoStatus.READY)
                            .ifPresent(video -> processedBy.put(source.key(), video.getId())));
                } else if (jobId != null) {
                    jobs.add(jobId);
                }
            }
            transactionTemplate.executeWithoutResult(_ -> createViewing(users.get(USER)));
            transactionTemplate.executeWithoutResult(_ -> createAudit(users.get(EDITOR)));
        } finally {
            FileSystemUtils.deleteRecursively(sourcesFolder);
        }
        log.info("Imported sample data in {} s", Duration.ofNanos(System.nanoTime() - started).toSeconds());
    }

    private Long claim(final Long jobId) {
        transactionTemplate.executeWithoutResult(_ -> {
            final ProcessingJob job = jobRepository.findById(jobId).orElseThrow();

            job.setStatus(JobStatus.RUNNING);
            job.setAttempts(1);
            job.setStartedAt(clock.instant());
            job.setLeaseUntil(clock.instant().plus(Duration.ofHours(1)));
        });
        return jobId;
    }

    private List<SpexDto> spexWithRetry() {
        for (int attempt = 1; ; attempt++) {
            try {
                final List<SpexDto> spex = spexregisterClient.allSpex();

                if (spex.isEmpty()) {
                    throw new IllegalStateException("Spexregister has no spex; import its sample data first");
                }
                return spex.stream().sorted(Comparator.comparing((SpexDto s) -> s.year() == null ? 0 : s.year())).toList();
            } catch (final SpexregisterClient.SpexregisterCallException e) {
                if (attempt == SPEXREGISTER_ATTEMPTS) {
                    throw new IllegalStateException("Spexregister is unavailable, so sample data cannot be imported", e);
                }
                log.info("Waiting for Spexregister ({}/{})", attempt, SPEXREGISTER_ATTEMPTS);
                try {
                    Thread.sleep(SPEXREGISTER_RETRY_INTERVAL);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for Spexregister", interrupted);
                }
            }
        }
    }

    /**
     * Gives Spexregister's sample users {@code admin@fgv.nu}, {@code editor@fgv.nu} and {@code user@fgv.nu} the roles
     * of the same name on this client, and creates {@code no-access@fgv.nu}, without roles, when it is missing. Refuses
     * without Spexregister's sample users, which keeps the import away from real environments.
     */
    private Map<String, AppUser> createUsers(final RealmResource realm) {
        final ClientResource client = realm.clients().get(realm.clients().findByClientId(SecurityUtil.CLIENT_ID).getFirst().getId());
        final String clientUuid = client.toRepresentation().getId();
        final Map<String, AppUser> users = new LinkedHashMap<>();

        users.put(ADMIN, grant(realm, client, clientUuid, spexregisterUser(realm, ADMIN), "USER", "EDITOR", "ADMIN"));
        users.put(EDITOR, grant(realm, client, clientUuid, spexregisterUser(realm, EDITOR), "USER", "EDITOR"));
        users.put(USER, grant(realm, client, clientUuid, spexregisterUser(realm, USER), "USER"));
        users.put(NO_ACCESS, recordUser(noAccessUser(realm)));
        return users;
    }

    private AppUser grant(final RealmResource realm, final ClientResource client, final String clientUuid,
                          final UserRepresentation user, final String... roles) {
        final List<RoleRepresentation> representations = Arrays.stream(roles)
                .map(role -> client.roles().get(role).toRepresentation())
                .toList();

        realm.users().get(user.getId()).roles().clientLevel(clientUuid).add(representations);
        return recordUser(user);
    }

    // ---------------------------------------------------------------------------------------------------- videos

    private AppUser recordUser(final UserRepresentation representation) {
        final Instant now = clock.instant();
        final AppUser user = new AppUser();

        user.setSubject(representation.getId());
        user.setUsername(representation.getUsername());
        user.setDisplayName(representation.getFirstName());
        user.setCreatedAt(now);
        user.setLastSeenAt(now);
        return transactionTemplate.execute(_ -> userRepository.save(user));
    }

    private UserRepresentation noAccessUser(final RealmResource realm) {
        final List<UserRepresentation> existing = realm.users().searchByEmail(NO_ACCESS, true);

        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        final CredentialRepresentation credential = new CredentialRepresentation();
        final UserRepresentation user = new UserRepresentation();

        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(properties.data().sampleUserPassword());
        credential.setTemporary(false);
        user.setUsername(NO_ACCESS);
        user.setEmail(NO_ACCESS);
        user.setFirstName("No");
        user.setLastName("Access");
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setCredentials(List.of(credential));

        try (Response response = realm.users().create(user)) {
            if (response.getStatus() != Response.Status.CREATED.getStatusCode()) {
                throw new IllegalStateException("Could not create user " + NO_ACCESS + " in Keycloak: " + response.getStatus());
            }

            final String path = response.getLocation().getPath();

            return realm.users().get(path.substring(path.lastIndexOf('/') + 1)).toRepresentation();
        }
    }

    /**
     * Creates a video with its original in place and, when asked, a queued job.
     *
     * @return the job's id, or null when the video copies another's processed files
     */
    private Long createVideo(final int index, final List<SpexDto> spex, final List<VideoKind> kinds,
                             final SampleVideos.Source source, final boolean queue) {
        final SpexDto picked = spex.get(index % spex.size());
        final Video video = new Video();
        final VideoKind kind = kinds.get(index < spex.size() ? 0 : index % kinds.size());
        final boolean show = kind == kinds.getFirst();

        video.setUuid(UUID.nameUUIDFromBytes(("spexflix-sample-video-" + index).getBytes(StandardCharsets.UTF_8)));
        spexService.copy(picked, video.getSpex());
        video.setKind(kind);
        video.setTitle(show ? String.valueOf(picked.title()) : picked.title() + " – " + kind.getNameSv().toLowerCase(java.util.Locale.ROOT));
        video.setYear(picked.year());
        video.setPerformance(show ? "Premiären" : null);
        video.setPerformanceDate(show && picked.year() != null ? LocalDate.of(picked.year(), 2, 14) : null);
        video.setDescription("Exempeldata: en genererad testbild med ton (" + source.key() + ").");
        video.setPublished(index != 3 && index != 5);
        video.setPublishedAt(video.isPublished() ? clock.instant().minus(Duration.ofDays(index)) : null);
        video.setTrashedAt(index == 7 ? clock.instant().minus(Duration.ofDays(1)) : null);
        video.setOriginalName(source.file().getFileName().toString());
        video.setOriginalExtension(MediaStorage.extensionOf(source.file().getFileName().toString()));
        video.setStatus(VideoStatus.PROCESSING);
        video.setCreatedBy("system");
        if (show) {
            addChapters(video, picked.id());
        }
        if (index % 3 == 0) {
            addSubtitle(video, "sv", "Svenska");
            addSubtitle(video, "en", "English");
        }
        videoRepository.save(video);
        try {
            Files.createDirectories(storage.originals(video));
            Files.copy(source.file(), storage.original(video));
            video.setOriginalSize(Files.size(storage.original(video)));
            for (final Subtitle subtitle : video.getSubtitles()) {
                final Path file = storage.subtitle(video, subtitle.getLanguage());

                Files.createDirectories(file.getParent());
                Files.writeString(file, "sv".equals(subtitle.getLanguage()) ? SUBTITLES_SV : SUBTITLES_EN, StandardCharsets.UTF_8);
            }
        } catch (final IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (!queue) {
            return null;
        }

        final ProcessingJob job = new ProcessingJob();

        job.setVideo(video);
        job.setSource(storage.root().relativize(storage.original(video)).toString());
        job.setCreatedAt(clock.instant().plusMillis(index));
        jobRepository.save(job);
        return job.getId();
    }

    private void addChapters(final Video video, final UUID spexId) {
        final List<SpexifyClient.Track> tracks = spexifyClient.tracks(spexId);

        for (int i = 0; i < 5; i++) {
            final Chapter chapter = new Chapter();
            final SpexifyClient.Track track = i < tracks.size() ? tracks.get(i) : null;

            chapter.setVideo(video);
            chapter.setStartSeconds(i * 10);
            chapter.setTitle(track != null ? track.title() : CHAPTERS.get(i));
            chapter.setSpexifyTrackId(track == null ? null : UUID.fromString(track.id()));
            chapter.setSpexifyTrackTitle(track == null ? null : track.title());
            video.getChapters().add(chapter);
        }
    }

    /**
     * Gives a video the processed files of another made from the same original, for the preprocessed mode.
     */
    private void copyFrom(final Long sourceVideoId, final int index) {
        transactionTemplate.executeWithoutResult(_ -> {
            final Video from = videoRepository.findById(sourceVideoId).orElseThrow();
            final UUID uuid = UUID.nameUUIDFromBytes(("spexflix-sample-video-" + index).getBytes(StandardCharsets.UTF_8));
            final Video to = videoRepository.findAll().stream().filter(video -> video.getUuid().equals(uuid)).findFirst().orElseThrow();

            try {
                FileSystemUtils.copyRecursively(storage.generation(from, from.getGeneration()), storage.generation(to, from.getGeneration()));
            } catch (final IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            to.setGeneration(from.getGeneration());
            to.setStatus(VideoStatus.READY);
            to.setDurationMs(from.getDurationMs());
            to.setWidth(from.getWidth());
            to.setHeight(from.getHeight());
            to.setHasThumbnails(from.isHasThumbnails());
            from.getRenditions().forEach(rendition -> {
                final var copy = new nu.fgv.spexflix.server.video.Rendition();

                copy.setVideo(to);
                copy.setHeight(rendition.getHeight());
                copy.setWidth(rendition.getWidth());
                copy.setBitrateKbps(rendition.getBitrateKbps());
                copy.setCopied(rendition.isCopied());
                copy.setSize(rendition.getSize());
                to.getRenditions().add(copy);
            });
        });
    }

    private void createViewing(final AppUser user) {
        final List<Video> videos = videoRepository.findAll().stream()
                .filter(video -> video.isPublished() && !video.isTrashed() && video.getStatus() != VideoStatus.FAILED)
                .limit(4)
                .toList();
        final Instant now = clock.instant();

        for (int i = 0; i < Math.min(2, videos.size()); i++) {
            final WatchProgress progress = new WatchProgress();

            progress.setUserId(user.getId());
            progress.setVideoId(videos.get(i).getId());
            progress.setPositionSeconds(i == 0 ? 20 : 3600);
            progress.setFinished(i == 1);
            progress.setUpdatedAt(now.minus(Duration.ofHours(i + 1)));
            progressRepository.save(progress);
        }
        for (int i = 2; i < videos.size(); i++) {
            final WatchListEntry entry = new WatchListEntry();

            entry.setUserId(user.getId());
            entry.setVideoId(videos.get(i).getId());
            entry.setAddedAt(now.minus(Duration.ofDays(i)));
            watchListRepository.save(entry);
        }
    }

    private void createAudit(final AppUser editor) {
        int minutes = 0;

        for (final Video video : videoRepository.findAll().stream().limit(8).toList()) {
            final AuditEntry entry = new AuditEntry();

            entry.setAt(clock.instant().minus(Duration.ofMinutes(minutes += 41)));
            entry.setActorSubject(editor.getSubject());
            entry.setActorName(editor.getDisplayName());
            entry.setAction("UPLOAD");
            entry.setEntityType("VIDEO");
            entry.setEntityId(video.getUuid().toString());
            entry.setSummary("Laddade upp filen till " + video.getTitle());
            auditRepository.save(entry);
        }
    }
}

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

package nu.fgv.spexflix.server.imports;

import nu.fgv.spexflix.server.imports.ImportEngine.Batch;
import nu.fgv.spexflix.server.imports.ImportEngine.Handler;
import nu.fgv.spexflix.server.imports.ImportEngine.ItemResult;
import nu.fgv.spexflix.server.imports.ImportRecords.ImportCollection;
import nu.fgv.spexflix.server.imports.ImportRecords.ImportVideo;
import nu.fgv.spexflix.server.util.Uuids;
import nu.fgv.spexflix.server.util.error.BadRequestException;
import nu.fgv.spexflix.server.video.CollectionService;
import nu.fgv.spexflix.server.video.VideoDtos;
import nu.fgv.spexflix.server.video.VideoKind;
import nu.fgv.spexflix.server.video.VideoKindService;
import nu.fgv.spexflix.server.video.VideoService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Spexflix's import API (spec 5.8): videos with their originals, queued for processing as uploads are, and collections
 * of them, e.g. the acts of one show. Everything arrives unpublished.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class SpexflixImport {

    public static final String VIDEO = "VIDEO";
    public static final String COLLECTION = "COLLECTION";

    private final ImportEngine engine;
    private final ImportFileStore files;
    private final VideoService videos;
    private final CollectionService collections;
    private final VideoKindService kinds;

    public SpexflixImport(final ImportEngine engine, final ImportFileStore files, final VideoService videos,
                          final CollectionService collections, final VideoKindService kinds) {
        this.engine = engine;
        this.files = files;
        this.videos = videos;
        this.collections = collections;
        this.kinds = kinds;
    }

    public List<ItemResult> run(final ImportRecords.Batch input, final boolean dryRun) {
        final int size = (input.videos() == null ? 0 : input.videos().size()) + (input.collections() == null ? 0 : input.collections().size());

        if (size > ImportEngine.MAX_RECORDS) {
            throw new BadRequestException("error.import.tooMany", ImportEngine.MAX_RECORDS);
        }

        final Batch batch = new Batch(dryRun, Boolean.TRUE.equals(input.createMissingKinds()));
        final List<ItemResult> results = new ArrayList<>();

        results.addAll(engine.run(videoHandler, input.videos(), batch));
        results.addAll(engine.run(collectionHandler, input.collections(), batch));
        return results;
    }

    public int forget(final String sourcePrefix) {
        return engine.forget(sourcePrefix, List.of(collectionHandler, videoHandler));
    }

    private @Nullable UUID videoKind(final @Nullable String name, final Batch batch) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return kinds.match(name).map(VideoKind::getUuid).orElseGet(() -> {
            if (batch.createMissingKinds()) {
                batch.warn("Ny filmsort: " + name.strip());
                return kinds.createForImport(name.strip()).getUuid();
            }
            throw new BadRequestException("error.kind.unknown", name,
                    kinds.kinds().stream().map(VideoKindService.VideoKindDto::nameSv).collect(Collectors.joining(", ")));
        });
    }

    private final Handler<ImportVideo> videoHandler = new Handler<>() {

        @Override
        public String kind() {
            return VIDEO;
        }

        @Override
        public String sourceKey(final ImportVideo record) {
            return record.sourceKey();
        }

        @Override
        public UUID apply(final ImportVideo record, final @Nullable UUID existing, final Batch batch) {
            final java.nio.file.Path file = files.require(record.file().sha256());
            final VideoDtos.VideoInput input = new VideoDtos.VideoInput(record.spexIds(), record.title(), record.year(),
                    videoKind(record.kind(), batch), record.performance(), record.performanceDate(), record.description());
            final UUID id = existing == null ? videos.create(input).id() : videos.update(existing, input).id();

            if (!batch.dryRun()) {
                videos.importFile(id, file, ImportFileStore.normalize(record.file().sha256()),
                        Objects.requireNonNullElse(record.file().name(), "video.mp4"));
            }
            if (record.chapters() != null) {
                videos.setChapters(id, record.chapters().stream()
                        .map(chapter -> new VideoDtos.ChapterInput(chapter.startSeconds(), chapter.title(), null, null)).toList());
            }
            return id;
        }

        @Override
        public @Nullable Long version(final UUID id) {
            return videos.recordVersion(id);
        }

        @Override
        public boolean forget(final UUID id) {
            return videos.forget(id);
        }
    };

    private final Handler<ImportCollection> collectionHandler = new Handler<>() {

        @Override
        public String kind() {
            return COLLECTION;
        }

        @Override
        public String sourceKey(final ImportCollection record) {
            return record.sourceKey();
        }

        @Override
        public UUID apply(final ImportCollection record, final @Nullable UUID existing, final Batch batch) {
            final List<UUID> ids = new ArrayList<>();

            for (final String key : record.videoSourceKeys()) {
                final UUID video = engine.recordOf(VIDEO, key);

                if (video != null) {
                    ids.add(video);
                } else if (!(batch.dryRun() && batch.planned(key))) {
                    throw new BadRequestException("error.field.invalid", "videoSourceKeys");
                }
            }
            if (batch.dryRun() && ids.size() < record.videoSourceKeys().size()) {
                return Uuids.v7();
            }

            final CollectionService.CollectionInput input = new CollectionService.CollectionInput(record.title(), record.description(), ids);

            return existing == null ? collections.create(input).id() : collections.update(existing, input).id();
        }

        @Override
        public @Nullable Long version(final UUID id) {
            return collections.recordVersion(id);
        }

        @Override
        public boolean forget(final UUID id) {
            return collections.forget(id);
        }
    };
}

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

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The records Spexflix's import API accepts (spec 5.8). Video kinds are given by name or alias; spex by their
 * Spexipedia ids; a collection's videos by the source keys of video records.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class ImportRecords {

    private ImportRecords() {
    }

    public record FileRef(String sha256, @Nullable String name) {
    }

    public record Batch(@Nullable Boolean createMissingKinds, @Nullable List<ImportVideo> videos,
                        @Nullable List<ImportCollection> collections) {
    }

    public record ImportVideo(String sourceKey, @Nullable List<UUID> spexIds, @Nullable String kind,
                              @Nullable String title,
                              @Nullable Integer year, @Nullable String performance, @Nullable String performanceDate,
                              @Nullable String description, FileRef file, @Nullable List<ImportChapter> chapters) {
    }

    public record ImportChapter(int startSeconds, String title) {
    }

    public record ImportCollection(String sourceKey, String title, @Nullable String description,
                                   List<String> videoSourceKeys) {
    }
}

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

package nu.fgv.spexflix.server.processing;

import nu.fgv.spexflix.server.config.SpexflixProperties;
import nu.fgv.spexflix.server.video.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The media folder: generations of processed files, deleting videos and wiping for the sample data.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class MediaStorageTest {

    @TempDir
    private Path root;
    private MediaStorage storage;
    private Video video;

    @BeforeEach
    void setUp() throws Exception {
        storage = new MediaStorage(new SpexflixProperties(null, null, null, new SpexflixProperties.Media(root, DataSize.ofGigabytes(1), null, null, null),
                null, null, null, null));
        video = new Video();
        video.setOriginalExtension("mp4");
        for (final int generation : new int[]{1, 2, 3}) {
            Files.createDirectories(storage.generation(video, generation));
        }
        Files.createDirectories(storage.subtitle(video, "sv").getParent());
        Files.writeString(storage.subtitle(video, "sv"), "WEBVTT");
        Files.createDirectories(storage.originals(video));
        Files.write(storage.original(video), new byte[10]);
    }

    @Test
    void onlyTheKeptGenerationRemainsAndSubtitlesStay() throws Exception {
        storage.deleteOtherGenerations(video, 2);

        assertThat(storage.generation(video, 1)).doesNotExist();
        assertThat(storage.generation(video, 2)).exists();
        assertThat(storage.generation(video, 3)).doesNotExist();
        assertThat(storage.subtitle(video, "sv")).exists();
    }

    @Test
    void deletingAVideoRemovesItsOriginalAndProcessedFiles() throws Exception {
        storage.deleteVideo(video);

        assertThat(storage.originals(video)).doesNotExist();
        assertThat(storage.hls(video)).doesNotExist();
        storage.deleteOtherGenerations(video, 1);
    }

    @Test
    void sizesAreCountedPerFolderAndWipingEmptiesThem() throws Exception {
        assertThat(storage.size("originals")).isEqualTo(10);
        assertThat(storage.size("missing")).isZero();
        assertThat(storage.freeSpace()).isPositive();

        storage.wipe();

        assertThat(root.resolve("originals")).isEmptyDirectory();
        assertThat(root.resolve("hls")).isEmptyDirectory();
    }

    @Test
    void theExtensionIsTakenFromTheFileName() {
        assertThat(MediaStorage.extensionOf("Cleopatra.MKV")).isEqualTo("mkv");
        assertThat(MediaStorage.extensionOf("utan")).isEqualTo("bin");
    }
}

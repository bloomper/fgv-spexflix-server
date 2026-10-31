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
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The media folder. Video files are named by id, never by title:
 *
 * <pre>
 * originals/<uuid>/original.<ext>               the uploaded file, the archive copy; never served
 * originals/<uuid>/pending-<job>.<ext>          a replacement waiting to be processed
 * hls/<uuid>/<generation>/master.m3u8           the renditions, thumbnails and poster, served by spexflix-media
 * hls/<uuid>/subtitles/<language>.vtt           subtitles, kept across generations
 * uploads/                                      unfinished uploads
 * trash/                                        nothing: trashed videos keep their files until deleted
 * </pre>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class MediaStorage {

    private static final List<String> FOLDERS = List.of("originals", "hls", "uploads");

    private final Path root;

    public MediaStorage(final SpexflixProperties properties) {
        this.root = properties.media().root().toAbsolutePath().normalize();
        for (final String folder : FOLDERS) {
            try {
                Files.createDirectories(root.resolve(folder));
            } catch (final IOException e) {
                throw new UncheckedIOException("Could not create " + root.resolve(folder), e);
            }
        }
    }

    public Path root() {
        return root;
    }

    public Path originals(final Video video) {
        return root.resolve("originals").resolve(video.getUuid());
    }

    public Path original(final Video video) {
        return originals(video).resolve("original." + video.getOriginalExtension());
    }

    public Path hls(final Video video) {
        return root.resolve("hls").resolve(video.getUuid());
    }

    public Path generation(final Video video, final int generation) {
        return hls(video).resolve(Integer.toString(generation));
    }

    public Path poster(final Video video) {
        return generation(video, video.getGeneration()).resolve("poster.jpg");
    }

    public Path uploadedPoster(final Video video) {
        return hls(video).resolve("poster-uploaded.jpg");
    }

    public Path subtitle(final Video video, final String language) {
        return hls(video).resolve("subtitles").resolve(language + ".vtt");
    }

    public Path uploadFile(final String uploadId) {
        return root.resolve("uploads").resolve(uploadId);
    }

    public Path folder(final String name) {
        return root.resolve(name);
    }

    public void deleteVideo(final Video video) throws IOException {
        FileSystemUtils.deleteRecursively(originals(video));
        FileSystemUtils.deleteRecursively(hls(video));
    }

    /**
     * Deletes every generation of the video's processed files but the given one.
     */
    public void deleteOtherGenerations(final Video video, final int keep) throws IOException {
        final Path folder = hls(video);

        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> children = Files.list(folder)) {
            for (final Path child : children.toList()) {
                final String name = child.getFileName().toString();

                if (name.matches("\\d+") && Integer.parseInt(name) != keep) {
                    FileSystemUtils.deleteRecursively(child);
                }
            }
        }
    }

    /**
     * Bytes used under a folder of the media root.
     */
    public long size(final String folder) throws IOException {
        final Path path = root.resolve(folder);

        if (!Files.isDirectory(path)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(path)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (final IOException e) {
                    return 0;
                }
            }).sum();
        }
    }

    public long freeSpace() throws IOException {
        return Files.getFileStore(root).getUsableSpace();
    }

    /**
     * Empties the media folders, for the sample data import. The folders stay, since containers mount them.
     */
    public void wipe() throws IOException {
        for (final String folder : FOLDERS) {
            final Path path = root.resolve(folder);

            Files.createDirectories(path);
            try (Stream<Path> children = Files.list(path)) {
                for (final Path child : children.toList()) {
                    FileSystemUtils.deleteRecursively(child);
                }
            }
        }
    }

    public static String extensionOf(final String fileName) {
        final int dot = fileName.lastIndexOf('.');
        final String extension = dot < 0 ? "bin" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);

        return extension.matches("[a-z0-9]{1,10}") ? extension : "bin";
    }
}

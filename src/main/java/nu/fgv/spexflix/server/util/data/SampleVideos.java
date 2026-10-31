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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexflix.server.processing.Ffmpeg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates sample originals with ffmpeg, never real recordings: test patterns with a tone, one for each path through
 * processing. The title and a running timecode are burnt in where ffmpeg has drawtext.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
final class SampleVideos {

    /**
     * A generated original.
     *
     * @param key  what it tests
     * @param file the file
     */
    record Source(String key, Path file) {
    }

    private static final List<String> FONTS = List.of(
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "/System/Library/Fonts/Supplemental/Arial Bold.ttf");

    private SampleVideos() {
    }

    /**
     * H.264/AAC 1080p (copied into the top rendition), H.264 720p (no rendition above it), VP9 480p (re-encoded),
     * interlaced MPEG-2 (deinterlaced), and a file that is not video.
     */
    static List<Source> generate(final Ffmpeg ffmpeg, final Path folder, final int seconds) throws IOException {
        Files.createDirectories(folder);

        final List<Source> sources = new ArrayList<>();

        sources.add(make(ffmpeg, folder, "h264-1080p", "mp4", 1920, 1080, seconds, false,
                List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k")));
        sources.add(make(ffmpeg, folder, "h264-720p", "mp4", 1280, 720, Math.max(seconds * 2 / 3, 6), false,
                List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k")));
        sources.add(fallback(ffmpeg, folder, "vp9-480p", 854, 480, Math.max(seconds / 2, 6)));
        sources.add(make(ffmpeg, folder, "mpeg2-interlaced", "mpg", 720, 576, Math.max(seconds / 2, 6), true,
                List.of("-c:v", "mpeg2video", "-b:v", "6M", "-flags", "+ilme+ildct", "-top", "1", "-c:a", "mp2", "-b:a", "192k")));

        final Path text = folder.resolve("not-video.txt");

        Files.writeString(text, "Det här är ingen video.\n", StandardCharsets.UTF_8);
        sources.add(new Source("not-video", text));
        return sources;
    }

    private static Source fallback(final Ffmpeg ffmpeg, final Path folder, final String key, final int width, final int height,
                                   final int seconds) throws IOException {
        try {
            return make(ffmpeg, folder, key, "webm", width, height, seconds, false,
                    List.of("-c:v", "libvpx-vp9", "-b:v", "800k", "-deadline", "realtime", "-cpu-used", "8", "-c:a", "libopus", "-b:a", "96k"));
        } catch (final IOException e) {
            log.info("No VP9 encoder, so the sample uses MPEG-4 instead: {}", e.getMessage());
            return make(ffmpeg, folder, key, "avi", width, height, seconds, false, List.of("-c:v", "mpeg4", "-q:v", "5", "-c:a", "mp3"));
        }
    }

    private static Source make(final Ffmpeg ffmpeg, final Path folder, final String key, final String extension, final int width,
                               final int height, final int seconds, final boolean interlaced, final List<String> codec) throws IOException {
        final Path file = folder.resolve(key + "." + extension);
        final String pattern = "testsrc2=size=%dx%d:rate=25:duration=%d".formatted(width, height, seconds);
        final String font = FONTS.stream().filter(path -> Files.isRegularFile(Path.of(path))).findFirst().orElse(null);
        final List<String> arguments = new ArrayList<>(List.of("-f", "lavfi", "-i", pattern,
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=" + seconds));

        if (font != null) {
            try {
                ffmpeg.ffmpeg(withFilter(arguments, codec, file, interlaced,
                        ("drawtext=fontfile='%s':text='%s':fontcolor=white:fontsize=%d:box=1:boxcolor=black@0.6:x=40:y=40,"
                                + "drawtext=fontfile='%s':timecode='00\\:00\\:00\\:00':rate=25:fontcolor=white:fontsize=%d:box=1:boxcolor=black@0.6:x=40:y=h-th-40")
                                .formatted(font, key, height / 14, font, height / 14)));
                return new Source(key, file);
            } catch (final IOException e) {
                log.debug("No drawtext, so the sample has no burnt-in text: {}", e.getMessage());
            }
        }
        ffmpeg.ffmpeg(withFilter(arguments, codec, file, interlaced, null));
        return new Source(key, file);
    }

    private static List<String> withFilter(final List<String> input, final List<String> codec, final Path file,
                                           final boolean interlaced, final String filter) {
        final List<String> arguments = new ArrayList<>(input);
        final String video = filter == null ? (interlaced ? "interlace" : null) : (interlaced ? filter + ",interlace" : filter);

        if (video != null) {
            arguments.addAll(List.of("-vf", video));
        }
        arguments.addAll(List.of("-shortest"));
        arguments.addAll(codec);
        arguments.add(file.toString());
        return arguments;
    }
}

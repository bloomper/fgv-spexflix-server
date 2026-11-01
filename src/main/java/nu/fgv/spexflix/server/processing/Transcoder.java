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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * Turns an original into a generation folder: HLS renditions with aligned keyframes and 6-second segments, a master
 * playlist, timeline thumbnails (sprites and a WebVTT index) and a poster.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class Transcoder {

    static final int SEGMENT_SECONDS = 6;
    static final int THUMB_WIDTH = 160;
    static final int TILES = 10;
    private static final String LOUDNESS = "loudnorm=I=-16:TP=-1.5:LRA=11";

    private final Ffmpeg ffmpeg;
    private final VideoProbe probe;

    public Transcoder(final Ffmpeg ffmpeg, final VideoProbe probe) {
        this.ffmpeg = ffmpeg;
        this.probe = probe;
    }

    /**
     * What was made.
     */
    public record Output(VideoProbe.Result probe, List<Made> renditions, boolean thumbnails) {
    }

    public record Made(RenditionPlan.Step step, int bitrateKbps, long size) {
    }

    /**
     * Thrown when the file is not video ffprobe can read.
     */
    public static class NotVideoException extends IOException {

        public NotVideoException() {
            super("The file is not video that can be read");
        }
    }

    /**
     * Processes the source into {@code target}, which is created anew.
     *
     * @param posterSeconds where to take the poster from, or null for a tenth into the video
     * @param progress      receives 0–100
     */
    public Output transcode(final Path source, final Path target, final @Nullable Double posterSeconds,
                            final IntConsumer progress) throws IOException {
        final VideoProbe.Result info = probe.probe(source).orElseThrow(NotVideoException::new);
        final List<RenditionPlan.Step> steps = RenditionPlan.plan(info);
        final double durationMicros = Math.max(info.durationMs(), 1) * 1000.0;
        final int stages = steps.size() + 1;
        final List<Made> made = new ArrayList<>();

        FileSystemUtils.deleteRecursively(target);
        Files.createDirectories(target);
        for (int i = 0; i < steps.size(); i++) {
            final RenditionPlan.Step step = steps.get(i);
            final Path folder = target.resolve(Integer.toString(step.height()));
            final int stage = i;

            Files.createDirectories(folder);
            ffmpeg.ffmpeg(renditionArguments(source, folder, step, info), micros ->
                    progress.accept((int) Math.min(99, 100.0 * (stage + Math.min(1, micros / durationMicros)) / stages)));

            final long size = folderSize(folder);
            final int bitrate = step.copy() ? (int) Math.max(1, size * 8 / Math.max(info.durationMs(), 1)) : step.bitrateKbps();

            made.add(new Made(step, bitrate, size));
        }
        writeMaster(target, made, info.audioCodec() != null);

        final boolean thumbnails = thumbnails(source, target.resolve("thumbs"), info);

        poster(source, target.resolve("poster.jpg"), posterSeconds == null ? info.durationMs() / 10000.0 : posterSeconds);
        progress.accept(100);
        return new Output(info, made, thumbnails);
    }

    static List<String> renditionArguments(final Path source, final Path folder, final RenditionPlan.Step step,
                                           final VideoProbe.Result info) {
        final List<String> arguments = input(source);

        arguments.addAll(List.of("-map", "0:v:0"));

        if (info.audioCodec() != null) {
            arguments.addAll(List.of("-map", "0:a:0"));
        }
        if (step.copy()) {
            arguments.addAll(List.of("-c", "copy"));
        } else {
            final String scale = "scale=%d:%d".formatted(step.width(), step.height());

            arguments.addAll(List.of("-vf", info.interlaced() ? "yadif=mode=send_frame," + scale : scale,
                    "-c:v", "libx264", "-preset", "veryfast", "-profile:v", "high", "-pix_fmt", "yuv420p",
                    "-b:v", step.bitrateKbps() + "k", "-maxrate", step.bitrateKbps() * 3 / 2 + "k",
                    "-bufsize", step.bitrateKbps() * 2 + "k",
                    "-force_key_frames", "expr:gte(t,n_forced*" + SEGMENT_SECONDS + ")", "-sc_threshold", "0"));
            if (info.audioCodec() != null) {
                arguments.addAll(List.of("-af", LOUDNESS, "-c:a", "aac", "-b:a", "128k", "-ac", "2", "-ar", "48000"));
            }
        }
        arguments.addAll(List.of("-f", "hls", "-hls_time", Integer.toString(SEGMENT_SECONDS), "-hls_playlist_type", "vod",
                "-hls_segment_filename", folder.resolve("seg_%05d.ts").toString(), folder.resolve("index.m3u8").toString()));
        return arguments;
    }

    static String master(final List<Made> renditions, final boolean audio) {
        final StringBuilder master = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");

        for (final Made made : renditions) {
            master.append("#EXT-X-STREAM-INF:BANDWIDTH=%d,RESOLUTION=%dx%d\n".formatted(
                            (made.bitrateKbps() + (audio ? 128 : 0)) * 1000, made.step().width(), made.step().height()))
                    .append(made.step().height()).append("/index.m3u8\n");
        }
        return master.toString();
    }

    private static void writeMaster(final Path target, final List<Made> renditions, final boolean audio) throws IOException {
        Files.writeString(target.resolve("master.m3u8"), master(renditions, audio), StandardCharsets.UTF_8);
    }

    /**
     * Timeline thumbnails: sprites of 10 × 10 frames and a WebVTT index pointing into them.
     */
    private boolean thumbnails(final Path source, final Path folder, final VideoProbe.Result info) {
        final double seconds = info.durationMs() / 1000.0;
        final int interval = (int) Math.clamp(Math.ceil(seconds / 100), 2, 10);
        final int height = (int) (Math.round((double) THUMB_WIDTH * info.height() / info.width() / 2) * 2);

        try {
            Files.createDirectories(folder);
            final List<String> arguments = input(source);

            arguments.addAll(List.of("-vf", "fps=1/%d,scale=%d:%d,tile=%dx%d".formatted(interval, THUMB_WIDTH, height, TILES, TILES),
                    "-q:v", "5", folder.resolve("sprite_%03d.jpg").toString()));
            ffmpeg.ffmpeg(arguments);
            Files.writeString(folder.resolve("thumbs.vtt"), thumbnailIndex(seconds, interval, height), StandardCharsets.UTF_8);
            return true;
        } catch (final IOException e) {
            log.warn("Could not make timeline thumbnails: {}", e.getMessage());
            return false;
        }
    }

    static String thumbnailIndex(final double seconds, final int interval, final int height) {
        final StringBuilder vtt = new StringBuilder("WEBVTT\n\n");
        final int count = (int) Math.ceil(seconds / interval);

        for (int i = 0; i < count; i++) {
            final int sprite = i / (TILES * TILES) + 1;
            final int tile = i % (TILES * TILES);

            vtt.append(timestamp(i * interval)).append(" --> ").append(timestamp(Math.min(seconds, (i + 1.0) * interval))).append('\n')
                    .append("sprite_%03d.jpg#xywh=%d,%d,%d,%d".formatted(sprite, tile % TILES * THUMB_WIDTH, tile / TILES * height, THUMB_WIDTH, height))
                    .append("\n\n");
        }
        return vtt.toString();
    }

    static String timestamp(final double seconds) {
        final long millis = Math.round(seconds * 1000);

        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", millis / 3_600_000, millis / 60_000 % 60, millis / 1000 % 60, millis % 1000);
    }

    /**
     * Takes a frame as poster, 1280 pixels wide at most.
     */
    public void poster(final Path source, final Path target, final double seconds) throws IOException {
        final List<String> arguments = new ArrayList<>(List.of("-ss", String.format(Locale.ROOT, "%.3f", Math.max(0, seconds))));

        arguments.addAll(input(source));
        arguments.addAll(List.of("-frames:v", "1", "-vf", "scale='min(1280,iw)':-2", "-q:v", "3", target.toString()));
        ffmpeg.ffmpeg(arguments);
    }

    /**
     * {@code -i source}, after the options that keep ffmpeg to what the source should be: an uploaded video, or the
     * HLS renditions Spexflix made itself.
     */
    static List<String> input(final Path source) {
        final List<String> arguments = new ArrayList<>(source.getFileName().toString().endsWith(".m3u8") ? Ffmpeg.HLS_INPUT : Ffmpeg.VIDEO_INPUT);

        arguments.addAll(List.of("-i", source.toString()));
        return arguments;
    }

    private static long folderSize(final Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (final IOException e) {
                    return 0;
                }
            }).sum();
        }
    }
}

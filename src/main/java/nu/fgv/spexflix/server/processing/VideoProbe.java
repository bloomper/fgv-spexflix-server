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

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Reads what a video file is with ffprobe: codecs, size, frame rate, scan type, duration and audio.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class VideoProbe {

    private final Ffmpeg ffmpeg;
    private final JsonMapper jsonMapper;

    public VideoProbe(final Ffmpeg ffmpeg, final JsonMapper jsonMapper) {
        this.ffmpeg = ffmpeg;
        this.jsonMapper = jsonMapper;
    }

    /**
     * The file's video, or empty when it is not video ffprobe can read.
     */
    public Optional<Result> probe(final Path file) {
        try {
            return parse(jsonMapper.readTree(ffmpeg.probe(List.of("-v", "error", "-print_format", "json", "-show_format",
                    "-show_streams", file.toString()))));
        } catch (final IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static Optional<Result> parse(final JsonNode root) {
        JsonNode video = null;
        JsonNode audio = null;

        for (final JsonNode stream : root.path("streams")) {
            final String type = stream.path("codec_type").asString("");
            final boolean attachedPicture = stream.path("disposition").path("attached_pic").asInt(0) == 1;

            if (video == null && "video".equals(type) && !attachedPicture) {
                video = stream;
            } else if (audio == null && "audio".equals(type)) {
                audio = stream;
            }
        }
        if (video == null || video.path("width").asInt(0) <= 0) {
            return Optional.empty();
        }

        final double seconds = number(root.path("format").path("duration").asString(video.path("duration").asString("0")));
        final String fieldOrder = video.path("field_order").asString("progressive");

        return Optional.of(new Result(
                video.path("codec_name").asString(""),
                video.path("width").asInt(),
                video.path("height").asInt(),
                frameRate(video.path("avg_frame_rate").asString(video.path("r_frame_rate").asString("25/1"))),
                !fieldOrder.isEmpty() && !"progressive".equals(fieldOrder) && !"unknown".equals(fieldOrder),
                audio == null ? null : audio.path("codec_name").asString(""),
                Math.round(seconds * 1000)));
    }

    private static double frameRate(final String value) {
        final String[] parts = value.split("/");

        try {
            final double rate = parts.length == 2 ? Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]) : Double.parseDouble(value);

            return Double.isFinite(rate) && rate > 0 ? rate : 25;
        } catch (final NumberFormatException e) {
            return 25;
        }
    }

    private static double number(final String value) {
        try {
            return Double.parseDouble(value);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /**
     * What ffprobe found.
     *
     * @param videoCodec e.g. {@code h264}, {@code vp9}, {@code mpeg2video}
     * @param width      in pixels
     * @param height     in pixels
     * @param frameRate  frames per second
     * @param interlaced whether the video is interlaced and must be deinterlaced
     * @param audioCodec e.g. {@code aac}, or null without sound
     * @param durationMs length in milliseconds
     */
    public record Result(String videoCodec, int width, int height, double frameRate, boolean interlaced,
                         @Nullable String audioCodec, long durationMs) {

        /**
         * Whether the top rendition can copy the original's streams instead of re-encoding them: H.264 (and AAC or no
         * sound), progressive, at most 1080p.
         */
        public boolean copyable() {
            return "h264".equals(videoCodec) && !interlaced && height <= 1080
                    && (audioCodec == null || "aac".equals(audioCodec));
        }
    }
}

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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscoderTest {

    private static final SpexflixProperties PROPERTIES = new SpexflixProperties(null, null, null, null,
            new SpexflixProperties.Worker(true, 10), null, null, null);

    @Test
    void writesAMasterPlaylistWithEveryRendition() {
        final String master = Transcoder.master(List.of(
                new Transcoder.Made(new RenditionPlan.Step(720, 1280, 3000, false), 3000, 1),
                new Transcoder.Made(new RenditionPlan.Step(480, 854, 1200, false), 1200, 1)), true);

        assertThat(master).isEqualTo("""
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-STREAM-INF:BANDWIDTH=3128000,RESOLUTION=1280x720
                720/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=1328000,RESOLUTION=854x480
                480/index.m3u8
                """);
    }

    @Test
    void indexesThumbnailsIntoTheSprites() {
        final String vtt = Transcoder.thumbnailIndex(25, 10, 90);

        assertThat(vtt).startsWith("WEBVTT\n\n00:00:00.000 --> 00:00:10.000\nsprite_001.jpg#xywh=0,0,160,90\n")
                .contains("00:00:20.000 --> 00:00:25.000\nsprite_001.jpg#xywh=320,0,160,90");
        assertThat(Transcoder.thumbnailIndex(1010, 10, 90)).contains("sprite_002.jpg#xywh=0,0,160,90");
    }

    @Test
    void transcodesAClipToHls(@TempDir final Path folder) throws IOException {
        Assumptions.assumeTrue(hasFfmpeg(), "ffmpeg is not installed");

        final Ffmpeg ffmpeg = new Ffmpeg(PROPERTIES);
        final Path source = folder.resolve("clip.mp4");
        final List<Integer> progress = new ArrayList<>();

        ffmpeg.ffmpeg(List.of("-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25:duration=8", "-f", "lavfi", "-i",
                "sine=duration=8", "-shortest", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac",
                source.toString()));

        final Transcoder.Output output = new Transcoder(ffmpeg, new VideoProbe(ffmpeg, JsonMapper.builder().build()))
                .transcode(source, folder.resolve("1"), null, progress::add);

        assertThat(output.probe().height()).isEqualTo(360);
        assertThat(output.renditions()).singleElement().satisfies(made -> assertThat(made.step().copy()).isTrue());
        assertThat(Files.readString(folder.resolve("1/master.m3u8"))).contains("360/index.m3u8");
        assertThat(Files.readString(folder.resolve("1/360/index.m3u8"))).contains("#EXT-X-ENDLIST", "seg_00000.ts");
        assertThat(folder.resolve("1/thumbs/thumbs.vtt")).exists();
        assertThat(folder.resolve("1/poster.jpg")).exists();
        assertThat(progress).last().isEqualTo(100);
    }

    @Test
    void refusesWhatIsNotVideo(@TempDir final Path folder) throws IOException {
        Assumptions.assumeTrue(hasFfmpeg(), "ffmpeg is not installed");

        final Ffmpeg ffmpeg = new Ffmpeg(PROPERTIES);
        final Path text = Files.writeString(folder.resolve("notes.txt"), "not video");

        assertThatThrownBy(() -> new Transcoder(ffmpeg, new VideoProbe(ffmpeg, JsonMapper.builder().build()))
                .transcode(text, folder.resolve("1"), null, _ -> {
                })).isInstanceOf(Transcoder.NotVideoException.class);
    }

    private static boolean hasFfmpeg() {
        try {
            return new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0;
        } catch (final IOException | InterruptedException e) {
            return false;
        }
    }
}

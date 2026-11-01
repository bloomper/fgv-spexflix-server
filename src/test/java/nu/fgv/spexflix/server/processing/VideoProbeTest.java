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

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading ffprobe's answer: streams, interlacing, frame rates and whether the top rendition can be copied.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class VideoProbeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(final String text) {
        return JSON.readTree(text);
    }

    @Test
    void readsTheVideoAndAudioStreamsAndSkipsACoverPicture() {
        final VideoProbe.Result result = VideoProbe.parse(json("""
                {"streams": [
                    {"codec_type": "video", "codec_name": "mjpeg", "width": 600, "height": 600, "disposition": {"attached_pic": 1}},
                    {"codec_type": "video", "codec_name": "h264", "width": 1920, "height": 1080, "avg_frame_rate": "30000/1001", "field_order": "progressive"},
                    {"codec_type": "audio", "codec_name": "aac"}],
                 "format": {"duration": "61.5"}}""")).orElseThrow();

        assertThat(result.videoCodec()).isEqualTo("h264");
        assertThat(result.width()).isEqualTo(1920);
        assertThat(result.frameRate()).isCloseTo(29.97, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.durationMs()).isEqualTo(61_500);
        assertThat(result.copyable()).isTrue();
    }

    @Test
    void interlacedOrOtherCodecsOrLargerThan1080pAreReEncoded() {
        final VideoProbe.Result interlaced = VideoProbe.parse(json("""
                {"streams": [{"codec_type": "video", "codec_name": "mpeg2video", "width": 720, "height": 576, "field_order": "tt",
                              "r_frame_rate": "25", "duration": "10"}]}""")).orElseThrow();

        assertThat(interlaced.interlaced()).isTrue();
        assertThat(interlaced.audioCodec()).isNull();
        assertThat(interlaced.frameRate()).isEqualTo(25);
        assertThat(interlaced.durationMs()).isEqualTo(10_000);
        assertThat(interlaced.copyable()).isFalse();
        assertThat(new VideoProbe.Result("h264", 3840, 2160, 25, false, "aac", 1).copyable()).isFalse();
        assertThat(new VideoProbe.Result("h264", 1920, 1080, 25, false, "opus", 1).copyable()).isFalse();
        assertThat(new VideoProbe.Result("h264", 1920, 1080, 25, false, null, 1).copyable()).isTrue();
    }

    @Test
    void oddFrameRatesAndDurationsFallBack() {
        final VideoProbe.Result odd = VideoProbe.parse(json("""
                {"streams": [{"codec_type": "video", "codec_name": "vp9", "width": 640, "height": 360, "avg_frame_rate": "0/0", "field_order": "unknown"}],
                 "format": {"duration": "N/A"}}""")).orElseThrow();

        assertThat(odd.frameRate()).isEqualTo(25);
        assertThat(odd.durationMs()).isZero();
        assertThat(odd.interlaced()).isFalse();
        assertThat(VideoProbe.parse(json("{\"streams\": [{\"codec_type\": \"video\", \"avg_frame_rate\": \"abc\", \"width\": 2}]}")).orElseThrow().frameRate())
                .isEqualTo(25);
    }

    @Test
    void aFileWithoutVideoIsNotVideo() {
        assertThat(VideoProbe.parse(json("{\"streams\": [{\"codec_type\": \"audio\", \"codec_name\": \"mp3\"}]}"))).isEmpty();
        assertThat(VideoProbe.parse(json("{}"))).isEmpty();
    }
}

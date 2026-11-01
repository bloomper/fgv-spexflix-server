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

package nu.fgv.spexflix.server.video;

import nu.fgv.spexflix.server.util.error.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaControllerTest {

    @Test
    void convertsSubRipToWebVtt() {
        assertThat(MediaController.toWebVtt("1\r\n00:00:01,000 --> 00:00:04,500\r\nHej\r\n"))
                .isEqualTo("WEBVTT\n\n1\n00:00:01.000 --> 00:00:04.500\nHej\n");
        assertThat(MediaController.toWebVtt("﻿WEBVTT\n\n00:01.000 --> 00:02.000\nHej")).startsWith("WEBVTT");
        assertThatThrownBy(() -> MediaController.toWebVtt("just text")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void readsTheImageTypeFromTheContent() {
        assertThat(MediaController.imageType(new byte[]{(byte) 0xFF, (byte) 0xD8, 0, 0})).hasToString("image/jpeg");
        assertThat(MediaController.imageType(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0, 0})).hasToString("image/png");
        assertThat(MediaController.imageType("RIFF0000WEBPVP8 ".getBytes())).hasToString("image/webp");
        assertThat(MediaController.imageType("<svg>".getBytes())).isNull();
        assertThat(MediaController.imageType(new byte[0])).isNull();
        assertThat(MediaController.imageType("RIFF0000WAVEfmt ".getBytes())).isNull();
    }

    @Test
    void keepsWebVttAndConvertsSubRipWithWindowsLineEnds() {
        assertThat(MediaController.toWebVtt("WEBVTT\n\n00:01.000 --> 00:02.000\nHej")).isEqualTo("WEBVTT\n\n00:01.000 --> 00:02.000\nHej");
        assertThat(MediaController.toWebVtt("\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\nHej")).startsWith("WEBVTT\n\n1\n00:00:01.000");
    }
}

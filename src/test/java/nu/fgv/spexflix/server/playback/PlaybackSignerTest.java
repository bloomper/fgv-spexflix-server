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

package nu.fgv.spexflix.server.playback;

import nu.fgv.spexflix.server.config.SpexflixProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackSignerTest {

    @Test
    void signsLikeNginxSecureLink() {
        // echo -n "1893456000abc secret" | openssl md5 -binary | openssl base64 | tr +/ -_ | tr -d =
        assertThat(PlaybackSigner.signature("abc", 1893456000L, "secret")).isEqualTo("sNviqONfxzGbG5d2pvN1wQ");
    }

    @Test
    void buildsAPrefixForOneVideo() {
        final PlaybackSigner signer = new PlaybackSigner(new SpexflixProperties(null, null, null,
                new SpexflixProperties.Media(null, null, "https://media.example.org/", "secret", Duration.ofHours(4)),
                null, null, null, null));

        assertThat(signer.prefix("abc", Instant.ofEpochSecond(1893456000L)))
                .isEqualTo("https://media.example.org/v/1893456000/sNviqONfxzGbG5d2pvN1wQ/abc/");
    }
}

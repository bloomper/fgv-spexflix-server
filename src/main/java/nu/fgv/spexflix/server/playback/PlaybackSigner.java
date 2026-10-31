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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexflix.server.config.SpexflixProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

/**
 * Signs playback addresses the way nginx's {@code secure_link} checks them, so that the media server serves a video's
 * files without asking the API for every segment:
 *
 * <pre>
 * {base}/v/{expires}/{signature}/{uuid}/{path}
 * signature = base64url(md5("{expires}{uuid} {key}")), without padding
 * </pre>
 *
 * <p>One signature covers every file of one video until it expires, and nothing of any other video.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class PlaybackSigner {

    private static final String DEVELOPMENT_KEY = "spexflix-development-signing-key";

    private final String baseUrl;
    private final String key;

    public PlaybackSigner(final SpexflixProperties properties) {
        this.baseUrl = properties.media().baseUrl().replaceAll("/+$", "");

        final String configured = properties.media().signingKey();

        if (configured == null || configured.isBlank()) {
            log.warn("MEDIA_SIGNING_KEY is not set; playback addresses are signed with a development key");
            this.key = DEVELOPMENT_KEY;
        } else {
            this.key = configured.strip();
        }
    }

    /**
     * The address prefix for a video's files, ending with a slash.
     */
    public String prefix(final String uuid, final Instant expires) {
        final long epoch = expires.getEpochSecond();

        return "%s/v/%d/%s/%s/".formatted(baseUrl, epoch, signature(uuid, epoch, key), uuid);
    }

    static String signature(final String uuid, final long expires, final String key) {
        try {
            final byte[] digest = MessageDigest.getInstance("MD5").digest((expires + uuid + " " + key).getBytes(StandardCharsets.UTF_8));

            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

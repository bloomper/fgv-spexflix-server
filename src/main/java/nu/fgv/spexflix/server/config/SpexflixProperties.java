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

package nu.fgv.spexflix.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Spexflix's settings under {@code spexflix.*}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ConfigurationProperties(prefix = "spexflix")
public record SpexflixProperties(String timeZone,
                                 Keycloak keycloak,
                                 Security security,
                                 Media media,
                                 Worker worker,
                                 Spexregister spexregister,
                                 Spexify spexify,
                                 Data data) {

    public record Keycloak(String url, String realm, String adminClientId, String adminClientSecret) {
    }

    public record Security(List<String> authorizedParties) {
    }

    /**
     * @param root          the media folder
     * @param maxUploadSize the largest video accepted
     * @param baseUrl       the media hostname's address, e.g. {@code https://media.example.org}
     * @param signingKey    the secret shared with the media server (nginx {@code secure_link})
     * @param linkMargin    how long a playback address outlives the video's duration
     */
    public record Media(Path root, DataSize maxUploadSize, String baseUrl, String signingKey, Duration linkMargin) {
    }

    /**
     * @param enabled  whether this instance processes videos; only the worker container does
     * @param priority the nice value ffmpeg runs at
     */
    public record Worker(boolean enabled, int priority) {
    }

    public record Spexregister(String graphqlUrl) {
    }

    public record Spexify(String graphqlUrl) {
    }

    public record Data(boolean importSampleData, String sampleDataSize, boolean sampleDataPreprocessed,
                       String sampleUserPassword) {
    }
}

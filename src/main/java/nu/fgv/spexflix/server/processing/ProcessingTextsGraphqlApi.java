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

import nu.fgv.spexflix.server.util.StoredText;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.Locale;

/**
 * Why processing failed, in the reader's language: a video's failure and a job's error are kept as {@link StoredText}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ProcessingTextsGraphqlApi {

    private final MessageSource messages;

    public ProcessingTextsGraphqlApi(final MessageSource messages) {
        this.messages = messages;
    }

    @SchemaMapping(typeName = "Video", field = "failure")
    public @Nullable String failure(final VideoDto video, final Locale locale) {
        return text(video.failure(), locale);
    }

    @SchemaMapping(typeName = "ProcessingJob", field = "error")
    public @Nullable String error(final ProcessingService.JobDto job, final Locale locale) {
        return text(job.error(), locale);
    }

    private @Nullable String text(final @Nullable String stored, final Locale locale) {
        return stored == null ? null : StoredText.decode(stored).in(messages, locale);
    }
}

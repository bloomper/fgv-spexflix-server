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

package nu.fgv.spexflix.server.mcp;

import nu.fgv.spexflix.server.util.error.BadRequestException;
import nu.fgv.spexflix.server.util.error.ResourceNotFoundException;
import nu.fgv.spexflix.server.util.error.SpexipediaUnavailableException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * What the MCP tools share: turning the services' refusals into tool errors, and links to Spexflix Web.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class McpToolSupport {

    private final MessageSource messageSource;
    private final @Nullable String webUrl;

    public McpToolSupport(final MessageSource messageSource, @Value("${spexflix.web-url:}") final @Nullable String webUrl) {
        this.messageSource = messageSource;
        this.webUrl = StringUtils.hasText(webUrl) ? webUrl.strip().replaceAll("/+$", "") : null;
    }

    /**
     * Runs a tool. A refusal becomes a tool error with the API's error code and the same English message the web
     * shows, e.g. "NOT_FOUND: The video does not exist.".
     */
    <T> T call(final Supplier<T> body) {
        try {
            return body.get();
        } catch (final AccessDeniedException e) {
            throw refusal("FORBIDDEN", "error.forbidden", null, e);
        } catch (final BadRequestException e) {
            throw refusal("BAD_REQUEST", e);
        } catch (final ResourceNotFoundException e) {
            throw refusal("NOT_FOUND", e);
        } catch (final SpexipediaUnavailableException e) {
            throw refusal("SPEXIPEDIA_UNAVAILABLE", e);
        }
    }

    /**
     * The address of a page in Spexflix Web, e.g. {@code /videos/<id>}, or null when Spexflix does not know the web's
     * address.
     */
    @Nullable String webUrl(final String path) {
        return webUrl == null ? null : webUrl + path;
    }

    private McpToolRefusal refusal(final String code, final ResponseStatusException e) {
        return refusal(code, e.getDetailMessageCode(), e.getDetailMessageArguments(), e);
    }

    private McpToolRefusal refusal(final String code, final String messageCode, final Object @Nullable [] arguments, final Exception cause) {
        return new McpToolRefusal("%s: %s".formatted(code, messageSource.getMessage(messageCode, arguments, messageCode, Locale.ENGLISH)), cause);
    }

    static final class McpToolRefusal extends RuntimeException {

        McpToolRefusal(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    public record ListResult<T>(List<T> items) {
    }
}

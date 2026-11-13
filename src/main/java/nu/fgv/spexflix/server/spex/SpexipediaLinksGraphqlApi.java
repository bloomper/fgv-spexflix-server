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

package nu.fgv.spexflix.server.spex;

import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.video.SpexRef;
import nu.fgv.spexflix.server.video.Video;
import nu.fgv.spexflix.server.video.VideoRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What Spexipedia asks Spexflix with its {@code SPEXIPEDIA} role: the published videos of spex.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class SpexipediaLinksGraphqlApi {

    private static final int MAX_IDS = 500;

    private final VideoRepository videoRepository;
    private final CallerService callerService;

    public SpexipediaLinksGraphqlApi(final VideoRepository videoRepository, final CallerService callerService) {
        this.videoRepository = videoRepository;
        this.callerService = callerService;
    }

    @QueryMapping
    @Transactional(readOnly = true)
    public List<VideoLink> spexipediaVideos(@Argument final List<UUID> spexIds) {
        if (!callerService.current().isSpexipediaService()) {
            throw new AccessDeniedException("SPEXIPEDIA required");
        }

        final Set<UUID> spex = new HashSet<>(spexIds.subList(0, Math.min(spexIds.size(), MAX_IDS)));

        return spex.isEmpty() ? List.of() : videoRepository.findListedBySpex(spex).stream()
                .map(video -> new VideoLink(video.getUuid(), video.getTitle(), matching(video, spex)))
                .toList();
    }

    /**
     * The video's spex among those asked for: the spex itself, else the original it is a revival of.
     */
    private static @Nullable UUID matching(final Video video, final Set<UUID> spex) {
        for (final SpexRef ref : video.getSpex()) {
            if (spex.contains(ref.getId())) {
                return ref.getId();
            }
        }
        return video.getSpex().stream().map(SpexRef::getParentId).filter(spex::contains).findFirst().orElse(null);
    }

    public record VideoLink(UUID id, String title, @Nullable UUID spexId) {
    }
}

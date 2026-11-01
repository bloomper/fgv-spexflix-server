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

package nu.fgv.spexflix.server.spexify;

import nu.fgv.spexflix.server.user.Caller;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.video.ChapterRepository;
import nu.fgv.spexflix.server.video.VideoService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * GraphQL for the links between chapters and Spexify's tracks.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class SpexifyGraphqlApi {

    private final SpexifyClient client;
    private final VideoService videoService;
    private final ChapterRepository chapterRepository;
    private final CallerService callerService;

    public SpexifyGraphqlApi(final SpexifyClient client, final VideoService videoService,
                             final ChapterRepository chapterRepository, final CallerService callerService) {
        this.client = client;
        this.videoService = videoService;
        this.chapterRepository = chapterRepository;
        this.callerService = callerService;
    }

    /**
     * The tracks an editor can link the video's chapters to: those of the video's spex.
     */
    @QueryMapping
    public List<SpexifyClient.Track> spexifyTracks(@Argument final UUID videoId) {
        callerService.current().requireEditor();

        final var video = videoService.video(videoId);

        return video == null || video.spex() == null ? List.of() : client.tracks(video.spex().id());
    }

    /**
     * For Spexify's track pages: chapters of published videos linked to the tracks.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    public List<ChapterLinkDto> chapterLinks(@Argument final List<UUID> trackIds) {
        final Caller caller = callerService.current();

        if (!caller.isSpexifyService() && !caller.isUser()) {
            throw new AccessDeniedException("SPEXIFY or USER required");
        }
        if (trackIds.isEmpty() || trackIds.size() > 500) {
            return List.of();
        }
        return chapterRepository.findListedByTracks(trackIds).stream()
                .map(chapter -> new ChapterLinkDto(String.valueOf(chapter.getSpexifyTrackId()), String.valueOf(chapter.getVideo().getUuid()),
                        chapter.getVideo().getTitle(), chapter.getTitle(), chapter.getStartSeconds()))
                .toList();
    }

    public record ChapterLinkDto(String trackId, String videoId, String videoTitle, String chapterTitle,
                                 int startSeconds) {
    }
}

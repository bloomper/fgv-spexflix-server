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

import nu.fgv.spexflix.server.video.CreditService.CreditCountDto;
import nu.fgv.spexflix.server.video.CreditService.CreditDto;
import nu.fgv.spexflix.server.video.CreditService.CreditInput;
import nu.fgv.spexflix.server.video.CreditService.CreditedVideoDto;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GraphQL for who takes part in the videos. A thin adapter: the service decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class CreditGraphqlApi {

    private final CreditService service;

    public CreditGraphqlApi(final CreditService service) {
        this.service = service;
    }

    @QueryMapping
    public List<CreditCountDto> credits() {
        return service.credits();
    }

    @QueryMapping
    public List<CreditedVideoDto> creditVideos(@Argument final String name) {
        return service.creditVideos(name);
    }

    @BatchMapping(typeName = "Video", field = "credits")
    public Map<VideoDto, List<CreditDto>> credits(final List<VideoDto> videos) {
        final Map<UUID, List<CreditDto>> credits = service.creditsOf(videos.stream().map(VideoDto::id).toList());
        final Map<VideoDto, List<CreditDto>> result = new LinkedHashMap<>();

        videos.forEach(video -> result.put(video, credits.getOrDefault(video.id(), List.of())));
        return result;
    }

    @MutationMapping
    public VideoDto creditsSet(@Argument final UUID videoId, @Argument final List<CreditInput> credits) {
        return service.setCredits(videoId, credits);
    }
}

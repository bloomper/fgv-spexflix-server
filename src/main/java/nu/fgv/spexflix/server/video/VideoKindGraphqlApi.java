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

import nu.fgv.spexflix.server.video.VideoKindService.VideoKindDto;
import nu.fgv.spexflix.server.video.VideoKindService.VideoKindInput;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * GraphQL for the video kinds. A thin adapter: {@link VideoKindService} decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class VideoKindGraphqlApi {

    private final VideoKindService service;

    public VideoKindGraphqlApi(final VideoKindService service) {
        this.service = service;
    }

    private static <T> Map<VideoKindDto, T> byKind(final List<VideoKindDto> kinds, final Function<List<UUID>, Map<UUID, T>> lookup) {
        final Map<UUID, T> values = lookup.apply(kinds.stream().map(VideoKindDto::id).toList());
        final Map<VideoKindDto, T> result = new LinkedHashMap<>();

        kinds.forEach(kind -> result.put(kind, values.get(kind.id())));
        return result;
    }

    @QueryMapping
    public List<VideoKindDto> videoKinds() {
        return service.kinds();
    }

    @BatchMapping(typeName = "VideoKind", field = "isDefault")
    public Map<VideoKindDto, Boolean> isDefault(final List<VideoKindDto> kinds) {
        return byKind(kinds, service::defaults);
    }

    @BatchMapping(typeName = "VideoKind", field = "videoCount")
    public Map<VideoKindDto, Integer> videoCount(final List<VideoKindDto> kinds) {
        return byKind(kinds, service::videoCounts);
    }

    @MutationMapping
    public VideoKindDto videoKindCreate(@Argument final VideoKindInput input) {
        return service.create(input);
    }

    @MutationMapping
    public VideoKindDto videoKindUpdate(@Argument final UUID id, @Argument final VideoKindInput input) {
        return service.update(id, input);
    }

    @MutationMapping
    public List<VideoKindDto> videoKindsReorder(@Argument final List<UUID> ids) {
        return service.reorder(ids);
    }

    @MutationMapping
    public @Nullable Void videoKindDelete(@Argument final UUID id) {
        service.delete(id);
        return null;
    }
}

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

import nu.fgv.spexflix.server.video.CollectionService.CollectionDto;
import nu.fgv.spexflix.server.video.CollectionService.CollectionInput;
import nu.fgv.spexflix.server.video.CollectionService.CollectionRefDto;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GraphQL for collections and what plays next. A thin adapter: the service decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class CollectionGraphqlApi {

    private final CollectionService service;

    public CollectionGraphqlApi(final CollectionService service) {
        this.service = service;
    }

    @QueryMapping
    public List<CollectionDto> collections() {
        return service.collections();
    }

    @QueryMapping
    public @Nullable CollectionDto collection(@Argument final UUID id) {
        return service.collection(id);
    }

    @BatchMapping(typeName = "Video", field = "collections")
    public Map<VideoDto, List<CollectionRefDto>> collections(final List<VideoDto> videos) {
        final Map<UUID, List<CollectionRefDto>> refs = service.collectionsOf(videos.stream().map(VideoDto::id).toList());
        final Map<VideoDto, List<CollectionRefDto>> result = new LinkedHashMap<>();

        videos.forEach(video -> result.put(video, refs.getOrDefault(video.id(), List.of())));
        return result;
    }

    @SchemaMapping(typeName = "Video", field = "next")
    public @Nullable VideoDto next(final VideoDto video) {
        return service.next(video.id());
    }

    @MutationMapping
    public CollectionDto collectionCreate(@Argument final CollectionInput input) {
        return service.create(input);
    }

    @MutationMapping
    public CollectionDto collectionUpdate(@Argument final UUID id, @Argument final CollectionInput input) {
        return service.update(id, input);
    }

    @MutationMapping
    public @Nullable Void collectionDelete(@Argument final UUID id) {
        service.delete(id);
        return null;
    }
}

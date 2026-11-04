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
package nu.fgv.spexflix.server.playlist;

import nu.fgv.spexflix.server.playlist.PlaylistService.PlaylistDto;
import nu.fgv.spexflix.server.playlist.PlaylistService.PlaylistInput;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

/**
 * GraphQL for playlists. A thin adapter: the service decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class PlaylistGraphqlApi {

    private final PlaylistService service;

    public PlaylistGraphqlApi(final PlaylistService service) {
        this.service = service;
    }

    @QueryMapping
    public List<PlaylistDto> playlists() {
        return service.playlists();
    }

    @QueryMapping
    public @Nullable PlaylistDto playlist(@Argument final UUID id) {
        return service.playlist(id);
    }

    @MutationMapping
    public PlaylistDto playlistCreate(@Argument final PlaylistInput input) {
        return service.create(input);
    }

    @MutationMapping
    public PlaylistDto playlistUpdate(@Argument final UUID id, @Argument final PlaylistInput input) {
        return service.update(id, input);
    }

    @MutationMapping
    public @Nullable Void playlistDelete(@Argument final UUID id) {
        service.delete(id);
        return null;
    }

    @MutationMapping
    public PlaylistDto playlistAddItem(@Argument final UUID playlistId, @Argument final UUID videoId,
                                       @Argument final @Nullable Integer startSeconds) {
        return service.addItem(playlistId, videoId, startSeconds);
    }

    @MutationMapping
    public PlaylistDto playlistRemoveItem(@Argument final UUID playlistId, @Argument final UUID itemId) {
        return service.removeItem(playlistId, itemId);
    }

    @MutationMapping
    public PlaylistDto playlistReorder(@Argument final UUID playlistId, @Argument final List<UUID> itemIds) {
        return service.reorder(playlistId, itemIds);
    }
}

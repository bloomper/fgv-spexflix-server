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

import nu.fgv.spexflix.server.playlist.PlaylistService;
import nu.fgv.spexflix.server.playlist.PlaylistService.PlaylistDto;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Playlists: the caller's own and everyone's public ones, read only.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class PlaylistMcpTools {

    private final PlaylistService playlistService;
    private final CallerService callerService;
    private final VideoMcpTools videoTools;
    private final McpToolSupport support;

    public PlaylistMcpTools(final PlaylistService playlistService, final CallerService callerService, final VideoMcpTools videoTools,
                            final McpToolSupport support) {
        this.playlistService = playlistService;
        this.callerService = callerService;
        this.videoTools = videoTools;
        this.support = support;
    }

    private McpPlaylist toMcp(final PlaylistDto playlist) {
        return new McpPlaylist(playlist.id(), playlist.name(), playlist.description(), playlist.isPublic(), playlist.owned(), playlist.ownerName(),
                playlist.itemCount(), Math.round(playlist.durationSeconds()), support.webUrl("/playlists/" + playlist.id()));
    }

    @McpTool(name = "list_playlists",
            description = "List the playlists you can see: your own, then everyone's public ones, by name, with how many videos and clips each holds and how long it plays.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<McpPlaylist> listPlaylists() {
        return support.call(() -> new McpToolSupport.ListResult<>(playlistService.playlists().stream().map(this::toMcp).toList()));
    }

    @McpTool(name = "get_playlist",
            description = "Get one of your playlists or a public one, with its videos and clips (a chapter of a video, from startSeconds to endSeconds) in order. "
                    + "Only videos you can see are listed.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpPlaylistDetails getPlaylist(@McpToolParam(description = "A playlist you can see (list_playlists gives the ids)") final UUID playlistId) {
        return support.call(() -> {
            final PlaylistDto playlist = playlistService.playlist(playlistId);

            if (playlist == null) {
                throw new ResourceNotFoundException("error.playlist.notFound", playlistId);
            }

            final boolean editor = callerService.current().isEditor();

            return new McpPlaylistDetails(toMcp(playlist), playlist.items().stream()
                    .map(item -> new McpPlaylistItem(item.id(), videoTools.toMcp(item.video(), editor), item.startSeconds(), item.endSeconds(),
                            item.title()))
                    .toList());
        });
    }

    /**
     * A playlist; {@code owned} when it is the caller's, {@code ownerName} whose it is.
     */
    public record McpPlaylist(UUID id, String name, @Nullable String description, boolean isPublic, boolean owned,
                              @Nullable String ownerName, int itemCount, long durationSeconds, @Nullable String webUrl) {
    }

    /**
     * A whole video, or a clip of it from {@code startSeconds} to {@code endSeconds} (the video's end when null) with
     * the chapter's title.
     */
    public record McpPlaylistItem(UUID id, VideoMcpTools.McpVideo video, @Nullable Integer startSeconds,
                                  @Nullable Integer endSeconds, @Nullable String clipTitle) {
    }

    public record McpPlaylistDetails(McpPlaylist playlist, List<McpPlaylistItem> items) {
    }
}

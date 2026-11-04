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

import nu.fgv.spexflix.server.user.AppUser;
import nu.fgv.spexflix.server.user.AppUserRepository;
import nu.fgv.spexflix.server.user.Caller;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.util.error.BadRequestException;
import nu.fgv.spexflix.server.util.error.ResourceNotFoundException;
import nu.fgv.spexflix.server.video.Chapter;
import nu.fgv.spexflix.server.video.Video;
import nu.fgv.spexflix.server.video.VideoDtos;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import nu.fgv.spexflix.server.video.VideoService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.text.Collator;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Each user's own playlists of whole videos and clips (a chapter of a video). Only the owner changes a playlist; a
 * public one can be played by everyone. Listeners see only the listed videos in a playlist.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class PlaylistService {

    static final int MAX_NAME = 255;
    static final int MAX_DESCRIPTION = 4000;
    static final int MAX_ITEMS = 500;
    static final int MAX_PLAYLISTS = 200;

    private final PlaylistRepository repository;
    private final VideoService videoService;
    private final CallerService callerService;
    private final AppUserRepository userRepository;
    private final Clock clock;

    public PlaylistService(final PlaylistRepository repository, final VideoService videoService, final CallerService callerService,
                           final AppUserRepository userRepository, final Clock clock) {
        this.repository = repository;
        this.videoService = videoService;
        this.callerService = callerService;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    static int indexOf(final List<Chapter> chapters, final int startSeconds) {
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).getStartSeconds() == startSeconds) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The caller's playlists, by name, then everyone else's public ones, by name.
     */
    @Transactional
    public List<PlaylistDto> playlists() {
        final AppUser user = currentUser();
        final List<Playlist> playlists = repository.findVisible(user.getId());
        final Map<Long, AppUser> owners = owners(playlists);
        final Collator collator = Collator.getInstance(Locale.forLanguageTag("sv"));

        return playlists.stream()
                .sorted(Comparator.comparing((Playlist playlist) -> !playlist.getUserId().equals(user.getId()))
                        .thenComparing(Playlist::getName, collator))
                .map(playlist -> toDto(playlist, user, owners.get(playlist.getUserId())))
                .toList();
    }

    @Transactional
    public @Nullable PlaylistDto playlist(final UUID id) {
        final AppUser user = currentUser();

        return repository.findByUuid(id)
                .filter(playlist -> playlist.getUserId().equals(user.getId()) || playlist.isShared())
                .map(playlist -> toDto(playlist, user, owners(List.of(playlist)).get(playlist.getUserId())))
                .orElse(null);
    }

    @Transactional
    public PlaylistDto create(final PlaylistInput input) {
        final AppUser user = currentUser();

        if (repository.countByUserId(user.getId()) >= MAX_PLAYLISTS) {
            throw new BadRequestException("error.playlist.tooMany", MAX_PLAYLISTS);
        }

        final Playlist playlist = new Playlist();
        final Instant now = clock.instant();

        playlist.setUserId(user.getId());
        playlist.setCreatedAt(now);
        apply(playlist, input);
        return toDto(repository.save(playlist), user, user);
    }

    @Transactional
    public PlaylistDto update(final UUID id, final PlaylistInput input) {
        final AppUser user = currentUser();
        final Playlist playlist = owned(id, user);

        apply(playlist, input);
        return toDto(playlist, user, user);
    }

    @Transactional
    public void delete(final UUID id) {
        final AppUser user = currentUser();

        repository.delete(owned(id, user));
    }

    /**
     * Adds a whole video, or with {@code startSeconds} the chapter starting there, last in the playlist.
     */
    @Transactional
    public PlaylistDto addItem(final UUID playlistId, final UUID videoId, final @Nullable Integer startSeconds) {
        final AppUser user = currentUser();
        final Playlist playlist = owned(playlistId, user);
        final Video video = videoService.visible(videoId);

        if (playlist.getItems().size() >= MAX_ITEMS) {
            throw new BadRequestException("error.playlist.full", MAX_ITEMS);
        }

        final PlaylistItem item = new PlaylistItem();

        item.setPlaylist(playlist);
        item.setVideo(video);
        item.setAddedAt(clock.instant());
        if (startSeconds != null) {
            final List<Chapter> chapters = video.getChapters().stream().sorted(Comparator.comparingInt(Chapter::getStartSeconds)).toList();
            final int index = indexOf(chapters, startSeconds);

            if (index < 0) {
                throw new BadRequestException("error.playlist.chapter");
            }

            final Chapter chapter = chapters.get(index);
            final long durationSeconds = Objects.requireNonNullElse(video.getDurationMs(), 0L) / 1000;

            item.setStartSeconds(chapter.getStartSeconds());
            item.setEndSeconds(index + 1 < chapters.size() ? chapters.get(index + 1).getStartSeconds()
                    : durationSeconds > chapter.getStartSeconds() ? (int) durationSeconds : null);
            item.setTitle(chapter.getTitle());
        }
        playlist.getItems().add(item);
        playlist.renumber();
        playlist.setUpdatedAt(clock.instant());
        return toDto(playlist, user, user);
    }

    @Transactional
    public PlaylistDto removeItem(final UUID playlistId, final UUID itemId) {
        final AppUser user = currentUser();
        final Playlist playlist = owned(playlistId, user);

        playlist.getItems().removeIf(item -> item.getUuid().equals(itemId));
        playlist.renumber();
        playlist.setUpdatedAt(clock.instant());
        return toDto(playlist, user, user);
    }

    /**
     * Puts the items in the given order. Items not named, e.g. ones the caller cannot see, keep their order after them.
     */
    @Transactional
    public PlaylistDto reorder(final UUID playlistId, final List<UUID> itemIds) {
        final AppUser user = currentUser();
        final Playlist playlist = owned(playlistId, user);
        final Map<UUID, PlaylistItem> items = playlist.getItems().stream().collect(Collectors.toMap(PlaylistItem::getUuid, Function.identity()));
        final Set<UUID> seen = new HashSet<>();

        if (!items.keySet().containsAll(itemIds) || itemIds.stream().anyMatch(id -> !seen.add(id))) {
            throw new BadRequestException("error.playlist.items");
        }

        final List<PlaylistItem> ordered = new java.util.ArrayList<>(itemIds.stream().map(items::get).toList());

        playlist.getItems().stream().filter(item -> !seen.contains(item.getUuid())).forEach(ordered::add);
        playlist.getItems().clear();
        playlist.getItems().addAll(ordered);
        playlist.renumber();
        playlist.setUpdatedAt(clock.instant());
        return toDto(playlist, user, user);
    }

    private void apply(final Playlist playlist, final PlaylistInput input) {
        final String name = input.name() == null ? "" : input.name().strip();
        final String description = StringUtils.hasText(input.description()) ? input.description().strip() : null;

        if (name.isEmpty() || name.length() > MAX_NAME || (description != null && description.length() > MAX_DESCRIPTION)) {
            throw new BadRequestException("error.playlist.invalid");
        }
        playlist.setName(name);
        playlist.setDescription(description);
        playlist.setShared(Boolean.TRUE.equals(input.isPublic()));
        playlist.setUpdatedAt(clock.instant());
    }

    private Playlist owned(final UUID id, final AppUser user) {
        return repository.findByUuid(id)
                .filter(playlist -> playlist.getUserId().equals(user.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("error.playlist.notFound", id));
    }

    private Map<Long, AppUser> owners(final List<Playlist> playlists) {
        return userRepository.findAllById(playlists.stream().map(Playlist::getUserId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
    }

    private PlaylistDto toDto(final Playlist playlist, final AppUser caller, final @Nullable AppUser owner) {
        final List<PlaylistItemDto> items = playlist.getItems().stream()
                .filter(item -> item.getVideo().isListed())
                .map(item -> new PlaylistItemDto(item.getUuid(), VideoDtos.toDto(item.getVideo()), item.getStartSeconds(),
                        item.getEndSeconds(), item.getTitle()))
                .toList();
        final double durationSeconds = items.stream().mapToDouble(PlaylistItemDto::durationSeconds).sum();

        return new PlaylistDto(playlist.getUuid(), playlist.getName(), playlist.getDescription(), playlist.isShared(),
                playlist.getUserId().equals(caller.getId()),
                owner == null ? null : Objects.requireNonNullElse(owner.getDisplayName(), owner.getUsername()),
                items, items.size(), durationSeconds, playlist.getCreatedAt(), playlist.getUpdatedAt());
    }

    private AppUser currentUser() {
        final Caller caller = callerService.current();

        caller.requireUser();
        return callerService.record(caller);
    }

    public record PlaylistInput(@Nullable String name, @Nullable String description, @Nullable Boolean isPublic) {
    }

    public record PlaylistDto(UUID id, String name, @Nullable String description, boolean isPublic, boolean owned,
                              @Nullable String ownerName, List<PlaylistItemDto> items, int itemCount,
                              double durationSeconds,
                              Instant createdAt, Instant updatedAt) {
    }

    /**
     * A whole video, or a clip of it from {@code startSeconds} to {@code endSeconds} (the video's end when null).
     */
    public record PlaylistItemDto(UUID id, VideoDto video, @Nullable Integer startSeconds, @Nullable Integer endSeconds,
                                  @Nullable String title) {

        public double durationSeconds() {
            final double length = Objects.requireNonNullElse(video.durationMs(), 0.0) / 1000;

            if (startSeconds == null) {
                return length;
            }
            return Math.max(0, (endSeconds == null ? length : endSeconds) - startSeconds);
        }
    }
}

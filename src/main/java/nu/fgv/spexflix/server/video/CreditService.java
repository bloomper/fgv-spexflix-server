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

import nu.fgv.spexflix.server.audit.AuditService;
import nu.fgv.spexflix.server.user.Caller;
import nu.fgv.spexflix.server.user.CallerService;
import nu.fgv.spexflix.server.util.error.BadRequestException;
import nu.fgv.spexflix.server.util.error.ResourceNotFoundException;
import nu.fgv.spexflix.server.video.VideoDtos.VideoDto;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.text.Collator;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who takes part in the videos ("Medverkande"), like Spexify's credits of its songs: editors list names with what each
 * does or plays, and everyone can see a name's videos.
 *
 * <p>Names are told apart without regard to case or surrounding space, and shown as first written. Listeners see the
 * credits of listed videos; EDITOR those of every video outside the trash.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class CreditService {

    static final int MAX_CREDITS = 300;
    private static final int MAX_TEXT = 255;
    private static final Collator COLLATOR = Collator.getInstance(Locale.of("sv"));

    private final VideoRepository videoRepository;
    private final CallerService callerService;
    private final AuditService auditService;

    public CreditService(final VideoRepository videoRepository, final CallerService callerService, final AuditService auditService) {
        this.videoRepository = videoRepository;
        this.callerService = callerService;
        this.auditService = auditService;
    }

    static String key(final String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private static @Nullable String clean(final @Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        final String stripped = value.strip().replaceAll("\\s+", " ");

        return stripped.length() > MAX_TEXT ? stripped.substring(0, MAX_TEXT) : stripped;
    }

    private static boolean visible(final Video video, final Caller caller) {
        return caller.isEditor() ? !video.isTrashed() : video.isListed();
    }

    private List<Video> visibleVideos(final Caller caller) {
        return caller.isEditor() ? videoRepository.findNotTrashed() : videoRepository.findListed();
    }

    /**
     * The credits of some videos, in their order.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<CreditDto>> creditsOf(final Collection<UUID> videoIds) {
        final Map<UUID, List<CreditDto>> result = new HashMap<>();

        callerService.current().requireUser();
        for (final Video video : videoRepository.findByUuidIn(videoIds)) {
            result.put(video.getUuid(), video.getCredits().stream().map(credit -> new CreditDto(credit.getName(), credit.getRole())).toList());
        }
        return result;
    }

    /**
     * Everyone credited in the videos the caller sees, by name, with how many videos each.
     */
    @Transactional(readOnly = true)
    public List<CreditCountDto> credits() {
        final Caller caller = callerService.current();
        final Map<String, String> names = new LinkedHashMap<>();
        final Map<String, Set<Long>> videos = new HashMap<>();

        caller.requireUser();
        for (final Video video : visibleVideos(caller)) {
            for (final Credit credit : video.getCredits()) {
                final String key = key(credit.getName());

                names.putIfAbsent(key, credit.getName());
                videos.computeIfAbsent(key, _ -> new LinkedHashSet<>()).add(video.getId());
            }
        }
        return names.entrySet().stream()
                .map(entry -> new CreditCountDto(entry.getValue(), videos.get(entry.getKey()).size()))
                .sorted(Comparator.comparing(CreditCountDto::name, COLLATOR))
                .toList();
    }

    /**
     * The videos the caller sees that credit a name, newest first, each with what the name does in it.
     */
    @Transactional(readOnly = true)
    public List<CreditedVideoDto> creditVideos(final String name) {
        final Caller caller = callerService.current();
        final String wanted = key(name);

        caller.requireUser();
        return visibleVideos(caller).stream()
                .filter(video -> visible(video, caller))
                .flatMap(video -> video.getCredits().stream()
                        .filter(credit -> key(credit.getName()).equals(wanted))
                        .findFirst()
                        .map(credit -> new CreditedVideoDto(VideoDtos.toDto(video), credit.getRole()))
                        .stream())
                .sorted(Comparator.comparing((CreditedVideoDto entry) -> entry.video().year(),
                        Comparator.nullsLast(Comparator.<Integer>reverseOrder())).thenComparing(entry -> entry.video().title()))
                .toList();
    }

    /**
     * Replaces a video's credits, kept in the given order. Rows without a name are left out.
     */
    @Transactional
    public VideoDto setCredits(final UUID videoId, final List<CreditInput> inputs) {
        callerService.current().requireEditor();

        final Video video = videoRepository.findByUuid(videoId)
                .orElseThrow(() -> new ResourceNotFoundException("error.video.notFound", videoId));

        if (video.isTrashed()) {
            throw new BadRequestException("error.video.trashed");
        }

        final List<Credit> credits = inputs.stream()
                .filter(input -> clean(input.name()) != null)
                .map(input -> new Credit(clean(input.name()), clean(input.role())))
                .toList();

        if (credits.size() > MAX_CREDITS) {
            throw new BadRequestException("error.credit.invalid");
        }
        video.getCredits().clear();
        video.getCredits().addAll(credits);
        auditService.record("UPDATE", "VIDEO", video.getUuid(), "Ändrade de medverkande i " + video.getTitle());
        return VideoDtos.toDto(video);
    }

    public record CreditDto(String name, @Nullable String role) {
    }

    public record CreditCountDto(String name, int videoCount) {
    }

    public record CreditedVideoDto(VideoDto video, @Nullable String role) {
    }

    public record CreditInput(String name, @Nullable String role) {
    }
}

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

package nu.fgv.spexflix.server.processing;

import java.util.ArrayList;
import java.util.List;

/**
 * Which renditions to make of a video: 1080p, 720p and 480p, never above the original's height, and at least one.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class RenditionPlan {

    /**
     * A rendition to make.
     *
     * @param height      in pixels
     * @param width       in pixels, even, keeping the aspect ratio
     * @param bitrateKbps the video bitrate aimed at
     * @param copy        whether to copy the original's streams instead of encoding
     */
    public record Step(int height, int width, int bitrateKbps, boolean copy) {
    }

    static final int[][] LADDER = {{1080, 5000}, {720, 3000}, {480, 1200}};

    private RenditionPlan() {
    }

    public static List<Step> plan(final VideoProbe.Result probe) {
        final List<Step> steps = new ArrayList<>();
        boolean first = true;

        for (final int[] level : LADDER) {
            if (level[0] <= probe.height()) {
                final boolean copy = first && probe.copyable() && probe.height() <= level[0] + 10;

                steps.add(new Step(copy ? probe.height() : level[0], copy ? probe.width() : width(probe, level[0]), level[1], copy));
                first = false;
            }
        }
        if (steps.isEmpty()) {
            final int height = probe.height() - probe.height() % 2;

            steps.add(new Step(height, width(probe, height), 800, probe.copyable()));
        }
        return steps;
    }

    static int width(final VideoProbe.Result probe, final int height) {
        final long width = Math.round((double) probe.width() * height / probe.height());

        return (int) (width - width % 2);
    }
}

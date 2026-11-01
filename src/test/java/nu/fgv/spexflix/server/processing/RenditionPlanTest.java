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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RenditionPlanTest {

    private static VideoProbe.Result probe(final String codec, final int width, final int height, final boolean interlaced, final String audio) {
        return new VideoProbe.Result(codec, width, height, 25, interlaced, audio, 60_000);
    }

    @Test
    void copiesH264At1080AndEncodesTheLowerLevels() {
        final List<RenditionPlan.Step> plan = RenditionPlan.plan(probe("h264", 1920, 1080, false, "aac"));

        assertThat(plan).extracting(RenditionPlan.Step::height).containsExactly(1080, 720, 480);
        assertThat(plan).extracting(RenditionPlan.Step::copy).containsExactly(true, false, false);
        assertThat(plan.get(1).width()).isEqualTo(1280);
    }

    @Test
    void neverMakesARenditionAboveTheOriginal() {
        assertThat(RenditionPlan.plan(probe("h264", 1280, 720, false, "aac")))
                .extracting(RenditionPlan.Step::height).containsExactly(720, 480);
        assertThat(RenditionPlan.plan(probe("vp9", 640, 360, false, "opus")))
                .extracting(RenditionPlan.Step::height).containsExactly(360);
    }

    @Test
    void reEncodesWhatCannotBeCopied() {
        assertThat(RenditionPlan.plan(probe("mpeg2video", 720, 576, true, "mp2"))).allMatch(step -> !step.copy());
        assertThat(RenditionPlan.plan(probe("h264", 1920, 1080, true, "aac")).getFirst().copy()).isFalse();
        assertThat(RenditionPlan.plan(probe("h264", 1920, 1080, false, "opus")).getFirst().copy()).isFalse();
        assertThat(RenditionPlan.plan(probe("h264", 3840, 2160, false, "aac")).getFirst())
                .extracting(RenditionPlan.Step::height, RenditionPlan.Step::copy).containsExactly(1080, false);
    }
}

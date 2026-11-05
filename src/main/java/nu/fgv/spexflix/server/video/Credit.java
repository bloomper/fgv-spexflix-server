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

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * Someone who takes part in a video, and what they do or play there.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class Credit {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "role")
    private @Nullable String role;

    public Credit(final String name, final @Nullable String role) {
        this.name = name;
        this.role = role;
    }
}

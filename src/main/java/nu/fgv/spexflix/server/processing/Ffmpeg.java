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

import nu.fgv.spexflix.server.config.SpexflixProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * Runs {@code ffmpeg} and {@code ffprobe}, with arguments as a list so that nothing is interpreted by a shell, and
 * ffmpeg at low priority ({@code nice}) so that the other applications on the host stay responsive.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class Ffmpeg {

    private static final Duration PROBE_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration TRANSCODE_TIMEOUT = Duration.ofHours(12);

    private final int priority;
    private final boolean nice;

    public Ffmpeg(final SpexflixProperties properties) {
        this.priority = properties.worker().priority();
        this.nice = isAvailable("nice");
    }

    public String probe(final List<String> arguments) throws IOException {
        final List<String> command = new ArrayList<>();

        command.add("ffprobe");
        command.addAll(arguments);
        return run(command, PROBE_TIMEOUT, null);
    }

    public void ffmpeg(final List<String> arguments) throws IOException {
        ffmpeg(arguments, null);
    }

    /**
     * Runs ffmpeg, reporting how far it has come in microseconds of output through {@code -progress}.
     */
    public void ffmpeg(final List<String> arguments, final LongConsumer progressMicros) throws IOException {
        final List<String> command = new ArrayList<>();

        if (nice) {
            command.addAll(List.of("nice", "-n", Integer.toString(priority)));
        }
        command.addAll(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"));
        if (progressMicros != null) {
            command.addAll(List.of("-progress", "pipe:1", "-nostats"));
        }
        command.addAll(arguments);
        run(command, TRANSCODE_TIMEOUT, progressMicros);
    }

    private static String run(final List<String> command, final Duration timeout, final LongConsumer progress) throws IOException {
        final Process process = new ProcessBuilder(command).start();
        final CompletableFuture<String> stdout = progress == null ? read(process.getInputStream()) : follow(process.getInputStream(), progress);
        final CompletableFuture<String> stderr = read(process.getErrorStream());

        try {
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg timed out");
            }
            if (process.exitValue() != 0) {
                final String error = stderr.join().strip();

                throw new IOException((error.isEmpty() ? "exit " + process.exitValue() : error.lines().reduce((a, b) -> b).orElse(error)));
            }
            return stdout.join();
        } catch (final InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static CompletableFuture<String> read(final InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (final IOException e) {
                return "";
            }
        });
    }

    private static CompletableFuture<String> follow(final InputStream stream, final LongConsumer progress) {
        return CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                        try {
                            progress.accept(Long.parseLong(line.substring(line.indexOf('=') + 1).strip()));
                        } catch (final NumberFormatException e) {
                            // N/A at the start
                        }
                    }
                }
            } catch (final IOException e) {
                // the process ended
            }
            return "";
        });
    }

    private static boolean isAvailable(final String program) {
        try {
            return new ProcessBuilder(program, "true").start().waitFor() == 0;
        } catch (final IOException | InterruptedException e) {
            return false;
        }
    }
}

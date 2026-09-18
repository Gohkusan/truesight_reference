package com.truesight.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the specific bug an end-to-end test against a live server caught: a blank
 * RHS in .env (exactly what .env.example's optional fields look like once copied and
 * left unfilled) was being registered as an empty-string property, which silently
 * defeats application.yml's ${VAR:default} fallback and produced a malformed Gemini
 * request URL at runtime instead of falling back to the real default.
 */
class DotEnvPostProcessorTest {

    private final DotEnvPostProcessor processor = new DotEnvPostProcessor();

    @Test
    void blankValuesAreOmittedNotRegisteredAsEmptyStrings(@TempDir Path tempDir) throws IOException {
        Path envFile = writeEnvFile(tempDir, """
                GEMINI_API_KEY=some-real-key
                GEMINI_MODEL=
                GEMINI_BASE_URL=
                """);

        Map<String, Object> parsed = processor.parse(envFile);

        assertThat(parsed).containsEntry("GEMINI_API_KEY", "some-real-key");
        assertThat(parsed).doesNotContainKey("GEMINI_MODEL");
        assertThat(parsed).doesNotContainKey("GEMINI_BASE_URL");
    }

    @Test
    void skipsCommentsAndBlankLines(@TempDir Path tempDir) throws IOException {
        Path envFile = writeEnvFile(tempDir, """
                # a comment
                KEY_ONE=value_one

                # another comment
                KEY_TWO=value_two
                """);

        Map<String, Object> parsed = processor.parse(envFile);

        assertThat(parsed).containsOnly(
                Map.entry("KEY_ONE", "value_one"),
                Map.entry("KEY_TWO", "value_two"));
    }

    @Test
    void stripsSurroundingDoubleQuotes(@TempDir Path tempDir) throws IOException {
        Path envFile = writeEnvFile(tempDir, "KEY=\"value with spaces\"\n");
        Map<String, Object> parsed = processor.parse(envFile);
        assertThat(parsed).containsEntry("KEY", "value with spaces");
    }

    @Test
    void ignoresAMalformedLineWithNoEqualsSign(@TempDir Path tempDir) throws IOException {
        Path envFile = writeEnvFile(tempDir, "THIS_LINE_HAS_NO_EQUALS_SIGN\nREAL_KEY=real_value\n");
        Map<String, Object> parsed = processor.parse(envFile);
        assertThat(parsed).containsOnly(Map.entry("REAL_KEY", "real_value"));
    }

    private Path writeEnvFile(Path dir, String content) throws IOException {
        Path file = dir.resolve(".env");
        Files.writeString(file, content);
        return file;
    }
}

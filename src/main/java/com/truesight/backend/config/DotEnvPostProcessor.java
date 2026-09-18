package com.truesight.backend.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Loads a local {@code .env} file into Spring's property sources, so
 * {@code ${GEMINI_API_KEY:}}-style placeholders in application.yml resolve the same way
 * whether the value came from a real environment variable (as it would in any deployed
 * environment) or from a gitignored local file (as it does on a laptop with no other
 * way to set env vars for a single project).
 *
 * <p>Why a hand-rolled loader instead of a library like java-dotenv: this project has
 * exactly one property-loading need (KEY=value lines, # comments, done), and an
 * {@link EnvironmentPostProcessor} is the extension point Spring Boot already provides
 * for "run some code before the environment is finalised". Pulling in a dependency for
 * ~20 lines of parsing would be the opposite of "favour the obvious" for a reference
 * implementation a team is meant to re-derive.
 *
 * <p>Deliberately lowest possible precedence: real environment variables and JVM system
 * properties must always win over .env, so a deployed environment's real secrets are
 * never shadowed by a leftover local file.
 */
public class DotEnvPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "dotEnvFile";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = Path.of(".env");
        if (!Files.isRegularFile(envFile)) {
            return; // Normal case in CI / deployed environments: real env vars are used instead.
        }

        Map<String, Object> values = parse(envFile);
        if (values.isEmpty()) {
            return;
        }

        // addLast: lowest priority. System env vars and -D system properties, which Spring
        // Boot registers earlier with higher precedence, override anything read from here.
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, values));
    }

    private Map<String, Object> parse(Path envFile) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(envFile);
        } catch (IOException e) {
            // Fail open: a malformed/unreadable .env should not stop the app from booting
            // with defaults. The absence of a key surfaces later as a clear "LLM not
            // configured" banner (AC 9.2), not a boot crash.
            return values;
        }

        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            // Strip a single layer of matching quotes, e.g. KEY="value with spaces"
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }
}

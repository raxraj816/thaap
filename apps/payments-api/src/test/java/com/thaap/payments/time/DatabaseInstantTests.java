package com.thaap.payments.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Holds the one invariant a macOS development machine cannot observe.
 *
 * <p>{@code Instant.now()} resolves to microseconds on macOS and to nanoseconds on Linux. Every
 * assertion comparing an in-memory instant against its own database row therefore passes for free
 * on a developer's laptop and fails in CI and production, where the extra digits exist and
 * PostgreSQL rounds them away. The behavioural tests below pin the truncation itself; the source
 * scan pins the thing that actually regressed — a call site that mints time without it.
 */
class DatabaseInstantTests {

    /** A nanosecond instant of the kind only a Linux host produces. */
    private static final Instant WITH_NANOS = Instant.parse("2026-08-07T18:14:06.400031361Z");

    @Test
    void truncatesTheSubMicrosecondDigitsPostgreSqlCannotStore() {
        assertThat(DatabaseInstant.of(WITH_NANOS))
                .isEqualTo(Instant.parse("2026-08-07T18:14:06.400031Z"));
    }

    @Test
    void truncatesRatherThanRoundsSoTheStoredInstantIsOneTheApplicationHeld() {
        // PostgreSQL would round .656610779 up to .656611, to an instant that never occurred here.
        assertThat(DatabaseInstant.of(Instant.parse("2026-08-07T18:21:45.656610779Z")))
                .isEqualTo(Instant.parse("2026-08-07T18:21:45.656610Z"));
    }

    @Test
    void leavesAnInstantThatIsAlreadyAtDatabasePrecisionAlone() {
        Instant exact = Instant.parse("2026-08-07T18:14:06.400031Z");
        assertThat(DatabaseInstant.of(exact)).isEqualTo(exact);
    }

    @Test
    void rejectsAMissingInstantRatherThanNormalizingItToNull() {
        assertThatThrownBy(() -> DatabaseInstant.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event time is required");
    }

    @Test
    void theTickedClockTruncatesEvenWhenTheUnderlyingClockOffersNanoseconds() {
        // Fixed at a nanosecond instant, which is what the bean sees on any Linux host.
        Clock nanosecondClock = Clock.fixed(WITH_NANOS, ZoneOffset.UTC);
        assertThat(nanosecondClock.instant()).isEqualTo(WITH_NANOS);

        assertThat(DatabaseInstant.tick(nanosecondClock).instant())
                .isEqualTo(Instant.parse("2026-08-07T18:14:06.400031Z"));
    }

    @Test
    void everyInstantTheTickedSystemClockHandsOutIsAlreadyAtDatabasePrecision() {
        Clock clock = DatabaseInstant.tick(Clock.systemUTC());
        for (int reading = 0; reading < 5_000; reading++) {
            Instant now = clock.instant();
            assertThat(now.getNano() % 1000)
                    .as("reading %s carried sub-microsecond digits the database cannot store", now)
                    .isZero();
        }
    }

    /**
     * Fails the build when a call site mints time without going through this class.
     *
     * <p>This is the only check in the repository that catches the defect on macOS, where the
     * kernel has already truncated the value and every runtime assertion agrees regardless. A
     * reviewer on a Mac cannot see the difference; the compiler can.
     */
    @Test
    void noSourceFileMintsTimeOutsideThisClass() throws IOException {
        // "Instant.now()" not preceded by an identifier character, so DatabaseInstant.now() passes.
        Pattern rawNow = Pattern.compile("(?<![A-Za-z0-9_])Instant\\.now\\(\\)");
        Pattern rawSystemClock = Pattern.compile("Clock\\.system(UTC\\(\\)|\\()");
        List<String> offenders = new ArrayList<>();

        for (Path source : sourceFiles()) {
            // Flyway migrations are historical: they ran once, against a schema now frozen.
            if (source.toString().contains("db/migration")
                    || source.getFileName().toString().equals("DatabaseInstant.java")) {
                continue;
            }
            String code = withoutComments(Files.readString(source));
            record Rule(Pattern pattern, String replacement) {
            }
            for (Rule rule : List.of(
                    new Rule(rawNow, "DatabaseInstant.now()"),
                    new Rule(rawSystemClock, "DatabaseInstant.tick(Clock.systemUTC())"))) {
                Matcher matcher = rule.pattern().matcher(code);
                while (matcher.find()) {
                    // A system clock already wrapped by tick(...) is the approved spelling.
                    int start = matcher.start();
                    String preceding = code.substring(Math.max(0, start - 25), start);
                    if (preceding.endsWith("DatabaseInstant.tick(")) {
                        continue;
                    }
                    offenders.add(source + " -> use " + rule.replacement());
                }
            }
        }

        assertThat(offenders)
                .as("these call sites mint nanosecond time on Linux, which PostgreSQL rounds away "
                        + "on write, so the value stops matching its own row when it is read back")
                .isEmpty();
    }

    private static List<Path> sourceFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String root : new String[] {"src/main/java", "src/test/java"}) {
            try (Stream<Path> walk = Files.walk(Path.of(root))) {
                walk.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
            }
        }
        assertThat(files).as("the source scan must actually find sources").isNotEmpty();
        return files;
    }

    /** Strips comments so prose naming the forbidden calls does not fail the scan. */
    private static String withoutComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}

package com.loganalyzer.service.profile;

import com.loganalyzer.model.MetricDto;
import com.loganalyzer.service.LogSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SUMMARY block spreads its figures over a base line plus qualified variants, and derives one
 * of them as a remainder that can go negative. These tests pin both, because reading only the base
 * lines under-reported work without failing anything.
 */
class ElabBatchSummaryTest {

    private final ElabBatchProfile profile = new ElabBatchProfile();

    @Test
    @DisplayName("successes reported under qualifiers are counted, not dropped")
    void sumsTheWholeSuccessFamily() throws IOException {
        ProcessingUnit unit = parseSingleUnit("""
                Record to elaborate                   : 100
                Record Succesful                      : 8
                Record Succesful WithoutMatch         : 40
                Record Succesful WithoutDocument      : 2
                Record Unchanged                      : 45
                Record Discarded                      : 5
                """);

        // 8 + 40 + 2 successful, plus 45 unchanged. Reading only "Record Succesful" gave 53.
        assertThat(unit.metric(MetricDto.Kind.OK)).isEqualTo(95);
        assertThat(unit.metric(MetricDto.Kind.REJECTED)).isEqualTo(5);
        assertThat(unit.metric(MetricDto.Kind.INPUT)).isEqualTo(100);
    }

    @Test
    @DisplayName("a qualifier this analyzer has never seen is still counted")
    void countsUnknownQualifiers() throws IOException {
        ProcessingUnit unit = parseSingleUnit("""
                Record to elaborate                   : 10
                Record Succesful                      : 1
                Record Succesful WithSomeFutureReason : 9
                """);

        assertThat(unit.metric(MetricDto.Kind.OK)).isEqualTo(10);
    }

    @Test
    @DisplayName("'Discarded NotToWork' is reported apart from KO, not folded into it")
    void keepsNotToWorkOutOfTheRejectedCount() throws IOException {
        ProcessingUnit unit = parseSingleUnit("""
                Record to elaborate                   : 100
                Record Succesful                      : 90
                Record Discarded NotToWork            : 7
                Record Discarded                      : 3
                """);

        assertThat(unit.metric(MetricDto.Kind.REJECTED)).isEqualTo(3);
        assertThat(metric(unit, "discardedNotToWork")).isEqualTo(7);
    }

    @Test
    @DisplayName("a negative remainder keeps its sign instead of reading as a positive count")
    void preservesNegativeUnprocessed() throws IOException {
        ProcessingUnit unit = parseSingleUnit("""
                Record to elaborate                   : 231
                Record Succesful                      : 8
                Record Unchanged                      : 117
                Record Discarded                      : 107
                Record PARTIALLY PROCESSED            : 0
                Record Unprocessed                    : -1
                """);

        // Matching the digits alone would have turned -1 into 1 and hidden the deficit.
        assertThat(metric(unit, "unprocessed")).isEqualTo(-1);
        assertThat(metric(unit, "partiallyProcessed")).isZero();
    }

    @Test
    @DisplayName("the reported figures reconcile against 'Record to elaborate'")
    void figuresReconcileWithTheDeclaredTotal() throws IOException {
        ProcessingUnit unit = parseSingleUnit("""
                Record to elaborate                   : 231
                Record Succesful                      : 8
                Record Unchanged                      : 117
                Record Discarded                      : 107
                Record PARTIALLY PROCESSED            : 0
                Record Unprocessed                    : -1
                """);

        int accounted = unit.metric(MetricDto.Kind.OK)
                + unit.metric(MetricDto.Kind.REJECTED)
                + metric(unit, "partiallyProcessed")
                + metric(unit, "unprocessed");

        assertThat(accounted).isEqualTo(unit.metric(MetricDto.Kind.INPUT));
    }

    @Test
    @DisplayName("absent counts stay absent rather than becoming zero")
    void doesNotInventCountsThatTheLogNeverReported() throws IOException {
        ProcessingUnit unit = parseSingleUnit("""
                Record to elaborate                   : 5
                """);

        assertThat(unit.metric(MetricDto.Kind.OK)).isNull();
        assertThat(unit.metric(MetricDto.Kind.REJECTED)).isNull();
        assertThat(metric(unit, "unprocessed")).isNull();
    }

    /** The named metric's value, or null when the profile did not report it. */
    private Integer metric(ProcessingUnit unit, String key) {
        return unit.metrics().stream()
                .filter(m -> m.key().equals(key))
                .findFirst()
                .map(m -> (int) m.value())
                .orElse(null);
    }

    /** Wraps a SUMMARY body in the markers the profile needs, then parses it. */
    private ProcessingUnit parseSingleUnit(String summaryBody) throws IOException {
        String log = """
                BATCH_VERSION: 1.0
                RunId: RUN-1
                SUMMARY START
                """ + summaryBody + """
                SUMMARY END
                ESTADO: FINISHED_OK
                """;

        LogSource source = LogSource.read(
                new MockMultipartFile("stdout", "stdout_job.txt", "text/plain",
                        log.getBytes(StandardCharsets.UTF_8)),
                null);

        List<ProcessingUnit> units = profile.parse(source);
        assertThat(units).hasSize(1);
        return units.get(0);
    }
}

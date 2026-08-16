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
 * Each profile must recognise its own dialect and must not claim someone else's - a log parsed by
 * the wrong profile fails silently, which is the failure mode worth guarding hardest against.
 */
class ProfileDetectionTest {

    private final ProfileRegistry registry = new ProfileRegistry();

    private static final String AIRFLOW = """
            [2026-08-01T22:00:04.143+0000] {taskinstance.py:1103} INFO - Dependencies all met for dag_id=nightly_load task_id=load_core try_number=1
            [2026-08-01T22:00:05.001+0000] {standard_task_runner.py:85} INFO - Running on host worker-1
            [2026-08-01T22:41:37.554+0000] {taskinstance.py:1400} ERROR - Task failed with exception
            [2026-08-01T22:41:38.001+0000] {taskinstance.py:1200} INFO - Marking task as FAILED
            [2026-08-01T22:41:38.100+0000] {local_task_job.py:212} INFO - Task exited with return code 1
            """;

    private static final String SPARK = """
            26/08/01 22:00:04 INFO SparkContext: Submitted application: nightly_load_app
            26/08/01 22:00:05 INFO DAGScheduler: Starting job: save at Loader.scala:42
            26/08/01 22:18:51 INFO DAGScheduler: Job 0 finished: save at Loader.scala:42, took 1127.412 s
            26/08/01 22:19:00 WARN TaskSetManager: Lost task 3.0 in stage 2.0
            """;

    private static final String ECS = """
            {"@timestamp":"2026-08-01T22:00:04.000Z","log.level":"INFO","message":"starting load","service.name":"data-loader","ecs.version":"1.6.0"}
            {"@timestamp":"2026-08-01T22:10:04.000Z","log.level":"ERROR","message":"null value in column \\"client_id\\"","service.name":"data-loader","error.type":"java.sql.SQLException"}
            {"@timestamp":"2026-08-01T22:41:37.000Z","log.level":"INFO","message":"finished","service.name":"data-loader"}
            """;

    private static final String BATCH_LAYER = """
            **** BATCH : NIGHTLY_LOAD ****
            START STAGING
            IDEXECUTION : 10241
            Starting time : 01/08/2026 22:00:04
            Raw data : 1000
            - Error : 0
            - Warning : 0
            - OK : 1000
            End time : 01/08/2026 22:18:51
            STATUS : OK
            """;

    @Test
    @DisplayName("each dialect is claimed by its own profile")
    void detectsEachDialect() throws IOException {
        assertThat(registry.detect(source(AIRFLOW)).id()).isEqualTo("airflow-task");
        assertThat(registry.detect(source(SPARK)).id()).isEqualTo("spark-log4j2");
        assertThat(registry.detect(source(ECS)).id()).isEqualTo("ecs-json");
        assertThat(registry.detect(source(BATCH_LAYER)).id()).isEqualTo("batch-layer");
    }

    @Test
    @DisplayName("Talend output is recognised even though it has no blocks to split on")
    void recognisesTalendWithoutProducingUnits() throws IOException {

        LogSource source = source("""
                01/08/2026 23:10:03 [INFO ] Query exec
                01/08/2026 23:12:44 [ERROR] Exception in component tPostgresqlOutput_5
                01/08/2026 23:12:45 [FATAL] EXIT CODE 1
                """);

        ProfileMatch match = registry.detect(source);

        assertThat(match.id()).isEqualTo("talend-job");
        assertThat(match.profile().parse(source))
                .as("no block structure, so the whole-log path handles it")
                .isEmpty();
    }

    @Test
    @DisplayName("an unrecognisable log matches nothing rather than being forced into a dialect")
    void reportsNoMatchForUnknownFormats() throws IOException {

        ProfileMatch match = registry.detect(source("some free-form text\nwith no structure at all\n"));

        assertThat(match.matched()).isFalse();
        assertThat(match.id()).isEqualTo("generic");
        assertThat(match.confidence()).isZero();
    }

    @Test
    @DisplayName("a stray marker from another dialect does not steal the match")
    void prefersTheProfileThatExplainsMostOfTheLog() throws IOException {

        // "START LOAD" would satisfy the batch-layer profile's trigger on its own.
        ProfileMatch match = registry.detect(source(AIRFLOW + "[2026-08-01T22:00:06.000+0000] "
                + "{bash.py:20} INFO - START LOAD\n"));

        assertThat(match.id()).isEqualTo("airflow-task");
    }

    @Test
    @DisplayName("Airflow: the task is one unit, with its dag, run and outcome")
    void parsesAirflowTask() throws IOException {

        LogSource source = source(AIRFLOW);
        LogProfile profile = registry.detect(source).profile();

        List<ProcessingUnit> units = profile.parse(source);
        assertThat(units).hasSize(1);

        ProcessingUnit unit = units.get(0);
        assertThat(unit.name()).isEqualTo("load_core");
        assertThat(unit.status()).isEqualTo("FAILED");
        assertThat(unit.failed()).isTrue();
        assertThat(unit.start()).isNotNull();
        assertThat(profile.identify(source).dagName()).isEqualTo("nightly_load");
    }

    @Test
    @DisplayName("Spark: a finished job becomes a unit carrying its duration")
    void parsesSparkJob() throws IOException {

        LogSource source = source(SPARK);
        LogProfile profile = registry.detect(source).profile();

        List<ProcessingUnit> units = profile.parse(source);

        assertThat(units).hasSize(1);
        assertThat(units.get(0).status()).isEqualTo("SUCCESS");
        assertThat(units.get(0).executionId()).isEqualTo("0");
        assertThat(units.get(0).metrics())
                .extracting(MetricDto::key)
                .contains("durationSeconds");
        assertThat(profile.identify(source).jobName()).isEqualTo("nightly_load_app");
    }

    @Test
    @DisplayName("ECS: events are grouped per service and an ERROR fails the unit")
    void parsesEcsEvents() throws IOException {

        LogSource source = source(ECS);
        LogProfile profile = registry.detect(source).profile();

        List<ProcessingUnit> units = profile.parse(source);

        assertThat(units).hasSize(1);
        assertThat(units.get(0).name()).isEqualTo("data-loader");
        assertThat(units.get(0).failed()).isTrue();
        assertThat(units.get(0).metric(MetricDto.Kind.INPUT)).isEqualTo(3);
        assertThat(units.get(0).metric(MetricDto.Kind.ERROR)).isEqualTo(1);
    }

    @Test
    @DisplayName("ECS accepts dotted keys nested as objects too, as the schema permits")
    void parsesNestedEcsEvents() throws IOException {

        LogSource source = source("""
                {"@timestamp":"2026-08-01T22:00:04.000Z","log":{"level":"INFO"},"message":"a"}
                {"@timestamp":"2026-08-01T22:00:05.000Z","log":{"level":"ERROR"},"message":"b"}
                """);

        ProfileMatch match = registry.detect(source);

        assertThat(match.id()).isEqualTo("ecs-json");
        assertThat(match.profile().parse(source)).singleElement()
                .extracting(ProcessingUnit::failed)
                .isEqualTo(true);
    }

    private LogSource source(String content) throws IOException {
        return LogSource.read(
                new MockMultipartFile("stdout", "stdout_job.txt", "text/plain",
                        content.getBytes(StandardCharsets.UTF_8)),
                null);
    }
}

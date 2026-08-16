package com.loganalyzer.service.profile;

import com.loganalyzer.model.RecordStatusDto;
import com.loganalyzer.service.LogSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every record the log declares has to appear in the list, including the ones it never reported an
 * outcome for - those are what "Record Unprocessed" counts, and dropping them left the record list
 * short of the declared total with nothing to explain the gap.
 */
class ElabBatchRecordsTest {

    private final ElabBatchProfile profile = new ElabBatchProfile();

    @Test
    @DisplayName("a record with no status line is listed as unreported, not dropped")
    void keepsRecordsThatNeverGotAStatus() throws IOException {
        List<RecordStatusDto> records = parseRecords("""
                IDDIALOGUE: 111 - INTERNALKEY: K1 - UNIQUECODE: U1
                STATUS: OK
                IDDIALOGUE: 222 - INTERNALKEY: K2 - UNIQUECODE: U2
                Elab-Msg  CodeAction[GD000] order[2]
                IDDIALOGUE: 333 - INTERNALKEY: K3 - UNIQUECODE: U3
                STATUS: KO
                """);

        assertThat(records).hasSize(3);
        assertThat(records).extracting(RecordStatusDto::dialogueId)
                .containsExactly("111", "222", "333");

        // The middle record's block carried no STATUS line.
        assertThat(records.get(1).status()).isNull();
        assertThat(records.get(0).status()).isEqualTo("OK");
        assertThat(records.get(2).status()).isEqualTo("KO");
    }

    @Test
    @DisplayName("the last record is listed even when the log ends before its status")
    void keepsATrailingRecordWithNoStatus() throws IOException {
        List<RecordStatusDto> records = parseRecords("""
                IDDIALOGUE: 111 - INTERNALKEY: K1 - UNIQUECODE: U1
                STATUS: OK
                IDDIALOGUE: 999 - INTERNALKEY: K9 - UNIQUECODE: U9
                """);

        assertThat(records).hasSize(2);
        assertThat(records.get(1).dialogueId()).isEqualTo("999");
        assertThat(records.get(1).status()).isNull();
    }

    @Test
    @DisplayName("a status is never taken from the record that follows")
    void doesNotBorrowTheNextRecordsStatus() throws IOException {
        List<RecordStatusDto> records = parseRecords("""
                IDDIALOGUE: 111 - INTERNALKEY: K1 - UNIQUECODE: U1
                IDDIALOGUE: 222 - INTERNALKEY: K2 - UNIQUECODE: U2
                STATUS: OK
                """);

        assertThat(records.get(0).status()).isNull();
        assertThat(records.get(1).status()).isEqualTo("OK");
    }

    @Test
    @DisplayName("a status further down its own block is still read")
    void readsAStatusSeparatedFromItsHeader() throws IOException {
        List<RecordStatusDto> records = parseRecords("""
                IDDIALOGUE: 111 - INTERNALKEY: K1 - UNIQUECODE: U1
                Elab-Msg  CodeAction[GD000] order[1]
                Elab-Msg  CodeAction[GD000] order[2]
                Elab-Msg  CodeAction[GD000] order[3]
                Elab-Msg  CodeAction[GD000] order[4]
                STATUS: OK
                """);

        // A fixed look-ahead window would have missed this and reported it as unreported.
        assertThat(records).hasSize(1);
        assertThat(records.get(0).status()).isEqualTo("OK");
    }

    @Test
    @DisplayName("a rejection reason is attached even when no status was printed")
    void attachesReasonToAnUnreportedRecord() throws IOException {
        List<RecordStatusDto> records = parseRecords("""
                IDDIALOGUE: 222 - INTERNALKEY: K2 - UNIQUECODE: U2
                RECORD SCARTATO : PSNHISTDIALOGUEMSG.IDDIALOGUEMSG [222] : Configurazione non presente
                """);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).status()).isNull();
        // The reason is usually why the record never got a status, so it is worth keeping.
        assertThat(records.get(0).koReason()).isEqualTo("Configurazione non presente");
    }

    @Test
    @DisplayName("the record list accounts for every record the log declared")
    void listsAsManyRecordsAsTheLogDeclared() throws IOException {
        List<RecordStatusDto> records = parseRecords("""
                IDDIALOGUE: 1 - INTERNALKEY: K - UNIQUECODE: U
                STATUS: OK
                IDDIALOGUE: 2 - INTERNALKEY: K - UNIQUECODE: U
                STATUS: KO
                IDDIALOGUE: 3 - INTERNALKEY: K - UNIQUECODE: U
                """);

        long ok = records.stream().filter(r -> "OK".equals(r.status())).count();
        long ko = records.stream().filter(r -> "KO".equals(r.status())).count();
        long unreported = records.stream().filter(r -> r.status() == null).count();

        assertThat(ok + ko + unreported).isEqualTo(records.size()).isEqualTo(3);
    }

    private List<RecordStatusDto> parseRecords(String body) throws IOException {
        String log = """
                BATCH_VERSION: 1.0
                RunId: RUN-1
                """ + body;

        LogSource source = LogSource.read(
                new MockMultipartFile("stdout", "stdout_job.txt", "text/plain",
                        log.getBytes(StandardCharsets.UTF_8)),
                null);

        return profile.parseRecords(source);
    }
}

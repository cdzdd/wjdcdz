package com.hengsheng.fund;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApplicationExportRecoveryTest extends ApplicationExportFixture {
    @Test void automaticScheduleRetriesRejectedQueueWithNewRunAndPreservesCompletedFile() {
        long rejected=db.insert("INSERT INTO clearing_runs(type,business_date,parameters,fingerprint,business_time,state,reason,created_at) VALUES('EXPORT',?,'{}',?,'2090-05-16 16:00:00','REJECTED_QUEUE_FULL','full',NOW())",date,tag);
        db.update("UPDATE application_exports SET run_id=? WHERE id=?",rejected,export);
        var submission=mock(ClearingService.class);
        when(submission.submit(anyString(),any(),anyMap(),isNull(),anyString())).thenReturn(999L);
        when(submission.get(999L)).thenReturn(Map.of("state","QUEUED"));
        var scheduler=new ApplicationExportService(db,accounts,submission,manager,json,directory.toString());
        scheduler.scheduleDue();
        assertThat(service.get(export).get("runId")).isEqualTo(999L);
        db.update("UPDATE application_exports SET state='COMPLETED',run_id=? WHERE id=?",rejected,export);
        scheduler.scheduleDue();
        assertThat(service.get(export).get("runId")).isEqualTo(rejected);
    }
    @Test void partialFileCannotBeDownloadedAndRetryUsesOriginalSnapshot() throws Exception {
        order("PURCHASE",1,"SUBMITTED",trade,card,fund,account);
        service.generate(export);
        byte[] original=Files.readAllBytes(service.file(export));
        String name=service.get(export).get("fileName").toString();
        Files.delete(service.file(export));
        Files.writeString(directory.resolve(name+".part"),"incomplete");
        db.update("UPDATE application_exports SET state='RUNNING',checksum=NULL WHERE id=?",export);
        assertThatThrownBy(()->service.file(export)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(409));
        db.update("UPDATE orders SET amount=999,status='CANCELLED' WHERE fund_class_id=?",fund);
        service.generate(export);
        assertThat(Files.readAllBytes(service.file(export))).isEqualTo(original);
        assertThat(Files.exists(directory.resolve(name+".part"))).isFalse();
    }

    @Test void filesystemFailureKeepsSnapshotAndCanRetryAfterStorageIsRestored() throws Exception {
        order("PURCHASE",1,"SUBMITTED",trade,card,fund,account);
        Path blocked=directory.resolve("not-a-directory");Files.writeString(blocked,"occupied");
        var failing=new ApplicationExportService(db,accounts,clearing,manager,json,blocked.toString());
        assertThatThrownBy(()->failing.generate(export)).isInstanceOf(IllegalStateException.class);
        assertThat(service.get(export)).containsEntry("state","FAILED").containsEntry("rowCount",1L);
        assertThat(service.get(export).get("errorReason")).isNotNull();
        assertThatThrownBy(()->failing.file(export)).isInstanceOf(ApiException.class);
        db.update("UPDATE orders SET amount=999 WHERE fund_class_id=?",fund);
        Files.delete(blocked);
        failing.generate(export);
        assertThat(Files.readString(failing.file(export))).contains(",123.45,,SIM000001,1,");
        assertThat(service.get(export)).containsEntry("state","COMPLETED").containsEntry("errorReason",null);
    }

    @Test void missingChecksumOrPartialFilenameCannotBeDownloadedEvenIfMarkedCompleted() throws Exception {
        Files.writeString(directory.resolve("unsafe.part"),"incomplete");
        db.update("UPDATE application_exports SET state='COMPLETED',file_name='unsafe.part',checksum=NULL WHERE id=?",export);
        assertThatThrownBy(()->service.file(export)).isInstanceOf(ApiException.class);
        db.update("UPDATE application_exports SET checksum=REPEAT('a',64) WHERE id=?",export);
        assertThatThrownBy(()->service.file(export)).isInstanceOf(ApiException.class);
    }

    @Test void incompleteCanonicalRowCannotBePublishedAsCompleted() {
        db.update("UPDATE application_exports SET file_name='invalid.csv',row_count=1 WHERE id=?",export);
        db.insert("INSERT INTO application_export_items(export_id,source_type,source_id,canonical_row) VALUES(?,'ORDER',1,'{}')",export);
        assertThatThrownBy(()->service.generate(export)).isInstanceOf(IllegalStateException.class).hasMessageContaining("ORDER:1");
        assertThat(service.get(export).get("state")).isEqualTo("FAILED");
        assertThatThrownBy(()->service.file(export)).isInstanceOf(ApiException.class);
    }

    @Test void failedMissingSourceRollsBackSnapshotThenCorrectedAcceptanceCanRetry() {
        long conversion=conversion(1,"SUBMITTED",null);
        assertThatThrownBy(()->service.generate(export)).isInstanceOf(IllegalStateException.class);
        assertThat(service.get(export).get("fileName")).isNull();
        assertThat(db.scalar("SELECT COUNT(*) FROM application_export_items WHERE export_id=?",export)).isZero();
        db.update("UPDATE conversions SET accepted_source_card_id=? WHERE id=?",card,conversion);
        service.generate(export);
        assertThat(service.get(export)).containsEntry("state","COMPLETED").containsEntry("rowCount",1L);
    }

    @Test void longFailureReasonIsRecordedWithoutReplacingOriginalFailure() throws Exception {
        var invalid=new ApplicationExportService(db,accounts,clearing,manager,json,directory.resolve("x".repeat(1400)).toString());
        Throwable failure=catchThrowable(()->invalid.generate(export));
        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(failure.getMessage().length()).isGreaterThan(1000);
        assertThat(service.get(export).get("state")).isEqualTo("FAILED");
        assertThat(service.get(export).get("errorReason").toString().length()).isEqualTo(1000);
    }
}

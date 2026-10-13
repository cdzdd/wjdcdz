package com.hengsheng.fund;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApplicationExportCompletionClockTest extends ApplicationExportFixture {
    @BeforeTransaction void capturedQueueClock(){ClearingExecutionContext.enter(0,LocalDateTime.of(2090,5,16,15,5),null);}
    @AfterTransaction void clearQueueClock(){ClearingExecutionContext.clear();}
    @Test void completionUsesCurrentBusinessClockInsteadOfCapturedSubmissionTime(){
        service.generate(export);
        assertThat(service.get(export).get("completedBusinessTime").toString()).isEqualTo("2090-05-16T16:00");
        assertThat(Db.yes(service.get(export).get("completedBeforeDeadline"))).isFalse();
    }
}

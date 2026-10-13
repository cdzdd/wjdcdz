package com.hengsheng.fund;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledJobs {
    private static final Logger log=LoggerFactory.getLogger(ScheduledJobs.class);
    private final ClearingService clearing;private final ApplicationExportService exports;private final AccountService accounts;private final boolean enabled;
    public ScheduledJobs(ClearingService clearing,ApplicationExportService exports,AccountService accounts,@Value("${fund.scheduler.enabled:true}") boolean enabled){this.clearing=clearing;this.exports=exports;this.accounts=accounts;this.enabled=enabled;}
    @Scheduled(fixedDelay=300000,initialDelay=300000) public void scan(){if(!enabled)return;try{clearing.submit("ALL",accounts.clock().toLocalDate(),java.util.Map.of(),null,"scheduler-"+java.util.UUID.randomUUID());}catch(Exception ex){log.error("Scheduled simulation scan failed: {}",ex.getMessage());}try{exports.scheduleDue();}catch(Exception ex){log.error("Scheduled application export failed: {}",ex.getMessage());}}
}

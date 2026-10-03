package com.hengsheng.fund;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledJobs {
    private static final Logger log=LoggerFactory.getLogger(ScheduledJobs.class);
    private final CoreController core;private final AccountService accounts;private final boolean enabled;
    public ScheduledJobs(CoreController core,AccountService accounts,@Value("${fund.scheduler.enabled:true}") boolean enabled){this.core=core;this.accounts=accounts;this.enabled=enabled;}
    @Scheduled(fixedDelay=300000,initialDelay=300000) public void scan(){if(!enabled)return;try{core.runJobs("ALL",accounts.clock().toLocalDate());}catch(Exception ex){log.error("Scheduled simulation scan failed: {}",ex.getMessage());}}
}

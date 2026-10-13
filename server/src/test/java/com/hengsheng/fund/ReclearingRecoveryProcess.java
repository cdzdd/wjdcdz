package com.hengsheng.fund;

import java.util.Set;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Separate process used only by the recovery integration test; binds an ephemeral test port. */
public class ReclearingRecoveryProcess {
    private static long requestedRun;
    public static void main(String[] args) throws Exception {
        long run=Long.parseLong(args[0]);requestedRun=run;
        String url=System.getenv("DB_TEST_URL");if(url!=null&&!url.matches("^jdbc:mysql://[^/]+/(fund_sim_test|fund_sim_upgrade_test)\\?.*"))throw new IllegalStateException("Recovery test requires fund_sim_test");
        try(var context=new SpringApplicationBuilder(FundApplication.class,RecoveryRunner.class).profiles("test").run("--server.port=0","--spring.main.banner-mode=off")){
            var db=context.getBean(Db.class);
            if(db.scalar("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID=CONNECTION_ID() AND DB IN('fund_sim_test','fund_sim_upgrade_test')").intValue()!=1)throw new IllegalStateException("Recovery test requires fund_sim_test");
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(50);
            while(System.nanoTime()<deadline){String state=db.one("SELECT state FROM clearing_runs WHERE id=?",run).get("state").toString();if("COMPLETED".equals(state))return;if(Set.of("FAILED","PARTIAL","WAITING_DATA").contains(state))throw new IllegalStateException("Recovery did not complete: "+state);Thread.sleep(50);}
            throw new IllegalStateException("Recovery timed out");
        }
    }
    @TestConfiguration(proxyBeanMethods=false)
    public static class RecoveryRunner {
        @Bean ApplicationRunner resume(ClearingService clearing){
            // The real recovery and worker entry points install their routing before transactions.
            // Run before ApplicationReady starts this process's regular poller.
            return args->{clearing.recover();clearing.execute(requestedRun);};
        }
    }
}

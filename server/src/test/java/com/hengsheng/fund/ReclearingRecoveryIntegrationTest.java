package com.hengsheng.fund;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="reclearing.recovery.test=true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ReclearingRecoveryIntegrationTest extends ReclearingIntegrationTest {
    @Autowired ClearingService clearing;
    @Test void killedPublisherRollsBackThenFreshJvmRecoversSameRunOnce() throws Exception {
        assertThat(db.scalar("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID=CONNECTION_ID() AND DB IN('fund_sim_test','fund_sim_upgrade_test')")).isEqualByComparingTo("1");
        clearing.stop();
        String oldClock=db.one("SELECT value FROM app_settings WHERE name='demo_clock'").get("value").toString();var oldRate=db.scalar("SELECT purchase_rate FROM fund_classes WHERE id=1");
        var fixture=new LinkedHashMap<String,Object>();
        long run=new TransactionTemplate(manager).execute(status->{try{var a=card();fixture.put("card",a);fixture.put("order",buy(a));var p=preview(revision("1.25"));fixture.put("preview",p);long value=execute(p,"recovery");db.update("UPDATE clearing_runs SET state='RUNNING' WHERE id=?",value);return value;}catch(Exception e){throw new RuntimeException(e);}});
        long aid=Db.id(((Map<?,?>)fixture.get("card")).get("tid"));Process crashing=null,restarted=null;
        try(var lock=db.jdbc.getDataSource().getConnection();var read=db.jdbc.getDataSource().getConnection()){
            lock.setAutoCommit(false);read.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
            try(var statement=lock.createStatement()){statement.executeQuery("SELECT sequence_value FROM business_number_sequences WHERE kind='CONFIRMATION' AND business_date='2026-10-13' FOR UPDATE").close();}
            crashing=process(run,"reclearing-recovery-crash.log");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);boolean changed=false;
            while(System.nanoTime()<deadline&&crashing.isAlive()){try(var statement=read.createStatement();var result=statement.executeQuery("SELECT shares FROM position_lots WHERE trading_account_id="+aid)){changed=result.next()&&result.getBigDecimal(1).compareTo(new java.math.BigDecimal("800"))==0;}if(changed)break;Thread.sleep(50);}
            assertThat(changed).as("child JVM reached publication writes before its confirmation-number lock").isTrue();
            crashing.destroyForcibly();assertThat(crashing.waitFor(10,TimeUnit.SECONDS)).isTrue();lock.rollback();
            assertThat(db.scalar("SELECT shares FROM position_lots WHERE trading_account_id=?",aid)).isEqualByComparingTo("1000");
            assertThat(db.scalar("SELECT nav FROM nav_records WHERE fund_class_id=? AND date='2026-10-12'",fund)).isEqualByComparingTo("1");
            assertThat(db.one("SELECT state FROM clearing_runs WHERE id=?",run).get("state")).isEqualTo("RUNNING");
            restarted=process(run,"reclearing-recovery-restart.log");assertThat(restarted.waitFor(45,TimeUnit.SECONDS)).isTrue();assertThat(restarted.exitValue()).isZero();
            assertThat(db.one("SELECT state FROM clearing_runs WHERE id=?",run).get("state")).isEqualTo("COMPLETED");
            assertThat(db.scalar("SELECT shares FROM position_lots WHERE trading_account_id=?",aid)).isEqualByComparingTo("800");
            assertThat(db.scalar("SELECT COUNT(*) FROM trade_confirmations WHERE source_type='ORDER' AND source_id=?",fixture.get("order"))).isEqualByComparingTo("2");
            assertThat(execute((Map<String,Object>)fixture.get("preview"),"recovery")).isEqualTo(run);apply(run);
            assertThat(db.scalar("SELECT COUNT(*) FROM trade_confirmations WHERE source_type='ORDER' AND source_id=?",fixture.get("order"))).isEqualByComparingTo("2");
        }finally{
            if(crashing!=null&&crashing.isAlive()){crashing.destroyForcibly();crashing.waitFor(10,TimeUnit.SECONDS);}if(restarted!=null&&restarted.isAlive()){restarted.destroyForcibly();restarted.waitFor(10,TimeUnit.SECONDS);}
            db.update("UPDATE clearing_runs SET state='COMPLETED',active_scope=NULL WHERE id=?",run);db.update("UPDATE fund_classes SET state='TERMINATED',purchase_open=FALSE,redemption_open=FALSE WHERE id IN(?,?)",fund,target);db.update("UPDATE fund_classes SET purchase_rate=? WHERE id=1",oldRate);clock(oldClock);
        }
    }
    private Process process(long run,String log) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        return new ProcessBuilder(java,"-cp",classpath,ReclearingRecoveryProcess.class.getName(),String.valueOf(run)).redirectErrorStream(true).redirectOutput(Path.of("target",log).toFile()).start();
    }
}

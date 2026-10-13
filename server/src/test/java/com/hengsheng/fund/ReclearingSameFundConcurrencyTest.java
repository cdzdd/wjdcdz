package com.hengsheng.fund;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class ReclearingSameFundConcurrencyTest extends ReclearingIntegrationTest {
    private String submissionDay;
    @Test void unrelatedAccountCanSubmitWhilePublisherHoldsAffectedAccount() throws Exception {
        assertThat(db.scalar("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID=CONNECTION_ID() AND DB IN('fund_sim_test','fund_sim_upgrade_test')")).isEqualByComparingTo("1");
        String oldClock=db.one("SELECT value FROM app_settings WHERE name='demo_clock'").get("value").toString();
        var originalRate=db.scalar("SELECT purchase_rate FROM fund_classes WHERE id=1");
        var transactions=new TransactionTemplate(manager);var cards=new ArrayList<Map<String,Object>>();
        long run=transactions.execute(status->{try{var a=card();cards.add(a);cards.add(card());buy(a);submissionDay=db.one("SELECT c.date FROM trading_calendar c LEFT JOIN application_days d ON d.business_date=c.date WHERE c.open=TRUE AND c.date>'2026-10-20' AND d.business_date IS NULL ORDER BY c.date LIMIT 1").get("date").toString();clock(submissionDay+"T09:00:00");db.update("INSERT IGNORE INTO business_number_sequences(kind,business_date,sequence_value) VALUES('CONFIRMATION',?,0)",submissionDay);var p=preview(revision("1.25"));long value=execute(p,"concurrent");db.update("UPDATE clearing_runs SET state='RUNNING' WHERE id=?",value);return value;}catch(Exception e){throw new RuntimeException(e);}});
        var orders=new ArrayList<Long>();
        try(var lock=db.jdbc.getDataSource().getConnection();var read=db.jdbc.getDataSource().getConnection();var executor=Executors.newFixedThreadPool(3)){
            lock.setAutoCommit(false);read.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
            try(var statement=lock.createStatement()){statement.executeQuery("SELECT sequence_value FROM business_number_sequences WHERE kind='CONFIRMATION' AND business_date='"+submissionDay+"' FOR UPDATE").close();}
            try {
                var publishing=executor.submit(()->{apply(run);return true;});
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean changed=false;
                while(System.nanoTime()<deadline){try(var statement=read.createStatement();var result=statement.executeQuery("SELECT shares FROM position_lots WHERE trading_account_id="+cards.getFirst().get("tid"))){changed=result.next()&&result.getBigDecimal(1).compareTo(new java.math.BigDecimal("800"))==0;}if(changed)break;Thread.sleep(20);}
                assertThat(changed).as("publisher has changed the lot while retaining its account lock").isTrue();
                var affected=executor.submit(()->submit(cards.getFirst(),"affected"));
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);boolean dayEntered=false;
                while(System.nanoTime()<deadline){try(var statement=read.createStatement();var result=statement.executeQuery("SELECT state FROM application_days WHERE business_date='"+submissionDay+"'")){dayEntered=result.next();}if(dayEntered)break;Thread.sleep(20);}
                assertThat(dayEntered).as("affected request has entered the new trading day gate").isTrue();
                var unrelated=executor.submit(()->submit(cards.getLast(),"unrelated"));
                try{orders.add(unrelated.get(3,TimeUnit.SECONDS));}catch(TimeoutException e){throw new AssertionError("Unrelated account is blocked: "+db.rows("SELECT STATE,INFO FROM information_schema.PROCESSLIST WHERE DB IN('fund_sim_test','fund_sim_upgrade_test') AND COMMAND<>'Sleep'"),e);}
                assertThat(affected.isDone()).isFalse();assertThat(publishing.isDone()).isFalse();
                lock.rollback();assertThat(publishing.get(5,TimeUnit.SECONDS)).isTrue();orders.add(affected.get(5,TimeUnit.SECONDS));
                assertThat(db.scalar("SELECT shares FROM position_lots WHERE trading_account_id=?",cards.getFirst().get("tid"))).isEqualByComparingTo("800");
            }finally{lock.rollback();}
        }finally{
            for(var order:db.rows("SELECT id FROM orders WHERE user_id=? AND status='SUBMITTED'",user))trades.cancel(user,Db.id(order.get("id")));
            db.update("UPDATE clearing_runs SET state='COMPLETED',active_scope=NULL WHERE id=?",run);
            db.update("UPDATE fund_classes SET state='TERMINATED',purchase_open=FALSE,redemption_open=FALSE WHERE id IN(?,?)",fund,target);
            db.update("UPDATE fund_classes SET purchase_rate=? WHERE id=1",originalRate);clock(oldClock);
        }
    }
    private long submit(Map<String,Object> card,String key){return Db.id(trades.submit(user,Map.of("type","PURCHASE","fundClassId",fund,"tradingAccountId",card.get("tid"),"bankCardId",card.get("id"),"bindingVersion",1,"amount","100.00","requestKey",key)).get("id"));}
}

package com.hengsheng.fund;

import java.time.LocalDateTime;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Captured queue identity and clock; installed before acquiring a transaction connection. */
public final class ClearingExecutionContext {
    private record Context(long runId,LocalDateTime businessTime,Long actorId) {}
    private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    private ClearingExecutionContext() {}
    public static long currentRunId(){var c=CURRENT.get();return c==null?0:c.runId();}
    public static LocalDateTime businessTime(){var c=CURRENT.get();return c==null?null:c.businessTime();}
    public static Long actorId(){var c=CURRENT.get();return c==null?null:c.actorId();}
    public static boolean batch(){return CURRENT.get()!=null;}
    static void enter(long runId,LocalDateTime businessTime,Long actorId){
        if(TransactionSynchronizationManager.isActualTransactionActive()||batch())throw new IllegalStateException("清算路由必须在事务开始前设置");
        CURRENT.set(new Context(runId,businessTime,actorId));
    }
    static void clear(){CURRENT.remove();}
}

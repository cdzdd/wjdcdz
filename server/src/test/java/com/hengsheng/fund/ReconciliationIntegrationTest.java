package com.hengsheng.fund;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @ActiveProfiles("test") @Transactional
class ReconciliationIntegrationTest {
    @Autowired Db db;
    @Autowired AccountService accounts;
    @Autowired TradeService trades;
    @Autowired PortfolioService portfolio;
    @Test void detectsFrozenShareAndCostMismatchEvenWhenTotalSharesMatch(){
        var account=db.one("SELECT id,user_id,bank_card_id FROM trading_accounts ORDER BY id LIMIT 1");
        long uid=Db.id(account.get("userId")),aid=Db.id(account.get("id"));
        long fa=accounts.linkFundAccount(uid,aid,1);
        long lot=trades.addLot(uid,aid,1,Db.id(account.get("bankCardId")),fa,new BigDecimal("10"),new BigDecimal("10"),LocalDate.of(2026,1,2),"TEST_RECON",UUID.randomUUID().toString());
        db.update("UPDATE position_lots SET frozen_shares=1,cost=11 WHERE id=?",lot);
        var result=portfolio.reconciliation();
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("issues").toString()).contains("持仓批次"+lot);
    }
}

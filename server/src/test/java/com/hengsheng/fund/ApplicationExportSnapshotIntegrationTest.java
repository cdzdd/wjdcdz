package com.hengsheng.fund;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApplicationExportSnapshotIntegrationTest extends ApplicationExportFixture {
    @org.springframework.beans.factory.annotation.Autowired FundService funds;
    @Test void acceptedSourceAndTargetCodesCannotChangeBeforeFirstSeal() throws Exception {
        order("PURCHASE",1,"SUBMITTED",trade,card,fund,account);conversion(2,"SUBMITTED",card);
        String original=db.one("SELECT code FROM fund_classes WHERE id=?",fund).get("code").toString(),originalTarget=db.one("SELECT code FROM fund_classes WHERE id=?",target).get("code").toString();
        var security=org.springframework.security.core.context.SecurityContextHolder.getContext();var prior=security.getAuthentication();security.setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken("admin","","ROLE_ADMIN"));
        try{assertThatThrownBy(()->funds.saveFund(fund,Map.of("code","BROKEN_SOURCE"))).isInstanceOf(ApiException.class);assertThatThrownBy(()->funds.saveFund(target,Map.of("code","BROKEN_TARGET"))).isInstanceOf(ApiException.class);}finally{security.setAuthentication(prior);}
        service.generate(export);var lines=Files.readAllLines(service.file(export));
        assertThat(lines.get(1)).contains(","+original+",,");assertThat(lines.get(2)).contains(","+original+","+originalTarget+",");
    }
    @Test void successfulFilePersistsByteCountAndPerBusinessInputTotals() throws Exception {
        order("PURCHASE",1,"CONFIRMED",trade,card,fund,account);
        order("REDEMPTION",2,"CONFIRMED",trade,card,fund,account);
        service.generate(export);
        assertThat(service.get(export).get("fileBytes")).isEqualTo(Files.size(service.file(export)));
        assertThat(service.get(export).get("businessSummary")).isInstanceOfSatisfying(Map.class,summary->{
            assertThat((Map)summary.get("PURCHASE")).containsEntry("rowCount",1).containsEntry("amountTotal","123.45").containsEntry("sharesTotal","0.0000");
            assertThat((Map)summary.get("REDEMPTION")).containsEntry("rowCount",1).containsEntry("amountTotal","0.00").containsEntry("sharesTotal","12.3456");
        });
    }
    @Test void allAcceptedBusinessesKeepOriginalInputAndSourceTa() throws Exception {
        long purchase=order("PURCHASE",3,"CONFIRMED",trade,card,fund,account);
        order("REDEMPTION",4,"CONFIRMED",trade,card,fund,account);
        order("OFFERING",5,"SUBMITTED",otherTrade,otherCard,fund,account);
        order("PURCHASE",6,"SUBMITTED",otherTrade,otherCard,fund,account); // generated SIP is one purchase
        conversion(2,"CONFIRMED",card);
        order("PURCHASE",7,"CANCELLED",trade,card,fund,account);
        conversion(8,"CANCELLED",card);
        order("PURCHASE",9,"SUBMITTED",trade,card,target,targetAccount);
        service.generate(export);
        String csv=Files.readString(service.file(export));
        var lines=csv.split("\r\n");
        assertThat(lines).hasSize(6);
        assertThat(lines[1]).contains("20900516000002,CONVERSION,TR000001,FA000001,").contains(","+ta+","+otherTa+",,12.3456,");
        assertThat(lines[2]).contains("20900516000003,PURCHASE,").contains(",123.45,,SIM000001,1,2090-05-16T10:00:00,ORDER,"+purchase);
        assertThat(lines[3]).contains("REDEMPTION,").contains(",,,12.3456,SIM000001,1,");
        assertThat(lines[4]).contains("OFFERING,TR000002,FA000001,").contains(",123.45,,SIM000002,2,");
        assertThat(lines[5]).contains("PURCHASE,TR000002,FA000001,").contains(",123.45,,SIM000002,2,");
        assertThat(csv).doesNotContain("20900516000007","20900516000008","20900516000009");
        assertThat(service.get(export)).containsEntry("rowCount",5L).containsEntry("excludedCancelledCount",2L).containsEntry("amountTotal","370.35").containsEntry("sharesTotal","24.6912");
    }

    @Test void protocolEmptyFileHasNoBomFixedHeaderNameAndDeadline() throws Exception {
        service.generate(export);
        Path file=service.file(export);
        assertThat(file.getFileName().toString()).isEqualTo("applications_20900516_TA"+ta+"_v1.csv");
        assertThat(Files.readString(file,StandardCharsets.UTF_8)).isEqualTo("protocol_version,application_date,application_no,business_type,trading_account_no,fund_account_no,source_fund_code,target_fund_code,source_ta_id,target_ta_id,application_amount,application_shares,source_sim_card_no,binding_version,accepted_business_time,source_type,source_id\r\n");
        assertThat(service.get(export)).containsEntry("rowCount",0L);
        assertThat(service.get(export).get("deadlineBusinessTime").toString()).isEqualTo("2090-05-16T15:30");
        assertThat(service.get(export).get("completedBusinessTime").toString()).isEqualTo("2090-05-16T16:00");
        assertThat(Db.yes(service.get(export).get("completedBeforeDeadline"))).isFalse();
    }

    @Test void sameSnapshotRegenerationHasSameChecksumAfterRenameCardChangeAndConfirmation() throws Exception {
        long id=order("PURCHASE",1,"SUBMITTED",trade,card,fund,account);
        conversion(2,"SUBMITTED",card);
        service.generate(export);
        byte[] first=Files.readAllBytes(service.file(export));String checksum=service.get(export).get("checksum").toString();
        db.update("UPDATE users SET display_name='后来改名' WHERE id=?",user);
        db.update("UPDATE bank_cards SET sim_number=? WHERE id=?","CHANGED"+tag,card);
        db.update("UPDATE trading_accounts SET bank_card_id=?,binding_version=9,account_no=? WHERE id=?",otherCard,"CHANGED-TR"+tag,trade);
        db.update("UPDATE fund_classes SET code=?,name='后来改名' WHERE id=?","CHANGED-F"+tag,fund);
        db.update("UPDATE orders SET status='CONFIRMED',amount=999,shares=888 WHERE id=?",id);
        db.update("UPDATE conversions SET status='CANCELLED' WHERE source_class_id=?",fund);
        service.generate(export);
        assertThat(Files.readAllBytes(service.file(export))).isEqualTo(first);
        assertThat(service.get(export).get("checksum")).isEqualTo(checksum);
        assertThat(checksum).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(first)));
        assertThat(db.scalar("SELECT COUNT(*) FROM application_export_items WHERE export_id=?",export)).isEqualByComparingTo("2");
    }

    @Test void moreThanOnePageKeepsEveryApplicationSorted() throws Exception {
        for(int i=401;i>=1;i--)order("PURCHASE",i,"SUBMITTED",trade,card,fund,account);
        service.generate(export);
        var lines=Files.readAllLines(service.file(export));
        assertThat(lines).hasSize(402);
        assertThat(lines.get(1)).contains("20900516000001,PURCHASE");
        assertThat(lines.get(401)).contains("20900516000401,PURCHASE");
        assertThat(service.get(export).get("rowCount")).isEqualTo(401L);
    }

    @Test void fieldsWithCommaQuoteAndNewlineAreCsvEscaped() throws Exception {
        db.update("UPDATE bank_cards SET sim_number=? WHERE id=?","SIM,\"演示\"\n行",card);
        order("PURCHASE",1,"SUBMITTED",trade,card,fund,account);
        service.generate(export);
        assertThat(Files.readString(service.file(export))).contains("\"SIM,\"\"演示\"\"\n行\",1,2090-05-16T10:00:00");
    }

    @Test void missingAcceptanceSourceFailsWithSpecificSourceInsteadOfPublishingIncompleteFile() {
        long id=conversion(1,"SUBMITTED",null);
        assertThatThrownBy(()->service.generate(export)).isInstanceOf(IllegalStateException.class).hasMessageContaining("CONVERSION:"+id);
        assertThat(service.get(export).get("state")).isEqualTo("FAILED");
        assertThat(db.scalar("SELECT COUNT(*) FROM application_export_items WHERE export_id=?",export)).isZero();
        assertThatThrownBy(()->service.file(export)).isInstanceOf(ApiException.class);
    }

    @Test void missingOrderFundAccountCannotSilentlyDropRow() {
        long id=order("PURCHASE",1,"SUBMITTED",trade,card,fund,999999999L);
        assertThatThrownBy(()->service.generate(export)).isInstanceOf(IllegalStateException.class).hasMessageContaining("ORDER:"+id);
        assertThat(service.get(export).get("state")).isEqualTo("FAILED");
    }

    @Test void exportControllerRequiresAdminOrOperatorForDetailsAndDownloads() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(tag,"unused",List.of()));
        try {
            var controller=new ApplicationExportController(accounts,service,clearing);
            assertThatThrownBy(()->controller.get(export)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(403));
            assertThatThrownBy(()->controller.download(export)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(403));
        } finally {SecurityContextHolder.clearContext();}
    }
}

class ApplicationExportFixture {
    @Autowired Db db;
    @Autowired AccountService accounts;
    @Autowired ClearingService clearing;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @TempDir Path directory;
    ApplicationExportService service;
    final String tag="EX"+UUID.randomUUID().toString().substring(0,10);
    final LocalDate date=LocalDate.of(2090,5,16);
    long user,card,otherCard,trade,otherTrade,ta,otherTa,fund,target,account,targetAccount,export;
    @BeforeEach void fixture() {
        assertThat(db.one("SELECT DATABASE() name").get("name")).isIn("fund_sim_test","fund_sim_upgrade_test");
        db.update("UPDATE app_settings SET value='2090-05-16T16:00:00' WHERE name='demo_clock'");
        db.update("INSERT INTO trading_calendar(date,open) VALUES(?,TRUE) ON DUPLICATE KEY UPDATE open=TRUE",date);
        user=db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES(?,'unused','导出测试','INVESTOR')",tag);
        card=db.insert("INSERT INTO bank_cards(user_id,bank_name,sim_number,holder_name,request_key) VALUES(?,'测试',?,'测试','one')",user,"CARD1"+tag);
        otherCard=db.insert("INSERT INTO bank_cards(user_id,bank_name,sim_number,holder_name,request_key) VALUES(?,'测试',?,'测试','two')",user,"CARD2"+tag);
        // Original identifiers include leading zeros; uniqueness comes from isolated transaction cleanup.
        db.update("UPDATE bank_cards SET sim_number='SIM000001' WHERE id=?",card);
        db.update("UPDATE bank_cards SET sim_number='SIM000002' WHERE id=?",otherCard);
        trade=db.insert("INSERT INTO trading_accounts(user_id,account_no,bank_card_id) VALUES(?,'TR000001',?)",user,card);
        otherTrade=db.insert("INSERT INTO trading_accounts(user_id,account_no,bank_card_id,binding_version) VALUES(?,'TR000002',?,2)",user,otherCard);
        ta=db.insert("INSERT INTO registrars(name) VALUES(?)",tag);
        otherTa=db.insert("INSERT INTO registrars(name) VALUES(?)",tag+"target");
        fund=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level) VALUES(?,'导出测试','BOND','测试',?,1)",tag,ta);
        target=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level) VALUES(?,'目标测试','BOND','测试',?,1)",tag+"T",otherTa);
        account=db.insert("INSERT INTO fund_accounts(user_id,registrar_id,account_no) VALUES(?,?,'FA000001')",user,ta);
        targetAccount=db.insert("INSERT INTO fund_accounts(user_id,registrar_id,account_no) VALUES(?,?,?)",user,otherTa,tag+"TARGET");
        export=db.insert("INSERT INTO application_exports(business_date,registrar_id,protocol_version,created_at) VALUES(?,?,'MOCK-TA-APPLICATION-1',NOW())",date,ta);
        service=new ApplicationExportService(db,accounts,clearing,manager,json,directory.toString());
    }
    long order(String type,int sequence,String state,long tid,long cid,long fid,long fa) {
        String key=tag+sequence;
        return db.insert("""
            INSERT INTO orders(order_no,user_id,type,fund_class_id,trading_account_id,source_card_id,binding_version,fund_account_id,status,
            amount,shares,application_date,confirmation_date,payment_date,request_key,request_fingerprint,purchase_rate,redemption_rate,risk_level,rule_version,cutoff_time,created_at,application_no)
            VALUES(?,?,?,?,?,?,?,?,?,123.45,12.3456,?,?,?,?,'fixture',0,0,1,1,'15:00:00','2090-05-16 10:00:00',?)
            """,key,user,type,fid,tid,cid,tid==otherTrade?2:1,fa,state,date,date.plusDays(1),date.plusDays(2),key,"20900516"+String.format("%06d",sequence));
    }
    long conversion(int sequence,String state,Long cid) {
        return db.insert("""
            INSERT INTO conversions(user_id,trading_account_id,source_class_id,target_class_id,shares,status,application_date,confirmation_date,
            source_rate,target_rate,redemption_rate,source_rule_version,target_rule_version,request_key,created_at,application_no,accepted_source_card_id,accepted_binding_version,accepted_fund_account_id)
            VALUES(?,?,?,?,12.3456,?,?,?,0,0,0,1,1,?,'2090-05-16 10:00:00',?, ?,1,?)
            """,user,trade,fund,target,state,date,date.plusDays(1),tag+"C"+sequence,"20900516"+String.format("%06d",sequence),cid,account);
    }
}

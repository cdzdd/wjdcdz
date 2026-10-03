package com.hengsheng.fund;

import java.time.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoData implements ApplicationRunner {
    private final Db db;private final AccountService account;private final PasswordEncoder passwords;
    public DemoData(Db db,AccountService account,PasswordEncoder passwords){this.db=db;this.account=account;this.passwords=passwords;}
    @Override @Transactional public void run(ApplicationArguments args) {
        if(!account.isDemo()){String pass=System.getenv("ADMIN_PASSWORD");if(db.scalar("SELECT COUNT(*) FROM users WHERE role='ADMIN'").signum()==0){if(pass==null)throw new IllegalStateException("Production first start requires ADMIN_PASSWORD");AccountService.validatePassword(pass);db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES('admin',?,'管理员','ADMIN')",passwords.encode(pass));}return;}
        db.update("INSERT IGNORE INTO app_settings(name,value) VALUES('demo_clock','2026-10-12T14:00:00')");
        db.update("INSERT IGNORE INTO users(username,password_hash,display_name,role) VALUES('demo',?,'演示用户','INVESTOR')",passwords.encode("Demo12345!"));
        db.update("INSERT IGNORE INTO users(username,password_hash,display_name,role,opened,risk_level,risk_expires_at) VALUES('admin',?,'管理员','ADMIN',TRUE,5,'2027-10-12')",passwords.encode("Admin12345!"));
        db.update("INSERT IGNORE INTO registrars(id,name) VALUES(1,'模拟登记机构一'),(2,'模拟登记机构二'),(3,'模拟登记机构三')");
        String[] names={"恒生模拟成长股票 A","恒生模拟成长股票 C","恒生模拟价值股票 A","恒生模拟价值股票 C","恒生模拟均衡混合 A","恒生模拟均衡混合 C","恒生模拟稳健混合 A","恒生模拟稳健混合 C","恒生模拟优选债券 A","恒生模拟优选债券 C","恒生模拟新募成长 A","恒生模拟安心货币"};
        for(int i=0;i<12;i++){
            String type=i==11?"MONEY":i>=8&&i<=9?"BOND":i>=4&&i<=7?"MIXED":"STOCK";int risk=type.equals("MONEY")?1:type.equals("BOND")?2:type.equals("MIXED")?3:4;
            db.update("INSERT IGNORE INTO fund_classes(id,code,name,type,manager,registrar_id,risk_level,state,purchase_rate,redemption_rate) VALUES(?,?,?,?,?,?,?,?,?,?)",i+1,"HS"+String.format("%04d",i+1),names[i],type,"恒生模拟资产管理",i<6?1:i<10?2:3,risk,i==10?"RAISING":"OPEN",i%2==1||i==11?"0":"0.015",i==11?"0":"0.005");
        }
        for(LocalDate d=LocalDate.of(2026,9,1);!d.isAfter(LocalDate.of(2027,12,31));d=d.plusDays(1))db.update("INSERT IGNORE INTO trading_calendar(date,open) VALUES(?,?)",d,d.getDayOfWeek()!=DayOfWeek.SATURDAY&&d.getDayOfWeek()!=DayOfWeek.SUNDAY);
        for(int f=1;f<=12;f++)for(LocalDate d=LocalDate.of(2026,9,28);d.isBefore(LocalDate.of(2026,10,12));d=d.plusDays(1))if(d.getDayOfWeek()!=DayOfWeek.SATURDAY&&d.getDayOfWeek()!=DayOfWeek.SUNDAY)db.update("INSERT IGNORE INTO nav_records(fund_class_id,date,nav,published) VALUES(?,?,?,TRUE)",f,d,f==12?"1.000000":"1.200000");
        db.update("INSERT IGNORE INTO announcements(id,title,body,created_at) VALUES(1,'欢迎使用基金模拟平台','本站基金、资金、银行卡和净值均为教学模拟数据，不接真实交易。交易规则与收益不代表任何真实基金。',?)",account.clock());
    }
}

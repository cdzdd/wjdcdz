package com.hengsheng.fund;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Immutable, cutoff-bound rows. Snapshot and file generation use bounded pages. */
@Service
public class ApplicationExportService {
    static final String PROTOCOL="MOCK-TA-APPLICATION-1";
    static final List<String> FIELDS=List.of("protocol_version","application_date","application_no","business_type","trading_account_no","fund_account_no","source_fund_code","target_fund_code","source_ta_id","target_ta_id","application_amount","application_shares","source_sim_card_no","binding_version","accepted_business_time","source_type","source_id");
    private final Db db;private final AccountService accounts;private final ClearingService clearing;
    private final TransactionTemplate tx;private final ObjectMapper json;private final Path directory;
    public ApplicationExportService(Db db,AccountService accounts,ClearingService clearing,PlatformTransactionManager manager,ObjectMapper json,@Value("${fund.exports.directory:.runtime/ta-exports}") String directory){this.db=db;this.accounts=accounts;this.clearing=clearing;this.tx=new TransactionTemplate(manager);this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);this.json=json;this.directory=Path.of(directory).toAbsolutePath().normalize();}
    private LocalDateTime actual(){return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));}
    private LocalDateTime completionClock(){if(accounts.isDemo()){var clock=db.rows("SELECT value FROM app_settings WHERE name='demo_clock'");if(!clock.isEmpty())return LocalDateTime.parse(clock.getFirst().get("value").toString());}return actual();}
    private String text(Map<?,?> row,String key){return Objects.toString(row.get(key),"");}
    private BigDecimal inputDecimal(Map<?,?> row,String key){return text(row,key).isEmpty()?BigDecimal.ZERO:Db.decimal(row.get(key));}
    private LocalDateTime sealTime(LocalDate date){
        var cutoff=db.one("SELECT COALESCE(MAX(cutoff_time),'15:00:00') cutoff FROM fund_classes WHERE state IN('OPEN','RAISING','PAUSED','TERMINATED')");
        return date.atTime(LocalTime.parse(cutoff.get("cutoff").toString())).plusMinutes(5);
    }
    private void eligible(LocalDate date){
        if(!Db.yes(db.one("SELECT open FROM trading_calendar WHERE date=?",date).get("open")))throw ApiException.bad("申请文件仅针对开放工作日");
        if(accounts.clock().isBefore(sealTime(date)))throw ApiException.conflict("尚未到最晚产品截止后5分钟的封账时点");
    }
    public Map<String,Object> create(LocalDate date,long registrarId,String requestKey,long actorId){
        eligible(date);db.one("SELECT id FROM registrars WHERE id=?",registrarId);
        long exportId=tx.execute(status->{
            db.update("INSERT IGNORE INTO application_exports(business_date,registrar_id,protocol_version,actor_id,created_at) VALUES(?,?,?,?,?)",date,registrarId,PROTOCOL,actorId==0?null:actorId,actual());
            return Db.id(db.one("SELECT id FROM application_exports WHERE business_date=? AND registrar_id=? AND protocol_version=?",date,registrarId,PROTOCOL).get("id"));
        });
        long run=clearing.submit("EXPORT",date,Map.of("exportId",exportId,"targetId",exportId),actorId==0?null:actorId,requestKey);
        db.update("UPDATE application_exports SET run_id=? WHERE id=?",run,exportId);
        return Map.of("exportId",exportId,"runId",run,"state",clearing.get(run).get("state"));
    }
    public Map<String,Object> regenerate(long exportId,String requestKey,long actorId){
        var row=get(exportId);return create(LocalDate.parse(row.get("businessDate").toString()),Db.id(row.get("registrarId")),requestKey,actorId);
    }
    public List<Map<String,Object>> list(){return db.rows("SELECT * FROM application_exports ORDER BY id DESC LIMIT 200").stream().map(this::statistics).toList();}
    public Map<String,Object> get(long exportId){return statistics(db.one("SELECT * FROM application_exports WHERE id=?",exportId));}
    private Map<String,Object> statistics(Map<String,Object> row){if(row.get("businessSummary") instanceof String value)row.put("businessSummary",json.readValue(value,Map.class));return row;}
    private void summarize(Map<String,Map<String,Object>> summary,Map<?,?> values){
        var row=summary.computeIfAbsent(text(values,"business_type"),key->new LinkedHashMap<>(Map.of("rowCount",0L,"amountTotal","0.00","sharesTotal","0.0000")));
        row.put("rowCount",Db.id(row.get("rowCount"))+1);row.put("amountTotal",Db.decimal(row.get("amountTotal")).add(inputDecimal(values,"application_amount")).setScale(2).toPlainString());row.put("sharesTotal",Db.decimal(row.get("sharesTotal")).add(inputDecimal(values,"application_shares")).setScale(4).toPlainString());
    }
    private void snapshot(long exportId){tx.executeWithoutResult(status->{
        var export=db.one("SELECT * FROM application_exports WHERE id=? FOR UPDATE",exportId);if(export.get("fileName")!=null)return;
        LocalDate date=LocalDate.parse(export.get("businessDate").toString());eligible(date);long ta=Db.id(export.get("registrarId"));
        db.update("INSERT IGNORE INTO application_days(business_date,state) VALUES(?,'OPEN')",date);
        db.one("SELECT state FROM application_days WHERE business_date=? FOR UPDATE",date);
        long excluded=db.scalar("SELECT COUNT(*) FROM orders o JOIN fund_classes f ON f.id=o.fund_class_id WHERE o.application_date=? AND f.registrar_id=? AND o.status='CANCELLED'",date,ta).longValue()+db.scalar("SELECT COUNT(*) FROM conversions c JOIN fund_classes f ON f.id=c.source_class_id WHERE c.application_date=? AND f.registrar_id=? AND c.status='CANCELLED'",date,ta).longValue();
        for(String source:List.of("ORDER","CONVERSION")){
            String table=source.equals("ORDER")?"orders":"conversions",fund=source.equals("ORDER")?"fund_class_id":"source_class_id";
            var missing=db.rows("SELECT v.id FROM "+table+" v JOIN fund_classes f ON f.id=v."+fund+" WHERE v.application_date=? AND f.registrar_id=? AND v.status!='CANCELLED' AND v.application_no IS NULL LIMIT 1",date,ta);
            if(!missing.isEmpty())throw ApiException.conflict(source+":"+missing.getFirst().get("id")+" 历史申请缺少业务编号，须先完成有审计的编号迁移");
        }
        long count=0;BigDecimal amount=BigDecimal.ZERO,shares=BigDecimal.ZERO;String cursor="";var summary=new LinkedHashMap<String,Map<String,Object>>();
        while(true){
            var page=new ArrayList<Map<String,Object>>(db.rows("""
                SELECT o.id,o.application_no,o.type,o.binding_version,o.amount,o.shares,o.created_at,'ORDER' source_type,f.code source_fund_code,f.registrar_id source_ta_id,t.account_no trading_account_no,a.account_no fund_account_no,c.sim_number source_sim_card_no
                FROM orders o STRAIGHT_JOIN fund_classes f ON f.id=o.fund_class_id LEFT JOIN trading_accounts t ON t.id=o.trading_account_id
                LEFT JOIN fund_accounts a ON a.id=o.fund_account_id LEFT JOIN bank_cards c ON c.id=o.source_card_id
                WHERE o.application_date=? AND f.registrar_id=? AND o.application_no>? AND o.status!='CANCELLED' ORDER BY o.application_no LIMIT 200
                """,date,ta,cursor));
            page.addAll(db.rows("""
                SELECT v.id,v.application_no,v.accepted_binding_version,v.shares,v.created_at,'CONVERSION' source_type,f.code source_fund_code,g.code target_fund_code,f.registrar_id source_ta_id,g.registrar_id target_ta_id,t.account_no trading_account_no,a.account_no fund_account_no,c.sim_number source_sim_card_no
                FROM conversions v STRAIGHT_JOIN fund_classes f ON f.id=v.source_class_id LEFT JOIN fund_classes g ON g.id=v.target_class_id
                LEFT JOIN trading_accounts t ON t.id=v.trading_account_id LEFT JOIN bank_cards c ON c.id=v.accepted_source_card_id LEFT JOIN fund_accounts a ON a.id=v.accepted_fund_account_id
                WHERE v.application_date=? AND f.registrar_id=? AND v.application_no>? AND v.status!='CANCELLED' ORDER BY v.application_no LIMIT 200
                """,date,ta,cursor));
            if(page.isEmpty())break;
            page.sort(Comparator.comparing(row->text(row,"applicationNo")));
            var batch=new ArrayList<Object[]>();
            for(var row:page.subList(0,Math.min(200,page.size()))){
                cursor=text(row,"applicationNo");String source=text(row,"sourceType"),type=source.equals("ORDER")?text(row,"type"):"CONVERSION";
                boolean moneyInput=Set.of("PURCHASE","OFFERING").contains(type);
                var values=new LinkedHashMap<String,Object>();for(String field:FIELDS)values.put(field,"");
                values.put("protocol_version",PROTOCOL);values.put("application_date",date.toString());values.put("application_no",cursor);values.put("business_type",type);
                for(String[] pair:new String[][]{{"trading_account_no","tradingAccountNo"},{"fund_account_no","fundAccountNo"},{"source_fund_code","sourceFundCode"},{"target_fund_code","targetFundCode"},{"source_ta_id","sourceTaId"},{"target_ta_id","targetTaId"},{"source_sim_card_no","sourceSimCardNo"},{"binding_version",source.equals("CONVERSION")?"acceptedBindingVersion":"bindingVersion"}})values.put(pair[0],text(row,pair[1]));
                values.put("application_amount",moneyInput?Db.decimal(row.get("amount")).setScale(2).toPlainString():"");values.put("application_shares",moneyInput?"":Db.decimal(row.get("shares")).setScale(4).toPlainString());
                values.put("accepted_business_time",LocalDateTime.parse(text(row,"createdAt")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));values.put("source_type",source);values.put("source_id",text(row,"id"));
                validate(values,source+":"+row.get("id"));
                summarize(summary,values);
                batch.add(new Object[]{exportId,source,row.get("id"),json.writeValueAsString(values)});
                count++;if(moneyInput)amount=amount.add(Db.decimal(row.get("amount")));else shares=shares.add(Db.decimal(row.get("shares")));
            }
            db.update("INSERT INTO application_export_items(export_id,source_type,source_id,canonical_row) VALUES "+String.join(",",Collections.nCopies(batch.size(),"(?,?,?,?)")),batch.stream().flatMap(Arrays::stream).toArray());
        }
        String name="applications_"+date.toString().replace("-","")+"_TA"+ta+"_v"+export.get("snapshotVersion")+".csv";
        db.update("UPDATE application_exports SET file_name=?,row_count=?,excluded_cancelled_count=?,amount_total=?,shares_total=?,deadline_business_time=?,business_summary=? WHERE id=?",name,count,excluded,amount,shares,sealTime(date).plusMinutes(25),json.writeValueAsString(summary),exportId);
        db.update("UPDATE application_days SET state='SEALED',sealed_business_time=?,sealed_at=? WHERE business_date=?",accounts.clock(),actual(),date);
    });}
    private void validate(Map<?,?> values,String source){
        for(String key:List.of("protocol_version","application_date","application_no","business_type","trading_account_no","fund_account_no","source_fund_code","source_ta_id","source_sim_card_no","binding_version","accepted_business_time","source_type","source_id"))if(text(values,key).isBlank())throw ApiException.conflict(source+" 缺少可靠受理快照字段："+key);
        String type=text(values,"business_type");boolean money=Set.of("PURCHASE","OFFERING").contains(type);
        if(!PROTOCOL.equals(values.get("protocol_version"))||!Set.of("PURCHASE","OFFERING","REDEMPTION","CONVERSION").contains(type)||!text(values,"application_no").matches("[0-9]{14}")||!text(values,"binding_version").matches("[1-9][0-9]*")||!text(values,"source_id").matches("[1-9][0-9]*"))throw ApiException.conflict(source+" 受理快照编号、版本或业务类型不符合协议");
        if(!text(values,money?"application_amount":"application_shares").matches(money?"[0-9]+\\.[0-9]{2}":"[0-9]+\\.[0-9]{4}")||!text(values,money?"application_shares":"application_amount").isEmpty())throw ApiException.conflict(source+" 原申请金额或份额不符合协议");
        if(type.equals("CONVERSION")&&(text(values,"target_fund_code").isBlank()||text(values,"target_ta_id").isBlank()))throw ApiException.conflict(source+" 转换缺少目标基金/TA受理快照");
    }
    private String csvCell(Object value){String text=Objects.toString(value,"");return text.indexOf(',')>=0||text.indexOf('"')>=0||text.indexOf('\r')>=0||text.indexOf('\n')>=0?'"'+text.replace("\"","\"\"")+'"':text;}
    public Map<String,Object> generate(long exportId){
        long start=System.nanoTime();db.update("UPDATE application_exports SET state='RUNNING',started_at=?,error_reason=NULL WHERE id=?",actual(),exportId);
        try{
            clearing.node("SEAL_APPLICATIONS",()->{snapshot(exportId);return Map.of("processed",1,"waiting",0,"failed",0,"details",List.of("申请快照已封账，共"+get(exportId).get("rowCount")+"行"));});long snapshotNanos=System.nanoTime()-start;
            var result=clearing.node("EXPORT_APPLICATIONS",()->{try{return generateFile(exportId,start,snapshotNanos);}catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new IllegalStateException(ex.getMessage(),ex);}});
            db.update("UPDATE application_exports SET state='COMPLETED' WHERE id=?",exportId);return result;
        }catch(Exception ex){
            String reason=Objects.requireNonNullElse(ex.getMessage(),ex.getClass().getSimpleName());db.update("UPDATE application_exports SET state='FAILED',error_reason=? WHERE id=?",reason.substring(0,Math.min(1000,reason.length())),exportId);
            throw new IllegalStateException("申请文件生成失败："+reason,ex);
        }
    }
    private Map<String,Object> generateFile(long exportId,long start,long snapshotNanos) throws Exception {
            var export=get(exportId);Files.createDirectories(directory);Path complete=directory.resolve(export.get("fileName").toString()).normalize();
            if(!complete.startsWith(directory)||!complete.getFileName().toString().endsWith(".csv"))throw new IllegalStateException("申请文件路径不在受控目录");
            Path part=directory.resolve(export.get("fileName")+".part");MessageDigest digest=MessageDigest.getInstance("SHA-256");long written=0;BigDecimal amount=BigDecimal.ZERO,shares=BigDecimal.ZERO;
            var missing=db.rows("SELECT source_type,source_id FROM application_export_items WHERE export_id=? AND application_no IS NULL LIMIT 1",exportId);
            if(!missing.isEmpty())throw ApiException.conflict(missing.getFirst().get("sourceType")+":"+missing.getFirst().get("sourceId")+" 缺少申请编号快照");
            try(var out=new DigestOutputStream(Files.newOutputStream(part),digest);var writer=new BufferedWriter(new OutputStreamWriter(out,StandardCharsets.UTF_8))){
                writer.write(String.join(",",FIELDS));writer.write("\r\n");long cursor=0;String number="";
                while(true){var page=db.rows("SELECT id,application_no,source_type,source_id,canonical_row FROM application_export_items WHERE export_id=? AND (application_no>? OR (application_no=? AND id>?)) ORDER BY application_no,id LIMIT 200",exportId,number,number,cursor);if(page.isEmpty())break;for(var item:page){var values=json.readValue(item.get("canonicalRow").toString(),Map.class);validate(values,item.get("sourceType")+":"+item.get("sourceId"));for(int i=0;i<FIELDS.size();i++){if(i>0)writer.write(',');writer.write(csvCell(values.get(FIELDS.get(i))));}writer.write("\r\n");amount=amount.add(inputDecimal(values,"application_amount"));shares=shares.add(inputDecimal(values,"application_shares"));written++;cursor=Db.id(item.get("id"));number=text(item,"applicationNo");}}
            }
            if(written!=Db.id(export.get("rowCount"))||amount.compareTo(Db.decimal(export.get("amountTotal")))!=0||shares.compareTo(Db.decimal(export.get("sharesTotal")))!=0)throw new IllegalStateException("申请文件行数或金额/份额与固定快照不符");
            String checksum=HexFormat.of().formatHex(digest.digest());long bytes=Files.size(part);Files.move(part,complete,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            LocalDateTime completed=completionClock();boolean onTime=export.get("deadlineBusinessTime")!=null&&!completed.isAfter(LocalDateTime.parse(export.get("deadlineBusinessTime").toString()));
            db.update("UPDATE application_exports SET state='COMPLETED',checksum=?,file_bytes=?,completed_at=?,completed_business_time=?,completed_before_deadline=? WHERE id=?",checksum,bytes,actual(),completed,onTime,exportId);
            var result=new LinkedHashMap<String,Object>();result.putAll(Map.of("processed",1,"waiting",0,"failed",0,"details",List.of("申请文件已完成，共"+written+"行"),"exportId",exportId,"rowCount",written,"checksum",checksum,"elapsedMillis",(System.nanoTime()-start)/1_000_000));result.put("snapshotMillis",snapshotNanos/1_000_000);result.put("fileMillis",(System.nanoTime()-start-snapshotNanos)/1_000_000);result.put("fileBytes",bytes);return result;
    }
    public Path file(long exportId){var row=get(exportId);if(!"COMPLETED".equals(row.get("state"))||row.get("checksum")==null||row.get("fileName")==null||!row.get("fileName").toString().endsWith(".csv"))throw ApiException.conflict("申请文件尚未完整生成");Path path=directory.resolve(row.get("fileName").toString()).normalize();if(!path.startsWith(directory)||!Files.isRegularFile(path))throw ApiException.missing();return path;}
    public void scheduleDue(){
        LocalDate date=accounts.clock().toLocalDate();if(accounts.clock().isBefore(sealTime(date)))return;
        var open=db.rows("SELECT open FROM trading_calendar WHERE date=?",date);if(open.isEmpty()||!Db.yes(open.getFirst().get("open")))return;
        for(var registrar:db.rows("""
            SELECT t.id FROM registrars t WHERE NOT EXISTS (
             SELECT 1 FROM application_exports e LEFT JOIN clearing_runs r ON r.id=e.run_id
             WHERE e.registrar_id=t.id AND e.business_date=? AND e.protocol_version=?
             AND (e.state='COMPLETED' OR r.state IN ('QUEUED','RUNNING')))
            ORDER BY t.id
            """,date,PROTOCOL))create(date,Db.id(registrar.get("id")),"AUTO-EXPORT-"+date+"-"+registrar.get("id")+"-"+UUID.randomUUID(),0);
    }
}

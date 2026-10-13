package com.hengsheng.fund;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api")
public class ReclearingController {
    private final Db db;private final AccountService accounts;private final TradeService trades;
    private final NavRevisionService revisions;private final ReclearingService reclearing;private final ClearingService clearing;
    private final ObjectMapper json;
    public ReclearingController(Db db,AccountService accounts,TradeService trades,NavRevisionService revisions,ReclearingService reclearing,ClearingService clearing,ObjectMapper json){this.db=db;this.accounts=accounts;this.trades=trades;this.revisions=revisions;this.reclearing=reclearing;this.clearing=clearing;this.json=json;}
    @PostMapping("/admin/nav-revisions") public Map<String,Object> revision(@RequestBody Map<String,Object> input){
        accounts.requireRole("ADMIN");
        long id=revisions.createRevisionSet(List.of(Map.of("fundClassId",Db.id(input.get("fundClassId")),"marketDate",Db.text(input,"marketDate"),"nav",Db.text(input,"nav"))),Db.text(input,"requestKey"),Db.text(input,"reason"),accounts.userId());
        return Map.of("revisionSetId",id);
    }
    @PostMapping("/admin/nav-revisions/import") public Map<String,Object> importRevisions(@RequestParam MultipartFile file,@RequestParam String reason,@RequestParam String requestKey) throws Exception {
        accounts.requireRole("ADMIN");if(file.getSize()>5*1024*1024)throw ApiException.bad("行情CSV最大5MB");
        String[] lines=new String(file.getBytes(),StandardCharsets.UTF_8).replace("\uFEFF","").split("\\R");
        if(lines.length<2||!lines[0].trim().equals("fundClassId,date,nav"))throw ApiException.bad("CSV表头须为fundClassId,date,nav");
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=1;i<lines.length;i++){if(lines[i].isBlank())continue;String[] values=lines[i].split(",",-1);if(values.length!=3)throw ApiException.bad("CSV第"+(i+1)+"行格式错误");try{rows.add(Map.of("fundClassId",Long.parseLong(values[0].trim()),"marketDate",values[1].trim(),"nav",values[2].trim()));}catch(RuntimeException ex){throw ApiException.bad("CSV第"+(i+1)+"行产品编号无效");}}
        return Map.of("revisionSetId",revisions.createRevisionSet(rows,requestKey,reason,accounts.userId()));
    }
    @GetMapping("/admin/nav-revisions") public List<Map<String,Object>> revisionList(){accounts.requireRole("ADMIN");return db.rows("SELECT * FROM nav_revision_sets ORDER BY id DESC LIMIT 200");}
    @PostMapping("/admin/reclearing/previews") public Map<String,Object> preview(@RequestBody Map<String,Object> input){accounts.requireRole("ADMIN");var result=reclearing.preview(Db.id(input.get("revisionSetId")),accounts.userId());return getPreview(Db.id(result.get("previewId")));}
    private Map<String,Object> fullPreview(long id){var row=db.one("SELECT * FROM reclearing_previews WHERE id=?",id);var result=new LinkedHashMap<String,Object>(json.readValue(row.get("result").toString(),Map.class));result.put("previewId",id);result.put("revisionSetId",row.get("revisionSetId"));result.put("state",row.get("state"));result.put("runId",row.get("runId"));return result;}
    @GetMapping("/admin/reclearing/previews/{id}") public Map<String,Object> getPreview(@PathVariable long id){accounts.requireRole("ADMIN");var result=fullPreview(id);var changes=(List<?>)result.getOrDefault("changes",List.of());result.put("changesTotal",changes.size());result.put("changes",changes.subList(0,Math.min(200,changes.size())));result.put("nextCursor",changes.size()>200?200:null);return result;}
    @GetMapping("/admin/reclearing/previews/{id}/items") public Map<String,Object> previewItems(@PathVariable long id,@RequestParam(defaultValue="0") int afterId){accounts.requireRole("ADMIN");if(afterId<0)throw ApiException.bad("差异游标不能为负数");var all=(List<?>)fullPreview(id).getOrDefault("changes",List.of());int start=Math.min(afterId,all.size()),end=Math.min(start+200,all.size());var response=new LinkedHashMap<String,Object>();response.put("items",all.subList(start,end));response.put("nextCursor",end<all.size()?end:null);response.put("total",all.size());return response;}
    @PostMapping("/admin/reclearing/runs") public ResponseEntity<Map<String,Object>> run(@RequestBody Map<String,Object> input){accounts.requireRole("ADMIN");long id=reclearing.execute(Db.id(input.get("previewId")),Db.text(input,"scopeVersion"),Db.text(input,"requestKey"),accounts.userId());return clearing.submissionResponse(id,Map.of());}
    @GetMapping("/orders/{id}/confirmations") public List<Map<String,Object>> orderHistory(@PathVariable long id){trades.order(accounts.userId(),id);return history("ORDER",id);}
    @GetMapping("/conversions/{id}/confirmations") public List<Map<String,Object>> conversionHistory(@PathVariable long id){db.one("SELECT id FROM conversions WHERE id=? AND user_id=?",id,accounts.userId());return history("CONVERSION",id);}
    private List<Map<String,Object>> history(String source,long id){var rows=db.rows("SELECT * FROM trade_confirmations WHERE source_type=? AND source_id=? ORDER BY id DESC",source,id);for(var row:rows){var result=json.readValue(row.get("result").toString(),Map.class);for(String key:List.of("amount","shares","nav","netAmount","fee","sourceNav","targetNav","targetShares"))if(result.containsKey(key))row.put(key,result.get(key));row.put("marketDate",result.get("applicationDate"));row.put("processedAt",row.get("recordedAt"));row.put("status",row.get("state"));}return rows;}
}

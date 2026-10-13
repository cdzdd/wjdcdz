package com.hengsheng.fund;

import java.time.LocalDate;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/clearing-runs")
public class ClearingController {
    private final AccountService accounts;private final ClearingService clearing;
    public ClearingController(AccountService accounts,ClearingService clearing){this.accounts=accounts;this.clearing=clearing;}
    @PostMapping public ResponseEntity<Map<String,Object>> submit(@RequestBody Map<String,Object> input){
        accounts.requireRole("OPERATOR");
        LocalDate date=LocalDate.parse(input.getOrDefault("businessDate",input.getOrDefault("date",accounts.clock().toLocalDate())).toString());
        var parameters=input.get("parameters") instanceof Map<?,?> raw?new LinkedHashMap<String,Object>((Map<String,Object>)raw):new LinkedHashMap<>(input);
        long id=clearing.submit(Db.text(input,"type"),date,parameters,accounts.userId(),Db.text(input,"requestKey"));
        return clearing.submissionResponse(id,Map.of());
    }
    @GetMapping public List<Map<String,Object>> list(){accounts.requireRole("OPERATOR");return clearing.list();}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable long id){accounts.requireRole("OPERATOR");return clearing.get(id);}
}

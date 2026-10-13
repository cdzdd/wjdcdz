package com.hengsheng.fund;

import java.time.LocalDate;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/application-exports")
public class ApplicationExportController {
    private final AccountService accounts;private final ApplicationExportService exports;private final ClearingService clearing;
    public ApplicationExportController(AccountService accounts,ApplicationExportService exports,ClearingService clearing){this.accounts=accounts;this.exports=exports;this.clearing=clearing;}
    @PostMapping public ResponseEntity<Map<String,Object>> create(@RequestBody Map<String,Object> input){accounts.requireRole("OPERATOR");var result=exports.create(LocalDate.parse(Db.text(input,input.containsKey("date")?"date":"businessDate")),Db.id(input.get("registrarId")),Db.text(input,"requestKey"),accounts.userId());return clearing.submissionResponse(Db.id(result.get("runId")),result);}
    @GetMapping public List<Map<String,Object>> list(){accounts.requireRole("OPERATOR");return exports.list();}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable long id){accounts.requireRole("OPERATOR");return exports.get(id);}
    @PostMapping("/{id}/regenerate") public ResponseEntity<Map<String,Object>> regenerate(@PathVariable long id,@RequestBody Map<String,Object> input){accounts.requireRole("OPERATOR");var result=exports.regenerate(id,Db.text(input,"requestKey"),accounts.userId());return clearing.submissionResponse(Db.id(result.get("runId")),result);}
    @GetMapping("/{id}/download") public ResponseEntity<FileSystemResource> download(@PathVariable long id){accounts.requireRole("OPERATOR");var path=exports.file(id);return ResponseEntity.ok().contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8)).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(path.getFileName().toString()).build().toString()).body(new FileSystemResource(path));}
}

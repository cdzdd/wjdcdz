package com.hengsheng.fund;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Statement;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Time;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

@Component
public class Db {
    public final JdbcTemplate jdbc;
    public Db(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<Map<String,Object>> rows(String sql,Object...args) {
        return jdbc.query(sql,(rs,n)-> {
            Map<String,Object> row=new LinkedHashMap<>();
            for(int i=1;i<=rs.getMetaData().getColumnCount();i++) {
                String raw=rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT);
                StringBuilder key=new StringBuilder();boolean upper=false;
                for(char c:raw.toCharArray()) { if(c=='_') upper=true;else {key.append(upper?Character.toUpperCase(c):c);upper=false;} }
                Object v=rs.getObject(i);
                if(v instanceof BigDecimal d) v=d.toPlainString();
                else if(v instanceof Date d) v=d.toLocalDate().toString();
                else if(v instanceof Timestamp d) v=d.toLocalDateTime().toString();
                else if(v instanceof Time t) v=t.toLocalTime().toString();
                row.put(key.toString(),v);
            }
            return row;
        },args);
    }
    public Map<String,Object> one(String sql,Object...args) { var rows=rows(sql,args);if(rows.isEmpty())throw ApiException.missing();return rows.getFirst(); }
    public BigDecimal scalar(String sql,Object...args) { var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?BigDecimal.ZERO:decimal(rows.getFirst().values().iterator().next()); }
    public long insert(String sql,Object...args) {
        var holder=new GeneratedKeyHolder();
        jdbc.update(c->{var p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
    public int update(String sql,Object...args) { return jdbc.update(sql,args); }
    public static BigDecimal decimal(Object value) { return value==null?BigDecimal.ZERO:new BigDecimal(value.toString()); }
    public static BigDecimal money(BigDecimal value) { return value.setScale(2,RoundingMode.HALF_UP); }
    public static Object jsonDecimals(Object value){
        if(value instanceof BigDecimal decimal)return decimal.toPlainString();
        if(value instanceof Map<?,?> map){var result=new LinkedHashMap<String,Object>();map.forEach((key,item)->result.put(key.toString(),jsonDecimals(item)));return result;}
        if(value instanceof List<?> list)return list.stream().map(Db::jsonDecimals).toList();
        return value;
    }
    public static long id(Object value) { if(value==null)throw ApiException.bad("缺少记录标识");return Long.parseLong(value.toString()); }
    public static boolean yes(Object v) { return Boolean.TRUE.equals(v)||"1".equals(String.valueOf(v))||"true".equalsIgnoreCase(String.valueOf(v)); }
    public static String text(Map<String,Object> m,String key) {Object v=m.get(key);if(v==null||v.toString().isBlank())throw ApiException.bad("缺少字段："+key);return v.toString();}
    public static BigDecimal positive(Map<String,Object> m,String key,int scale) {BigDecimal d;try{d=decimal(text(m,key));}catch(NumberFormatException ex){throw ApiException.bad("数字格式错误："+key);}if(d.signum()<=0||d.scale()>scale||d.precision()>(scale==4?24:20))throw ApiException.bad("数值必须为正且最多"+scale+"位小数："+key);return d.setScale(scale);}
}

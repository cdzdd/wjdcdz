package com.hengsheng.fund;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ClearingDataSourceConfig {
    private HikariDataSource pool(String name,int size,String url,String username,String password){
        var config=new HikariConfig();config.setPoolName(name);config.setJdbcUrl(url);config.setUsername(username);config.setPassword(password);
        config.setMaximumPoolSize(size);config.setMinimumIdle(0);config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
    }
    @Bean(destroyMethod="close")
    HikariDataSource realtimeDataSource(@Value("${spring.datasource.url}") String url,@Value("${spring.datasource.username}") String user,@Value("${spring.datasource.password}") String password){return pool("realtime",8,url,user,password);}
    @Bean(destroyMethod="close")
    HikariDataSource batchDataSource(@Value("${spring.datasource.url}") String url,@Value("${spring.datasource.username}") String user,@Value("${spring.datasource.password}") String password){return pool("clearing",2,url,user,password);}
    @Bean(name={"dataSource","routingDataSource"}) @Primary
    DataSource routingDataSource(@Qualifier("realtimeDataSource") DataSource realtime,@Qualifier("batchDataSource") DataSource batch){
        var routing=new AbstractRoutingDataSource(){@Override protected Object determineCurrentLookupKey(){return ClearingExecutionContext.batch()?"BATCH":"REALTIME";}};
        routing.setTargetDataSources(Map.of("REALTIME",realtime,"BATCH",batch));routing.setDefaultTargetDataSource(realtime);routing.setLenientFallback(false);return routing;
    }
    @Bean
    PlatformTransactionManager transactionManager(@Qualifier("routingDataSource") DataSource dataSource){return new JdbcTransactionManager(dataSource);}
}

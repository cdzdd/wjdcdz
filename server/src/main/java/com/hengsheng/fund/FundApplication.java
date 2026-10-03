package com.hengsheng.fund;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FundApplication {
    public static void main(String[] args) { SpringApplication.run(FundApplication.class, args); }
}

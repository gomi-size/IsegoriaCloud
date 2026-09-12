package com.ruwei.social;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan("com.ruwei")
@EnableDubbo
@MapperScan("com.ruwei.social.mapper")
public class IsegoriaSocialApplication {

    public static void main(String[] args) {
        SpringApplication.run(IsegoriaSocialApplication.class, args);
    }

}

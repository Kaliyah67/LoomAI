package com.lsq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 服务启动类
@SpringBootApplication
@Slf4j
public class AIWearApplication {
    public static void main(String[] args) {
        log.info("loomai项目启动成功！");
        SpringApplication.run(AIWearApplication.class, args);
    }
}

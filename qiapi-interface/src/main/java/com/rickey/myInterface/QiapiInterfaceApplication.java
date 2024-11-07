package com.rickey.myInterface;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * QiApi 模拟接口入口类
 */
@SpringBootApplication
@MapperScan("com.rickey.myInterface.mapper")
public class QiapiInterfaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(QiapiInterfaceApplication.class, args);
    }

}
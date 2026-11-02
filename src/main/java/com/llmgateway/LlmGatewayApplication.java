package com.llmgateway;

import com.llmgateway.config.GatewayProperties;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 进程入口。只把标了 {@link Mapper} 的接口交给 MyBatis。
 * 同包里的负载均衡、限流等扩展接口不能被扫成 Mapper，否则会和真正的实现抢注入。
 * 不启用会改表的数据库迁移，表结构以已经建好的库为准。
 */
@SpringBootApplication
@MapperScan(basePackages = {"com.llmgateway.routing", "com.llmgateway.task", "com.llmgateway.admin"},
        annotationClass = Mapper.class)
@EnableConfigurationProperties(GatewayProperties.class)
public class LlmGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmGatewayApplication.class, args);
    }
}

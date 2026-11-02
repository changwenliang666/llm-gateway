package com.llmgateway.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * 按运维给出的 MYSQL_SERVER、REDIS_SERVER 建立连接。
 * 测试可以把这两个变量指到临时库，业务代码不用改。
 * 密码为空时不向 Redis 发送 AUTH，方便无密码的本地 Redis。
 */
@Configuration
public class InfrastructureConfig {

    static final String DEFAULT_MYSQL_SERVER = "140.143.144.152:3306";
    static final String DEFAULT_REDIS_SERVER = "140.143.144.152";

    @Bean
    public DataSource dataSource(Environment env) {
        HostPort server = HostPort.parse(env.getProperty("MYSQL_SERVER", DEFAULT_MYSQL_SERVER), 3306);
        String database = env.getProperty("MYSQL_DATABASE", "llm-gateway");
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("llm-gateway");
        dataSource.setJdbcUrl("jdbc:mysql://" + server.host() + ":" + server.port() + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true");
        dataSource.setUsername(env.getProperty("MYSQL_USER", "root"));
        dataSource.setPassword(env.getProperty("MYSQL_PASSWORD", ""));
        dataSource.setMaximumPoolSize(10);
        dataSource.setConnectionTimeout(5_000L);
        return dataSource;
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(Environment env) {
        HostPort server = HostPort.parse(env.getProperty("REDIS_SERVER", DEFAULT_REDIS_SERVER), 6379);
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(server.host(), server.port());
        String username = env.getProperty("REDIS_USERNAME", "");
        if (StringUtils.hasText(username)) {
            standalone.setUsername(username);
        }
        String password = env.getProperty("REDIS_PASSWORD", "");
        if (StringUtils.hasText(password)) {
            standalone.setPassword(password);
        }
        return new LettuceConnectionFactory(standalone);
    }
}

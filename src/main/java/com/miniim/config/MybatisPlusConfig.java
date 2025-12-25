package com.miniim.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.miniim.**.mapper")
public class MybatisPlusConfig {
}

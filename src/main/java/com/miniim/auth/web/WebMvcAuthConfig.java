package com.miniim.auth.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcAuthConfig implements WebMvcConfigurer {

    private final AccessTokenInterceptor accessTokenInterceptor;

    public WebMvcAuthConfig(AccessTokenInterceptor accessTokenInterceptor) {
        this.accessTokenInterceptor = accessTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessTokenInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/**");
    }
}

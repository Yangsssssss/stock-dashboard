package com.quantboard.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/** 静态资源：直接服务项目根目录的 web/（前端文件即时生效，无需重新打包）。 */
@Configuration
public class StaticConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String webDir = Paths.get(System.getProperty("user.dir"), "web").toUri().toString();
        registry.addResourceHandler("/**")
                .addResourceLocations(webDir)
                .setCacheControl(org.springframework.http.CacheControl.noCache());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}

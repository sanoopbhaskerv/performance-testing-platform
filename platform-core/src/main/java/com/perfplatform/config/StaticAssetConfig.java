package com.perfplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the /data/runs directory as static assets.
 * This allows the browser to view HTML reports, MAT analysis, and thread dumps
 * directly.
 */
@Configuration
public class StaticAssetConfig implements WebMvcConfigurer {

    @Value("${platform.work-dir:/tmp/perf-platform}")
    private String workDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve /runs/** from the workDir (e.g. /data/runs)
        registry.addResourceHandler("/runs/**")
                .addResourceLocations("file:" + workDir + "/");
    }
}

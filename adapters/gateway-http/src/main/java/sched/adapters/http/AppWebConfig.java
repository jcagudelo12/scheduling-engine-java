package sched.adapters.http;

import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Entrada del cliente web de demostración: los archivos estáticos viven en
 * {@code classpath:/static/app/} (Spring Boot los sirve bajo {@code /app/**}); aquí solo se
 * resuelve {@code /app} y {@code /app/} hacia {@code index.html}.
 */
public final class AppWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/app", "/app/");
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
    }
}

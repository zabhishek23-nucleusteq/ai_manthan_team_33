package com.expenseflow.mockbank.fault;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Applies the configured faults to every /bank/** request (never to /admin or /actuator). */
@Configuration
public class FaultInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final FaultInjector injector;

    public FaultInterceptor(FaultInjector injector) {
        this.injector = injector;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        injector.apply();
        return true;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/bank/**");
    }
}

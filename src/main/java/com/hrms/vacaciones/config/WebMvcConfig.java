package com.hrms.vacaciones2.config;

import com.hrms.vacaciones.interceptor.SecurityInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final SecurityInterceptor securityInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(securityInterceptor)
                // Le metemos la lupa a todas las pantallas operativas del portal
                .addPathPatterns("/pantallas/**", "/api/**")

                // 🚀 EXCLUSIONES LIBRES: Rutas que no necesitan bloqueo de sesión
                .excludePathPatterns(
                        "/login",               // Formulario de login
                        "/logout",              // Endpoint de cierre
                        "/css/**",              // Estilos de la planta
                        "/js/**",               // Scripts reactivos
                        "/images/**",           // Logos corporativos
                        "/favicon.ico"          // Icono del navegador
                );
    }
}
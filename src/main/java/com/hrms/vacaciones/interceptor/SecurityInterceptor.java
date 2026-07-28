package com.hrms.vacaciones.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class SecurityInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        String uri = request.getRequestURI();

        // 1. Candado Base: Si no hay sesión activa, directito al Login
        if (session == null || session.getAttribute("usuarioLogueado") == null) {
            log.warn("🚨 Intento de acceso anónimo bloqueado en: {}. Redirigiendo a /login", uri);
            response.sendRedirect(request.getContextPath() + "/login");
            return false;
        }

        // Recuperamos los datos limpios de la sesión del servidor
        String nomina = (String) session.getAttribute("usuarioLogueado");
        String rol = (String) session.getAttribute("rolUsuario");

        // Sanitizamos el rol para evitar fallas por minúsculas o espacios de la BD
        String rolUpper = (rol != null) ? rol.trim().toUpperCase() : "OPERADOR";

        // Definición de banderas de jerarquía Grammer
        boolean esNominas = "NOMINAS".equals(rolUpper) || "RH".equals(rolUpper) || "ADMIN".equals(rolUpper)
                || java.util.List.of("1555", "1302", "1550").contains(nomina);

        boolean esJefatura = "SHIFT".equals(rolUpper) || "SHIFT LEADER".equals(rolUpper)
                || "SUPERVISOR".equals(rolUpper) || "GERENTE".equals(rolUpper);

        // 2. Validación Quirúrgica por Pantalla (Matriz de Accesos del Chato)

        // Caso A: Pantallas exclusivas de Nóminas / RH
        if (uri.contains("gestion-saldos") || uri.contains("torre-control")) {
            if (!esNominas) {
                log.error("🛑 Violación de Seguridad: Nómina {} ({}) intentó forzar entrada a {}", nomina, rolUpper, uri);
                response.sendRedirect(request.getContextPath() + "/pantallas/dashboard?error=sin_permiso");
                return false;
            }
        }

        // Caso B: Pantallas de Autorización de Jefes (Shift, Supervisores y Gerentes)
        // Nota: Se le permite el acceso a Nóminas/Admin también por si necesitan auditar bandejas
        if (uri.contains("aprobaciones-jefe") || uri.contains("aprobaciones-permisos")) {
            if (!esJefatura && !esNominas) {
                log.error("🛑 Violación de Seguridad: Nómina {} ({}) intentó firmar aprobaciones en {}", nomina, rolUpper, uri);
                response.sendRedirect(request.getContextPath() + "/pantallas/dashboard?error=sin_permiso");
                return false;
            }
        }

        // Caso C: Dashboard y Solicitar (Acceso universal para todos los registros vivos)
        // Ya pasó el filtro de estar logueado, así que se le concede el paso libre
        return true;
    }
}
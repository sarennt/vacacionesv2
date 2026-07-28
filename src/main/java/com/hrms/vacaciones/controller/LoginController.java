package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.repository.EmpleadoRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

@Slf4j
@Controller
@RequiredArgsConstructor
public class LoginController {

    private final EmpleadoRepository empleadoRepository;

    @GetMapping("/login")
    public String mostrarLogin(HttpSession session) {
        // Si ya tiene sesión activa, lo mandamos directo a su Dashboard
        if (session.getAttribute("usuarioLogueado") != null) {
            return "redirect:/pantallas/dashboard";
        }
        return "login";
    }

    @PostMapping("/login")
    public String procesarLogin(@RequestParam("nomina") String nominaStr,
                                @RequestParam("tag") String tag,
                                HttpSession session,
                                Model model) {
        try {
            // 1️⃣ Candado de seguridad visual: Validamos que la nómina sea un número real
            Integer nomina;
            try {
                nomina = Integer.parseInt(nominaStr.trim());
            } catch (NumberFormatException e) {
                model.addAttribute("error", "El número de nómina debe contener únicamente números.");
                return "login";
            }

            // 2️⃣ Buscamos al empleado usando el Integer numérico puro que espera el Repositorio
            Optional<Empleado> empleadoOpt = empleadoRepository.findById(nomina);

            if (empleadoOpt.isEmpty()) {
                model.addAttribute("error", "El número de nómina no está registrado.");
                return "login";
            }

            Empleado empleado = empleadoOpt.get();

            // 3️⃣ Validación de seguridad contra el TAG (Contraseña)
            if (!empleado.getTag().equals(tag.trim())) {
                model.addAttribute("error", "Credenciales incorrectas. Verifica tu TAG.");
                return "login";
            }

            // 4️⃣ Candado Anti-Intrusos: Si es baja legal, ¡CUELLO!
            if ("BAJA".equalsIgnoreCase(empleado.getEstatus())) {
                model.addAttribute("error", "Acceso denegado. Perfil de empleado inactivo.");
                return "login";
            }

            // 5️⃣ ¡ÉXITO! Guardamos las variables clave en la Sesión del Servidor
            session.setAttribute("usuarioLogueado", empleado.getNomina().toString());

// ✨ CAMBIA ESTA LÍNEA REEMPLAZANDO LOS MÉTODOS EN INGLÉS POR TU FUNCIÓN MAESTRA:
            session.setAttribute("nombreUsuario", empleado.getNombres());

            session.setAttribute("rolUsuario", empleado.getRolJerarquico());
            session.setAttribute("tipoEmpleado", empleado.getTipoEmpleado());

            log.info("🔓 Sesión iniciada con éxito: Nómina {} ({})", empleado.getNomina(), empleado.getRolJerarquico());

            // Redirección inteligente de entrada al Dashboard
            return "redirect:/pantallas/dashboard";

        } catch (Exception e) {
            log.error("Error crítico en el login: ", e);
            model.addAttribute("error", "Ocurrió un error en el servidor. Intenta de nuevo.");
            return "login";
        }
    }

    @GetMapping("/logout")
    public String cerrarSesion(HttpSession session) {
        log.info("🔒 Sesión cerrada para la nómina: {}", session.getAttribute("usuarioLogueado"));
        session.invalidate(); // Destruye por completo la sesión
        return "redirect:/login?logout=exito";
    }
}
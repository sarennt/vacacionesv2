package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @Autowired
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            Empleado empleado = authService.login(request.nomina(), request.tag());

            Map<String, Object> response = new HashMap<>();
            response.put("mensaje", "Bienvenido");
            response.put("nombres", empleado.getNombres());
            response.put("apellidoPaterno", empleado.getApellidoPaterno());
            response.put("apellidoMaterno", empleado.getApellidoMaterno());
            response.put("nombreCompleto", empleado.getNombreCompleto());
            response.put("rol", empleado.getRolJerarquico());
            response.put("nomina", empleado.getNomina());
            // Agrega más campos si consideras que son seguros e importantes en el Frontend

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
        }
    }

    public record LoginRequest(Integer nomina, String tag) {
    }
}

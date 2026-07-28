package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.dto.AjusteSaldoRequest;
import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.service.EmpleadoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rh")
@CrossOrigin(origins = "*")
public class RhController {

    @Autowired
    private EmpleadoService empleadoService;

    @PostMapping("/ajuste-saldo")
    public ResponseEntity<?> ajustarSaldoManual(@RequestBody AjusteSaldoRequest request) {
        try {
            Empleado empleadoActualizado = empleadoService.ajustarSaldoManual(request);
            return ResponseEntity.ok(empleadoActualizado);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error al procesar el ajuste de saldo: " + e.getMessage());
        }
    }
}

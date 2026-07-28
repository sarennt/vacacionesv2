package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.dto.SolicitudVacacionesRequest;
import com.hrms.vacaciones.dto.RespuestaJefeRequest;
import com.hrms.vacaciones.model.SolicitudVacaciones;
import com.hrms.vacaciones.service.VacacionesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/vacaciones")
public class VacacionesController {

    private final VacacionesService vacacionesService;

    @Autowired
    public VacacionesController(VacacionesService vacacionesService) {
        this.vacacionesService = vacacionesService;
    }

    @PostMapping("/solicitar")
    public ResponseEntity<?> solicitar(@RequestBody SolicitudVacacionesRequest request) {
        try {
            SolicitudVacaciones solicitud = vacacionesService.crearSolicitud(request);

            Map<String, Object> response = new HashMap<>();
            response.put("mensaje", "Solicitud creada exitosamente");
            response.put("solicitudId", solicitud.getId());

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @GetMapping("/tablero/{nomina}")
    public ResponseEntity<?> obtenerTablero(@PathVariable Integer nomina) {
        try {
            com.hrms.vacaciones.dto.TableroEmpleadoResponse response = vacacionesService.obtenerTablero(nomina);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @GetMapping("/pendientes/jefe/{nomina}")
    public ResponseEntity<?> obtenerPendientesJefe(@PathVariable("nomina") Integer nomina) {
        try {
            java.util.List<SolicitudVacaciones> pendientes = vacacionesService.obtenerPendientesPorJefe(nomina);
            return ResponseEntity.ok(pendientes);
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @PostMapping("/responder/jefe")
    public ResponseEntity<?> responderJefe(@RequestBody RespuestaJefeRequest request) {
        try {
            vacacionesService.procesarRespuestaJefe(request);

            Map<String, String> response = new HashMap<>();
            response.put("mensaje", "La solicitud ha sido procesada exitosamente.");
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @PutMapping("/aprobar/jefe/{idSolicitud}/{nominaJefe}")
    public ResponseEntity<?> aprobarPorJefe(
            @PathVariable("idSolicitud") Integer idSolicitud,
            @PathVariable("nominaJefe") Integer nominaJefe) {
        try {
            SolicitudVacaciones solicitud = vacacionesService.aprobarPorJefe(idSolicitud, nominaJefe);

            Map<String, Object> response = new HashMap<>();
            response.put("mensaje", "Solicitud aprobada por el jefe exitosamente");
            response.put("solicitudId", solicitud.getId());
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }
}

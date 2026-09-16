package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.dto.SolicitudVacacionesRequest;
import com.hrms.vacaciones.dto.RespuestaJefeRequest;
import com.hrms.vacaciones.model.SolicitudVacaciones;
import com.hrms.vacaciones.service.VacacionesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
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

    // ✨ EL NUEVO ENDPOINT PARA EL CALENDARIO CINÉPOLIS
    @GetMapping("/dias-bloqueados")
    public ResponseEntity<List<String>> obtenerDiasBloqueados(
            @RequestParam("nominaEmpleado") Integer nominaEmpleado,
            @RequestParam("turnoId") Integer turnoId) {
        try {
            List<String> bloqueados = vacacionesService.obtenerDiasBloqueadosCinepolis(nominaEmpleado, turnoId);
            return ResponseEntity.ok(bloqueados);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Collections.emptyList());
        }
    }

    // =========================================================================
    // 🎟️ API CINÉPOLIS: CARTELERA DE DISPONIBILIDAD POR DÍA (MODAL)
    // =========================================================================
    @GetMapping("/disponibilidad-fecha")
    public ResponseEntity<?> consultarDisponibilidadFecha(
            @RequestParam("nominaEmpleado") Integer nominaEmpleado,
            @RequestParam("fecha") String fechaStr) {
        try {
            java.time.LocalDate fecha = java.time.LocalDate.parse(fechaStr);
            java.util.Map<String, Object> reporte = vacacionesService.obtenerCarteleraPorFecha(nominaEmpleado, fecha);
            return ResponseEntity.ok(reporte);
        } catch (Exception e) {
            java.util.Map<String, String> errorResponse = new java.util.HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    // =========================================================================
    // 🎟️ API CINÉPOLIS: CARTELERA DE DISPONIBILIDAD POR RANGO (MODAL)
    // =========================================================================
    @GetMapping("/disponibilidad-rango")
    public ResponseEntity<?> consultarDisponibilidadRango(
            @RequestParam("nominaEmpleado") Integer nominaEmpleado,
            @RequestParam("fechaInicio") String fechaInicioStr,
            @RequestParam("fechaFin") String fechaFinStr,
            @RequestParam("turnoId") Integer turnoId) { // ✨ NUEVO PARÁMETRO
        try {
            java.time.LocalDate fechaInicio = java.time.LocalDate.parse(fechaInicioStr);
            java.time.LocalDate fechaFin = java.time.LocalDate.parse(fechaFinStr);

            if (java.time.temporal.ChronoUnit.DAYS.between(fechaInicio, fechaFin) > 31) {
                throw new RuntimeException("El rango de inspección no puede exceder los 31 días. Por favor, acorte las fechas.");
            }
            if (fechaFin.isBefore(fechaInicio)) {
                throw new RuntimeException("La fecha de fin no puede ser anterior a la de inicio.");
            }

            // ✨ Le pasamos el turnoId al servicio
            List<java.util.Map<String, Object>> reporte = vacacionesService.obtenerCarteleraPorRango(nominaEmpleado, fechaInicio, fechaFin, turnoId);
            return ResponseEntity.ok(reporte);

        } catch (Exception e) {
            java.util.Map<String, String> errorResponse = new java.util.HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }
    // =========================================================================
    // 📡 API RADAR: ESCANEO DE OCUPACIÓN MENSUAL (GLOBAL POR GRUPO)
    // =========================================================================
    @GetMapping("/radar-ocupacion")
    public ResponseEntity<?> obtenerRadarOcupacion(
            @RequestParam("grupoId") Integer grupoId,
            @RequestParam("fechaInicio") String fechaInicioStr,
            @RequestParam("fechaFin") String fechaFinStr) {
        try {
            java.time.LocalDate fechaInicio = java.time.LocalDate.parse(fechaInicioStr);
            java.time.LocalDate fechaFin = java.time.LocalDate.parse(fechaFinStr);

            List<Map<String, Object>> reporteRadar = vacacionesService.escanearRadarPorGrupo(grupoId, fechaInicio, fechaFin);
            return ResponseEntity.ok(reporteRadar);
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    // =========================================================================
    // 📡 API RADAR: DETALLE ESTADÍSTICO POR TURNOS (MODAL)
    // =========================================================================
    @GetMapping("/radar-detalle-dia")
    public ResponseEntity<?> obtenerDetalleRadarDia(
            @RequestParam("grupoId") Integer grupoId,
            @RequestParam("fecha") String fechaStr) {
        try {
            java.time.LocalDate fecha = java.time.LocalDate.parse(fechaStr);
            List<Map<String, Object>> detalle = vacacionesService.obtenerDetalleRadarPorTurnos(grupoId, fecha);
            return ResponseEntity.ok(detalle);
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }
}
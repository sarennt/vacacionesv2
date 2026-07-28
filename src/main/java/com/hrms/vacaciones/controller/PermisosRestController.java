package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.dto.SolicitudPermisoRangoRequestDTO;
import com.hrms.vacaciones.dto.SolicitudPermisoRequestDTO;
import com.hrms.vacaciones.dto.SolicitudVacacionesRequest;
import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.model.TipoPermiso;
import com.hrms.vacaciones.repository.EmpleadoRepository;
import com.hrms.vacaciones.repository.SolicitudPermisoRepository;
import com.hrms.vacaciones.service.PermisosService;
import com.hrms.vacaciones.service.VacacionesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/permisos")
public class PermisosRestController {

    @Autowired
    private PermisosService permisosService;

    @Autowired
    private VacacionesService vacacionesService;

    @Autowired
    private SolicitudPermisoRepository permisoRepo;

    @Autowired
    private EmpleadoRepository empleadoRepo;

    @GetMapping("/tipos-disponibles")
    public ResponseEntity<List<TipoPermiso>> obtenerTiposDisponibles(@RequestParam("nomina") Integer nomina) {
        try {
            // 🎯 CORRECCIÓN AQUÍ: Cambiado 'types' por 'tipos' para matar el error de inferencia
            List<TipoPermiso> tipos = permisosService.obtenerPermisosParaEmpleado(nomina);
            return ResponseEntity.ok(tipos);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(null);
        }
    }

    @PostMapping("/solicitar")
    public ResponseEntity<Map<String, Object>> procesarSolicitudPermiso(@RequestBody SolicitudPermisoRequestDTO request) {
        Map<String, Object> respuesta = new HashMap<>();
        try {
            permisosService.registrarSolicitudPermiso(request);
            respuesta.put("status", "SUCCESS");
            respuesta.put("message", "Tu solicitud de incidencia ha sido registrada correctamente en el sistema.");
            return ResponseEntity.ok(respuesta);
        } catch (Exception e) {
            respuesta.put("status", "ERROR");
            respuesta.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(respuesta);
        }
    }

    @GetMapping("/colectivo/cargar-operadores")
    public ResponseEntity<?> cargarOperadoresColectivo(
            @RequestParam("workCenterIds") List<Integer> wcIds,
            @RequestParam("fechaParo") String fechaParoStr) {

        LocalDate fechaParo = LocalDate.parse(fechaParoStr);
        String mensajeAdvertencia = null;

        // 🎯 FIX: Ya no regresamos BadRequest, solo armamos un mensaje de advertencia
        // si detectamos que alguna línea ya trae un paro para ese mismo día.
        for (Integer wcId : wcIds) {
            if (permisoRepo.existeRegistroColectivoPorLinea(wcId, fechaParo)) {
                mensajeAdvertencia = "Aviso Contable: La línea WC " + wcId + " ya tiene un registro de paro reportado para el día " + fechaParoStr + ". Evita duplicidades.";
                break; // Con un aviso basta para alertar al jefe
            }
        }

        List<Empleado> operadores = empleadoRepo.findByWorkCenterIdInAndEstatus(wcIds, "ACTIVO");

        List<Map<String, Object>> listaOperadores = operadores.stream().map(op -> {
            Map<String, Object> map = new HashMap<>();
            map.put("nomina", op.getNomina());
            map.put("nombre", op.getNombreCompleto());
            map.put("turnoDefault", "1ro");
            return map;
        }).collect(Collectors.toList());

        // Empaquetamos tanto la lista de personas como el posible aviso en un mapa
        Map<String, Object> respuestaFinal = new HashMap<>();
        respuestaFinal.put("operadores", listaOperadores);
        if (mensajeAdvertencia != null) {
            respuestaFinal.put("advertencia", mensajeAdvertencia);
        }

        return ResponseEntity.ok(respuestaFinal);
    }

    @PostMapping("/colectivo/guardar-lote")
    public ResponseEntity<?> guardarLotePermisosColectivos(@RequestBody List<SolicitudPermisoRequestDTO> solicitudes) {
        try {
            for (SolicitudPermisoRequestDTO dto : solicitudes) {
                permisosService.registrarSolicitudPermisoDirectoAprobado(dto);
            }
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "¡Lote aplicado con éxito! Se inyectaron los registros autorizados directamente a nómina."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @PostMapping("/solicitar-rango")
    public ResponseEntity<?> solicitarPermisoRango(
            @RequestParam("empleadoNomina") Integer empleadoNomina,
            @RequestParam("codigoPermiso") String codigoPermiso,
            @RequestParam("fechaInicio") String fechaInicioStr,
            @RequestParam("fechaFin") String fechaFinStr,
            @RequestParam("rolDescansoId") Integer rolDescansoId,
            @RequestParam(value = "comentarios", required = false) String comentarios,
            @RequestParam(value = "jefeAutorizadorNomina", required = false) Integer jefeAutorizadorNomina,
            @RequestParam(value = "comprobante", required = false) MultipartFile comprobante
    ) {
        Map<String, Object> respuesta = new HashMap<>();
        try {
            LocalDate fechaI = LocalDate.parse(fechaInicioStr);
            LocalDate fechaF = LocalDate.parse(fechaFinStr);

            SolicitudVacacionesRequest requestAdaptado = new SolicitudVacacionesRequest(
                    empleadoNomina,
                    fechaI,
                    fechaF,
                    0,
                    codigoPermiso,
                    rolDescansoId,
                    jefeAutorizadorNomina,
                    comentarios,
                    false
            );

            vacacionesService.crearSolicitud(requestAdaptado);

            respuesta.put("status", "SUCCESS");
            respuesta.put("message", "¡Registro Exitoso! Tu solicitud de " + codigoPermiso + " fue enviada correctamente.");
            return ResponseEntity.ok(respuesta);

        } catch (Exception e) {
            respuesta.put("status", "ERROR");
            respuesta.put("message", "Error contable al registrar rango: " + e.getMessage());
            return ResponseEntity.ok(respuesta);
        }
    }
}
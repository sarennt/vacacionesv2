package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.model.CatRolDescanso;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.hrms.vacaciones.dto.JefeDisponibleDTO;
import com.hrms.vacaciones.service.EmpleadoService;
import com.hrms.vacaciones.model.Turno;             // ✨ Importación Oficial Viva
import com.hrms.vacaciones.repository.TurnoRepository;
import java.util.List;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/catalogos")
public class CatalogoController {

    private final TurnoRepository turnoRepository;
    private final EmpleadoService empleadoService;

    @Autowired // Tu inyección por constructor limpia y directa
    public CatalogoController(TurnoRepository turnoRepository, EmpleadoService empleadoService) {
        this.turnoRepository = turnoRepository;
        this.empleadoService = empleadoService;
    }

    @GetMapping("/api/turnos") // Pon la ruta exacta que tú tengas aquí
    public ResponseEntity<List<Turno>> obtenerTurnos() {

        // Consultamos el catálogo maestro real de producción
        List<Turno> turnos = turnoRepository.findAll();

        // ✨ Ahora sí compilará impecable y en verde total
        return ResponseEntity.ok(turnos);
    }

    @GetMapping("/jefes-disponibles")
    public ResponseEntity<List<JefeDisponibleDTO>> getJefesDisponibles() {
        List<JefeDisponibleDTO> jefes = empleadoService.obtenerJefesDisponibles();
        return ResponseEntity.ok(jefes);
    }
}

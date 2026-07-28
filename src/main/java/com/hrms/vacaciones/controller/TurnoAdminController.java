package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.model.Turno;
import com.hrms.vacaciones.repository.TurnoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/turnos") // ✨ Ajustado al nuevo dominio único de cat_turnos
@CrossOrigin(origins = "*")
public class TurnoAdminController {

    private final TurnoRepository turnoRepository;

    @Autowired
    public TurnoAdminController(TurnoRepository turnoRepository) {
        this.turnoRepository = turnoRepository;
    }

    /**
     * Devuelve el catálogo completo de turnos vigentes para popular las tablas de la Torre de Control.
     */
    @GetMapping
    public ResponseEntity<List<Turno>> listarTodos() {
        return ResponseEntity.ok(turnoRepository.findAll());
    }

    /**
     * Registra un nuevo turno operativo en la base de datos aplicando los fallbacks reglamentarios.
     */
    @PostMapping
    public ResponseEntity<Turno> crearTurno(@RequestBody Turno turno) {
        turno.setId(null); // Garantiza que sea una inserción pura

        if (turno.getActivo() == null) {
            turno.setActivo(true);
        }
        if (turno.getEsPorDefecto() == null) {
            turno.setEsPorDefecto(false);
        }
        if (turno.getDiasDescanso() == null || turno.getDiasDescanso().isBlank()) {
            turno.setDiasDescanso("DOMINGO"); // Escudo por defecto de la planta
        }
        if (turno.getPeso() == null) {
            turno.setPeso(3); // Ponderación estándar intermedia del Plan Maestro
        }

        Turno guardado = turnoRepository.save(turno);
        return ResponseEntity.ok(guardado);
    }

    /**
     * Sincroniza en caliente las modificaciones de jornadas u horas sin necesidad de reiniciar la app.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> actualizarTurno(@PathVariable Integer id, @RequestBody Turno turnoDetalles) {
        return turnoRepository.findById(id)
                .map(turnoExistente -> {
                    // 🧠 Alineación milimétrica con los nuevos campos de Turno.java
                    turnoExistente.setNombreTurno(turnoDetalles.getNombreTurno());
                    turnoExistente.setHorasJornada(turnoDetalles.getHorasJornada());
                    turnoExistente.setMundo(turnoDetalles.getMundo());
                    turnoExistente.setDiasDescanso(turnoDetalles.getDiasDescanso());

                    if (turnoDetalles.getActivo() != null) {
                        turnoExistente.setActivo(turnoDetalles.getActivo());
                    }
                    if (turnoDetalles.getEsPorDefecto() != null) {
                        turnoExistente.setEsPorDefecto(turnoDetalles.getEsPorDefecto());
                    }
                    if (turnoDetalles.getPeso() != null) {
                        turnoExistente.setPeso(turnoDetalles.getPeso());
                    }

                    Turno actualizado = turnoRepository.save(turnoExistente);
                    return ResponseEntity.ok(actualizado);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 🔒 CANDADO DEL PLAN MAESTRO: Queda estrictamente prohibido usar DELETE físico.
     * Si un turno se vuelve obsoleto, se apaga lógicamente cambiando activo a false
     * para salvaguardar el historial contable de las solicitudes del pasado.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminarTurno(@PathVariable Integer id) {
        return turnoRepository.findById(id)
                .map(turno -> {
                    turno.setActivo(false); // Apagado lógico de seguridad de piso
                    turnoRepository.save(turno);
                    return ResponseEntity.ok("Turno inhabilitado lógicamente con éxito. Historial relacional a salvo.");
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
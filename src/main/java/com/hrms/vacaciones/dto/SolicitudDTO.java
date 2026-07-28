package com.hrms.vacaciones.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record SolicitudDTO(
        Integer id,
        LocalDate fechaInicio,
        LocalDate fechaFin,
        Integer diasTotalesCalculados,
        String estatus,
        String comentarioJefe,
        LocalDateTime fechaCreacion,
        String jefeAutorizadorNombre,

        // --- LOS CAMPOS PARA EL SEMÁFORO VISUAL ---
        String tipoSolicitud,
        String comentarioSupervisor,
        String comentarioExcepcion,

        // ✨ NUEVO: El hilo conductor para fusionar tarjetas parciales en el Dashboard
        String grupoFolio
) {}
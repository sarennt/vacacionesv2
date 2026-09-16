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
        String tipoSolicitud,
        String comentarioSupervisor,
        String comentarioExcepcion,
        String grupoFolio,
        String turno,
        Boolean esPorHoras,
        java.math.BigDecimal horasPermiso,
        java.util.List<String> detallePagos
) {}
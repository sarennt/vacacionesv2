package com.hrms.vacaciones.dto;

import java.time.LocalDate;
import jakarta.validation.constraints.NotNull;

public record SolicitudVacacionesRequest(
        Integer nominaEmpleado,
        LocalDate fechaInicio,
        LocalDate fechaFin,
        Integer diasSolicitados,
        String tipoSolicitud,

        @NotNull(message = "El rol de descanso es estrictamente obligatorio.")
        Integer rolDescansoId,

        // --- NUEVOS CAMPOS AÑADIDOS PARA EMPATAR CON LA PANTALLA ---
        Integer jefeAutorizadorNomina,
        String comentarios,
        Boolean esExtemporanea
) {
}

package com.hrms.vacaciones.dto;

import java.math.BigDecimal;
import java.util.List;

public record TableroEmpleadoResponse(
        Integer nomina,
        String nombreCompleto,
        BigDecimal saldoActual,
        List<SolicitudDTO> historial
) {}



package com.hrms.vacaciones.dto;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RangoSemanaVip {
    private LocalDate fechaInicio; // Lunes
    private LocalDate fechaFin;    // Viernes
}
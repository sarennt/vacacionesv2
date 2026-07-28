package com.hrms.vacaciones.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FilaPagoTxtDTO {
    private LocalDate fechaPago; // Cuándo repondrá el tiempo (No puede ser del pasado cerrado)
    private BigDecimal horasPago; // Cuántas horas va a meter ese día
}
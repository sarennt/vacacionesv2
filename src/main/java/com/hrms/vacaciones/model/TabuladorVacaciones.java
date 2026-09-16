package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "tabulador_vacaciones")
public class TabuladorVacaciones {

    @Id
    @Column(name = "anio_antiguedad")
    private Integer anioAntiguedad;

    @Column(name = "dias_ley", nullable = false)
    private Integer diasLey;
}
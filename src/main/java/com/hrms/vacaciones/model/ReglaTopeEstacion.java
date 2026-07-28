package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cat_reglas_tope_estacion")
public class ReglaTopeEstacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "min_operadores", nullable = false)
    private Integer minOperadores;

    @Column(name = "max_operadores", nullable = false)
    private Integer maxOperadores;

    @Column(name = "max_ausentes_por_dia", nullable = false)
    private Integer maxAusentesPorDia;
}
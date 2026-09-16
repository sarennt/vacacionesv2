package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "cat_rangos_turno")
public class RangoTurno {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "nombre_turno", nullable = false, length = 100)
    private String nombreTurno; // Se vincula al nombre de cat_turnos (ej. "1RO", "3RO")

    @Column(name = "entrada_desde", nullable = false)
    private LocalTime entradaDesde;

    @Column(name = "entrada_hasta", nullable = false)
    private LocalTime entradaHasta;

    @Column(name = "salida_desde", nullable = false)
    private LocalTime salidaDesde;

    @Column(name = "salida_hasta", nullable = false)
    private LocalTime salidaHasta;
}
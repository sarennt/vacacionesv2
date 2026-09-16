package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "cat_turnos") // Amarrado a tu tabla real con datos
public class Turno {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // ✨ AQUÍ ESTABA EL DETALLE: Faltaba la variable del nombre
    @Column(name = "nombre", nullable = false, unique = true, length = 100)
    private String nombreTurno;

    @Column(name = "horas_jornada", nullable = false, precision = 4, scale = 2)
    private BigDecimal horasJornada;

    @Column(name = "horas_semanales", nullable = false, precision = 5, scale = 2)
    private BigDecimal horasSemanales = BigDecimal.ZERO;

    @Column(nullable = false, length = 15)
    private String mundo; // SIND, ADMIN, TODOS

    @Builder.Default
    @Column(nullable = false)
    private Boolean activo = true;

    @Builder.Default
    @Column(name = "dias_descanso", nullable = false, length = 100)
    private String diasDescanso = "DOMINGO";

}
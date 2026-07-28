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

    @Column(name = "nombre", nullable = false, unique = true, length = 100) // ✨ Tu columna física real en SQL
    private String nombreTurno; // La variable que usa Thymeleaf y el repositorio

    @Column(name = "horas_jornada", nullable = false, precision = 4, scale = 2)
    private BigDecimal horasJornada;

    @Column(nullable = false, length = 15)
    private String mundo; // SIND, ADMIN, TODOS

    @Builder.Default
    @Column(nullable = false)
    private Boolean activo = true; // El switch para prender/apagar

    @Builder.Default
    @Column(name = "dias_descanso", nullable = false, length = 100)
    private String diasDescanso = "DOMINGO"; // Soporta N días separados por comas: "SABADO,DOMINGO" o "VIERNES,SABADO,DOMINGO"

    @Builder.Default
    @Column(name = "es_por_defecto", nullable = false)
    private Boolean esPorDefecto = false;

    @Builder.Default
    @Column(name = "peso", nullable = false)
    private Integer peso = 3; // Ponderación Plan Maestro (1 al 5)
}
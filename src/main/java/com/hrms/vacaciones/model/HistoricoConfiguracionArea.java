package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "historico_configuracion_areas")
public class HistoricoConfiguracionArea {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "mes", nullable = false)
    private Integer mes;

    @Column(name = "anio", nullable = false)
    private Integer anio;

    @Column(name = "fecha_limite_registro", nullable = false)
    private LocalDate fechaLimiteRegistro;

    @Column(name = "fecha_apertura_rezago", nullable = false)
    private LocalDate fechaAperturaRezago;

    @Column(name = "fecha_apertura_general", nullable = false)
    private LocalDate fechaAperturaGeneral;

    // ✨ LOS NUEVOS CAMPOS DE LA FOTOGRAFÍA V2
    @Column(name = "resumen_grupos", length = 500)
    private String resumenGrupos;

    @Column(name = "resumen_bloqueos", length = 500)
    private String resumenBloqueos;

    @Column(name = "accion", nullable = false, length = 20)
    private String accion;

    @Column(name = "realizado_por_nomina", nullable = false)
    private Integer realizadoPorNomina;

    @Column(name = "fecha_registro")
    private LocalDateTime fechaRegistro;
}
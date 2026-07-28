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

    @Column(name = "work_center_id", nullable = false)
    private Integer workCenterId;

    @Column(name = "mes", nullable = false)
    private Integer mes;

    @Column(name = "anio", nullable = false)
    private Integer anio;

    @Column(name = "max_empleados_por_dia", nullable = false)
    private Integer maxEmpleadosPorDia;

    @Column(name = "fecha_limite_registro", nullable = false)
    private LocalDate fechaLimiteRegistro;

    @Column(name = "fecha_apertura_rezago", nullable = false)
    private LocalDate fechaAperturaRezago;

    @Column(name = "fecha_apertura_general", nullable = false)
    private LocalDate fechaAperturaGeneral;

    @Column(name = "dias_minimos_rezago", nullable = false)
    private Integer diasMinimosRezago;

    @Column(name = "accion", nullable = false, length = 20)
    private String accion;

    @Column(name = "realizado_por_nomina", nullable = false)
    private Integer realizadoPorNomina;

    @Column(name = "fecha_registro")
    private LocalDateTime fechaRegistro;
}
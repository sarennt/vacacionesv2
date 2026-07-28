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
@Table(name = "capacidad_vacaciones_mes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_supervisor_turno_periodo", columnNames = {"supervisor_nomina", "turno_id", "mes", "anio"})
        })
public class CapacidadVacacionesMes {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // ✨ EL DUEÑO DE LA BOLSA GLOBAL
    @Column(name = "supervisor_nomina", nullable = false)
    private Integer supervisorNomina;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "turno_id", nullable = false)
    private Turno turno;

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

    @Column(name = "ultima_modificacion_por")
    private Integer ultimaModificacionPor;

    @Column(name = "fecha_ultima_modificacion")
    private LocalDateTime fechaUltimaModificacion;
}
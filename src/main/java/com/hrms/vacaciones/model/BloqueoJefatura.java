package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "bloqueos_jefatura")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BloqueoJefatura {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // Relacionamos directamente con la tabla de empleados usando la nómina
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supervisor_nomina", referencedColumnName = "nomina", nullable = false)
    private Empleado supervisor;

    @Column(name = "fecha_inicio", nullable = false)
    private LocalDate fechaInicio;

    @Column(name = "fecha_fin", nullable = false)
    private LocalDate fechaFin;

    @Column(nullable = false)
    private String motivo;

    // Aquí están los checkboxes tácticos que pidieron
    @Column(name = "aplica_vacaciones", nullable = false)
    private Boolean aplicaVacaciones;

    @Column(name = "aplica_txt", nullable = false)
    private Boolean aplicaTxt;

    @Column(name = "fecha_creacion", insertable = false, updatable = false)
    private LocalDateTime fechaCreacion;
}
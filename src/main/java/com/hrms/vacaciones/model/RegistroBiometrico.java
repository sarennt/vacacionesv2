package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "biometrico_registros")
public class RegistroBiometrico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empleado_nomina", referencedColumnName = "nomina", nullable = false)
    private Empleado empleado;

    @Column(name = "fecha_original", length = 30)
    private String fechaOriginal; // Ej. "Lun 24/08/2026"

    @Column(name = "fecha_limpia", nullable = false)
    private LocalDate fechaLimpia; // Ej. 2026-08-24

    @Column(name = "hora_limpia", nullable = false)
    private LocalTime horaLimpia; // Ya con la macro de redondeo aplicada

    @Column(name = "timestamp_registro", nullable = false)
    private LocalDateTime timestampRegistro; // Fecha y Hora juntas para ordenar cronológicamente

    // Estatus para saber si ya se emparejó o si es un registro "huérfano"
    @Column(length = 30)
    @Builder.Default
    private String estatus = "PROCESADO";

    @Column(name = "hora_original", length = 30)
    private String horaOriginal;
}
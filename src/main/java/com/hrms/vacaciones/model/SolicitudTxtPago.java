package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "solicitud_txt_pagos")
public class SolicitudTxtPago {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "solicitud_permiso_id", nullable = false)
    private SolicitudPermiso solicitudPermiso;

    @Column(name = "fecha_pago", nullable = false)
    private LocalDate fechaPago; // Cuándo repondrá el tiempo

    @Column(name = "horas_pago", nullable = false, precision = 4, scale = 2)
    private BigDecimal horasPago; // Cuántas horas va a meter ese día
}
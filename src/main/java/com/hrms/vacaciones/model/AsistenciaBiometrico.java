package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "biometrico_asistencias")
public class AsistenciaBiometrico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empleado_nomina", referencedColumnName = "nomina", nullable = false)
    private Empleado empleado;

    @Column(name = "fecha_referencia", nullable = false)
    private LocalDate fechaReferencia; // El día al que pertenece este bloque de trabajo

    @OneToOne
    @JoinColumn(name = "entrada_id")
    private RegistroBiometrico entrada;

    @OneToOne
    @JoinColumn(name = "salida_id")
    private RegistroBiometrico salida;

    @Column(name = "turno_asignado", length = 50)
    private String turnoAsignado; // "1RO", "2DO", "3RO", o "ATIPICO"

    // Estatus para identificar rápido si todo cuadró o si faltan piezas
    @Column(length = 30)
    private String estatus; // "COMPLETO", "HUERFANO_SALIDA", "ATIPICO"

    @Column(name = "horas_efectivas")
    private Double horasEfectivas;

    @Column(name = "tiempo_extra")
    private Double tiempoExtra;

    @Column(name = "turno_dominante", length = 50)
    private String turnoDominante; // Se llenará al cierre de la semana
}
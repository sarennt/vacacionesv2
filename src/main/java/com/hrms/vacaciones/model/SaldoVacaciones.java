package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "saldos_vacaciones")
public class SaldoVacaciones {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // Relación ManyToOne: A qué empleado pertenece este saldo anual
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empleado_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Empleado empleado;

    @Column(name = "periodo_anio", nullable = false)
    private Integer periodoAnio;

    @Column(name = "dias_ganados", nullable = false, precision = 6, scale = 2)
    private BigDecimal diasGanados;

    @Column(name = "dias_proporcionales_disponibles", nullable = false, precision = 6, scale = 2)
    private BigDecimal diasProporcionalesDisponibles;

    @Column(name = "dias_disfrutados", nullable = false, precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal diasDisfrutados = BigDecimal.ZERO;

    @Column(name = "fecha_caducidad", nullable = false)
    private LocalDate fechaCaducidad;

    @Column(name = "ultima_actualizacion", nullable = false)
    private LocalDateTime ultimaActualizacion;
}

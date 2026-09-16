package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "periodos_inhabiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeriodoInhabil {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // Amarramos el bloqueo al Jefe/Supervisor.
    // Así el bloqueo solo le pega a los operadores que estén bajo su mando.
    @Column(name = "supervisor_nomina", nullable = false)
    private Integer supervisorNomina;

    // Opcional: Si quisieras ser súper granular, podrías poner un centroCostoId,
    // pero usualmente los inventarios o paros técnicos le pegan a toda el área del supervisor.

    @Column(name = "fecha_inicio", nullable = false)
    private LocalDate fechaInicio;

    @Column(name = "fecha_fin", nullable = false)
    private LocalDate fechaFin;

    @Column(name = "motivo", nullable = false, length = 150)
    private String motivo; // Ej. "Inventario Anual", "Mantenimiento Mayor"

    @Column(name = "fecha_registro")
    private LocalDateTime fechaRegistro;
}
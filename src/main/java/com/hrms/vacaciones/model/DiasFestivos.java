package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "dias_festivos")
public class DiasFestivos {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "fecha")
    private LocalDate fecha;

    @Column(name = "descripcion", length = 255)
    private String descripcion;

    // Nuestro candado para apagar feriados pasados sin borrarlos del historial
    @Builder.Default
    @Column(columnDefinition = "boolean default true")
    private Boolean activo = true;

    // --- ¡ESO ÚLTIMO VA AQUÍ MERITO, PADRINO! ---

    @Builder.Default
    @Column(name = "por_ley", length = 2)
    private String porLey = "NO"; // Guardará "SI" o "NO" para inspecciones de la STPS

    @Builder.Default
    @Column(name = "por_contrato", length = 2)
    private String porContrato = "NO"; // Guardará "SI" o "NO" para revisiones del sindicato
}
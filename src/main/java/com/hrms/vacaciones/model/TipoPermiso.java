package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "cat_tipos_permiso")
public class TipoPermiso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(unique = true, nullable = false, length = 10)
    private String codigo;

    @Column(nullable = false, length = 100)
    private String descripcion;

    @Column(name = "aplica_a", nullable = false, length = 15)
    private String aplicaA; // SIND, ADMIN, TODOS

    @Column(nullable = false)
    private Boolean activo = true; // ¡Tu súper switch dinámico!
}
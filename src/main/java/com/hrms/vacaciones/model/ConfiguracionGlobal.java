package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "configuraciones_globales")
public class ConfiguracionGlobal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "parametro", length = 100, unique = true)
    private String parametro;

    @Column(name = "valor", length = 255)
    private String valor;

    @Column(name = "descripcion", length = 255)
    private String descripcion;
}

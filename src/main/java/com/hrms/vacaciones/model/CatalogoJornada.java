package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "catalogo_jornadas")
public class CatalogoJornada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "nombre_jornada", length = 100)
    private String nombreJornada;

    @Column(name = "lunes")
    private Boolean lunes;

    @Column(name = "martes")
    private Boolean martes;

    @Column(name = "miercoles")
    private Boolean miercoles;

    @Column(name = "jueves")
    private Boolean jueves;

    @Column(name = "viernes")
    private Boolean viernes;

    @Column(name = "sabado")
    private Boolean sabado;

    @Column(name = "domingo")
    private Boolean domingo;
}

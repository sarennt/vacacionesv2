package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "prenomina_autorizada")
public class PrenominaAutorizada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String semanaContable; // Ej. "36"
    private Integer nomina;
    private String nombre;
    private String turno; // Soporta "#N/A"

    private String asisLun; private Double teLun;
    private String asisMar; private Double teMar;
    private String asisMie; private Double teMie;
    private String asisJue; private Double teJue;
    private String asisVie; private Double teVie;
    private String asisSab; private Double teSab;
    private String asisDom; private Double teDom;

    private Double totalFaltas;
    private Double totalTe;

    @Column(length = 500)
    private String observaciones; // Soporta textos largos
}
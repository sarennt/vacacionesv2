package com.hrms.vacaciones.model;

import jakarta.persistence.*;

@Entity
@Table(name = "centros_costo")
public class CentroCosto {

    @Id
    private Integer id;

    private String nombre;

    public CentroCosto() {
    }

    public CentroCosto(Integer id, String nombre) {
        this.id = id;
        this.nombre = nombre;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }
}

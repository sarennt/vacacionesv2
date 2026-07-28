package com.hrms.vacaciones.model;

import jakarta.persistence.*;

@Entity
@Table(name = "cat_roles_descanso")
public class RolDescanso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String descripcion;

    @Column(name = "dia_descanso_1")
    private Integer diaDescanso1;

    @Column(name = "dia_descanso_2")
    private Integer diaDescanso2;

    @Column(columnDefinition = "boolean default true")
    private Boolean activo = true;

    @Column(name = "es_por_defecto", columnDefinition = "boolean default false")
    private Boolean esPorDefecto = false;

    public RolDescanso() {
    }

    public RolDescanso(String descripcion, Integer diaDescanso1, Integer diaDescanso2, Boolean activo, Boolean esPorDefecto) {
        this.descripcion = descripcion;
        this.diaDescanso1 = diaDescanso1;
        this.diaDescanso2 = diaDescanso2;
        this.activo = activo;
        this.esPorDefecto = esPorDefecto;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public Integer getDiaDescanso1() {
        return diaDescanso1;
    }

    public void setDiaDescanso1(Integer diaDescanso1) {
        this.diaDescanso1 = diaDescanso1;
    }

    public Integer getDiaDescanso2() {
        return diaDescanso2;
    }

    public void setDiaDescanso2(Integer diaDescanso2) {
        this.diaDescanso2 = diaDescanso2;
    }

    public Boolean getActivo() {
        return activo;
    }

    public void setActivo(Boolean activo) {
        this.activo = activo;
    }

    public Boolean getEsPorDefecto() {
        return esPorDefecto;
    }

    public void setEsPorDefecto(Boolean esPorDefecto) {
        this.esPorDefecto = esPorDefecto;
    }
}

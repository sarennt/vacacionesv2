package com.hrms.vacaciones.model;

import jakarta.persistence.*;

@Entity
@Table(name = "boletos_vip")
public class BoletoVip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "nomina_empleado", nullable = false)
    private Integer nominaEmpleado;

    @Column(name = "supervisor_nomina", nullable = false)
    private Integer supervisorNomina;

    @Column(nullable = false)
    private Integer mes;

    @Column(nullable = false)
    private Integer anio;

    @Column(name = "dia_asignado", nullable = false)
    private String diaAsignado;

    public BoletoVip() {}

    public BoletoVip(Integer nominaEmpleado, Integer supervisorNomina, Integer mes, Integer anio, String diaAsignado) {
        this.nominaEmpleado = nominaEmpleado;
        this.supervisorNomina = supervisorNomina;
        this.mes = mes;
        this.anio = anio;
        this.diaAsignado = diaAsignado;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getNominaEmpleado() { return nominaEmpleado; }
    public void setNominaEmpleado(Integer nominaEmpleado) { this.nominaEmpleado = nominaEmpleado; }

    public Integer getSupervisorNomina() { return supervisorNomina; }
    public void setSupervisorNomina(Integer supervisorNomina) { this.supervisorNomina = supervisorNomina; }

    public Integer getMes() { return mes; }
    public void setMes(Integer mes) { this.mes = mes; }

    public Integer getAnio() { return anio; }
    public void setAnio(Integer anio) { this.anio = anio; }

    public String getDiaAsignado() { return diaAsignado; }
    public void setDiaAsignado(String diaAsignado) { this.diaAsignado = diaAsignado; }
}
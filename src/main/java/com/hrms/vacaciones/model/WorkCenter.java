package com.hrms.vacaciones.model;

import jakarta.persistence.*;

@Entity
@Table(name = "work_centers")
public class WorkCenter {

    @Id
    private Integer id;

    private String nombre;

    @ManyToOne(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "centro_costo_id")
    private CentroCosto centroCosto;

    public WorkCenter() {
    }

    public WorkCenter(Integer id, String nombre, CentroCosto centroCosto) {
        this.id = id;
        this.nombre = nombre;
        this.centroCosto = centroCosto;
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

    public CentroCosto getCentroCosto() {
        return centroCosto;
    }

    public void setCentroCosto(CentroCosto centroCosto) {
        this.centroCosto = centroCosto;
    }

    @Column(name = "supervisor_nomina")
    private Integer supervisorNomina;

    public Integer getSupervisorNomina() {
        return supervisorNomina;
    }

    public void setSupervisorNomina(Integer supervisorNomina) {
        this.supervisorNomina = supervisorNomina;
    }

    // ==========================================
    // ⚙️ PARAMETRIZACIÓN DINÁMICA DE CUPO (PISO)
    // ==========================================
    @Column(name = "cupo_concurrente_turno", nullable = false)
    private Integer cupoConcurrenteTurno = 1;

    public Integer getCupoConcurrenteTurno() {
        return (cupoConcurrenteTurno == null || cupoConcurrenteTurno < 1) ? 1 : cupoConcurrenteTurno;
    }

    public void setCupoConcurrenteTurno(Integer cupoConcurrenteTurno) {
        this.cupoConcurrenteTurno = (cupoConcurrenteTurno == null || cupoConcurrenteTurno < 1) ? 1 : cupoConcurrenteTurno;
    }
}

package com.hrms.vacaciones.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "jefes_work_centers")
public class JefeWorkCenter {

    @Id
    @Column(name = "nomina_jefe")
    private Integer nominaJefe;

    @Column(name = "work_center_id")
    private Integer workCenterId;

    public JefeWorkCenter() {
    }

    public Integer getNominaJefe() {
        return nominaJefe;
    }

    public void setNominaJefe(Integer nominaJefe) {
        this.nominaJefe = nominaJefe;
    }

    public Integer getWorkCenterId() {
        return workCenterId;
    }

    public void setWorkCenterId(Integer workCenterId) {
        this.workCenterId = workCenterId;
    }
}

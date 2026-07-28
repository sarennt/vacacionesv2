package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "auditoria_saldo")
public class AuditoriaSaldo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "nomina_empleado")
    private Integer nominaEmpleado;

    @Column(name = "dias_modificados")
    private Double diasModificados;

    @Column(name = "comentario_justificacion", length = 500)
    private String comentarioJustificacion;

    @Column(name = "fecha_movimiento")
    private LocalDateTime fechaMovimiento;

    @Column(name = "realizado_por")
    private String realizadoPor;

    public AuditoriaSaldo() {
    }

    public AuditoriaSaldo(Integer nominaEmpleado, Double diasModificados, String comentarioJustificacion, LocalDateTime fechaMovimiento, String realizadoPor) {
        this.nominaEmpleado = nominaEmpleado;
        this.diasModificados = diasModificados;
        this.comentarioJustificacion = comentarioJustificacion;
        this.fechaMovimiento = fechaMovimiento;
        this.realizadoPor = realizadoPor;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getNominaEmpleado() {
        return nominaEmpleado;
    }

    public void setNominaEmpleado(Integer nominaEmpleado) {
        this.nominaEmpleado = nominaEmpleado;
    }

    public Double getDiasModificados() {
        return diasModificados;
    }

    public void setDiasModificados(Double diasModificados) {
        this.diasModificados = diasModificados;
    }

    public String getComentarioJustificacion() {
        return comentarioJustificacion;
    }

    public void setComentarioJustificacion(String comentarioJustificacion) {
        this.comentarioJustificacion = comentarioJustificacion;
    }

    public LocalDateTime getFechaMovimiento() {
        return fechaMovimiento;
    }

    public void setFechaMovimiento(LocalDateTime fechaMovimiento) {
        this.fechaMovimiento = fechaMovimiento;
    }

    public String getRealizadoPor() {
        return realizadoPor;
    }

    public void setRealizadoPor(String realizadoPor) {
        this.realizadoPor = realizadoPor;
    }
}

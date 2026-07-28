package com.hrms.vacaciones.dto;

public class AjusteSaldoRequest {
    private Integer nominaEmpleado;
    private Double diasModificados;
    private String comentarioJustificacion;
    private String realizadoPor;

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

    public String getRealizadoPor() {
        return realizadoPor;
    }

    public void setRealizadoPor(String realizadoPor) {
        this.realizadoPor = realizadoPor;
    }
}

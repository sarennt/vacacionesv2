package com.hrms.vacaciones.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "configuracion_cortes_nomina")
public class ConfiguracionCorteNomina {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "tipo_empleado")
    private String tipoEmpleado;

    @Column(name = "dia_corte")
    private Integer diaCorte;

    @Column(name = "hora_corte")
    private LocalTime horaCorte;
}

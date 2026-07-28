package com.hrms.vacaciones.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "configuracion_roles_nomina")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfiguracionRolesNomina {
    @Id
    private String llavePuesto;
    private Integer numNominaAsignada; // <--- Cambiado a Integer
}
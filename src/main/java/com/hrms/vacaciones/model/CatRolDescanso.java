package com.hrms.vacaciones.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cat_roles_descanso")
public class CatRolDescanso {

    @Id
    private Integer id;

    private String descripcion;

    @Column(name = "dia_descanso_1")
    private Integer diaDescanso1;

    @Column(name = "dia_descanso_2")
    private Integer diaDescanso2;
}

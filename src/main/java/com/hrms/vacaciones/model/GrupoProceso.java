package com.hrms.vacaciones.model;

import jakarta.persistence.*; // Si usas una versión antigua de Spring Boot, cambia "jakarta" por "javax"
import java.util.List;

@Entity
@Table(name = "grupos_proceso")
public class GrupoProceso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "nombre_grupo", nullable = false)
    private String nombre;

    @Column(name = "cupo_maximo", nullable = false)
    private Integer cupoMaximo;

    // Relación: Un Grupo de Proceso agrupa a varios Centros de Costo
    @OneToMany
    @JoinColumn(name = "grupo_proceso_id") // Esto creará la llave foránea en tu tabla de centros_costo
    private List<CentroCosto> centrosCosto;

    // ==========================================
    // Constructor vacío requerido por JPA
    // ==========================================
    public GrupoProceso() {
    }

    // ==========================================
    // Getters y Setters
    // ==========================================
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

    public Integer getCupoMaximo() {
        return cupoMaximo;
    }

    public void setCupoMaximo(Integer cupoMaximo) {
        this.cupoMaximo = cupoMaximo;
    }

    public List<CentroCosto> getCentrosCosto() {
        return centrosCosto;
    }

    public void setCentrosCosto(List<CentroCosto> centrosCosto) {
        this.centrosCosto = centrosCosto;
    }
}
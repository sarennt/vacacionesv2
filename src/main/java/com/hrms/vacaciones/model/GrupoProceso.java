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

    @Column(name = "supervisor_nomina")
    private Integer supervisorNomina;

    // ✨ NUEVOS CAMPOS DE AGRUPACIÓN HÍBRIDA
    @Column(name = "tipo_agrupacion", length = 5)
    private String tipoAgrupacion = "CC"; // Puede ser "CC" o "WC"

    @Column(name = "cupo_maximo_turno")
    private Integer cupoMaximoTurno;

    // ✨ CAMBIO: Cambiamos a EAGER e inicializamos la lista
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "grupos_proceso_wcs",
            joinColumns = @JoinColumn(name = "grupo_proceso_id"),
            inverseJoinColumns = @JoinColumn(name = "work_center_id")
    )
    private List<WorkCenter> workCenters = new java.util.ArrayList<>();

    // ==========================================
    // Constructor vacío requerido por JPA
    // ==========================================
    public GrupoProceso() {
    }

    // ==========================================
    // Getters y Setters Generales
    // ==========================================
    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public Integer getCupoMaximo() { return cupoMaximo; }
    public void setCupoMaximo(Integer cupoMaximo) { this.cupoMaximo = cupoMaximo; }

    // ✨ CAMBIO: Blindaje para no devolver nunca una lista nula a Thymeleaf
    public List<CentroCosto> getCentrosCosto() { return centrosCosto != null ? centrosCosto : new java.util.ArrayList<>(); }
    public void setCentrosCosto(List<CentroCosto> centrosCosto) { this.centrosCosto = centrosCosto; }

    public Integer getSupervisorNomina() { return supervisorNomina; }
    public void setSupervisorNomina(Integer supervisorNomina) { this.supervisorNomina = supervisorNomina; }

    // ✨ CAMBIO: Blindaje para no devolver nulos
    public String getTipoAgrupacion() { return tipoAgrupacion != null ? tipoAgrupacion : "CC"; }
    public void setTipoAgrupacion(String tipoAgrupacion) { this.tipoAgrupacion = tipoAgrupacion; }

    public Integer getCupoMaximoTurno() { return cupoMaximoTurno; }
    public void setCupoMaximoTurno(Integer cupoMaximoTurno) { this.cupoMaximoTurno = cupoMaximoTurno; }

    // ✨ CAMBIO: Blindaje para no devolver nunca una lista nula a Thymeleaf
    public List<WorkCenter> getWorkCenters() { return workCenters != null ? workCenters : new java.util.ArrayList<>(); }
    public void setWorkCenters(List<WorkCenter> workCenters) { this.workCenters = workCenters; }
}
package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "solicitudes_permisos")
public class SolicitudPermiso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // Enlazamos directo a la nómina del empleado
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empleado_id", referencedColumnName = "nomina", nullable = false)
    private Empleado empleado;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tipo_permiso_id", nullable = false)
    private TipoPermiso tipoPermiso;

    // --- CÓDIGO A AGREGAR ---
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "turno_id")
    private Turno turno;
    // ------------------------

    @Column(name = "fecha_solicitud", nullable = false)
    private LocalDateTime fechaSolicitud;

    @Column(name = "fecha_incidencia", nullable = false)
    private LocalDate fechaIncidencia; // El día de la falta o el día del HO/Salida

    @Column(nullable = false, length = 30)
    private String estatus; // PENDIENTE, APROBADO, RECHAZADO, CANCELADO_GUILLOTINA

    @Column(name = "comprobante_path", length = 255)
    private String comprobantePath; // Para las capturas de correo de admins

    @Column(name = "justificacion_supervisor", columnDefinition = "TEXT")
    private String justificacionSupervisor; // El "Tapa-bocas" si meten fechas muy viejas

    @Column(name = "horas_permiso", precision = 4, scale = 2)
    private java.math.BigDecimal horasPermiso;

    @Builder.Default
    @Column(name = "es_por_horas", nullable = false)
    private Boolean esPorHoras = false;

    // Relación limpia con su tabla hija (Desglose de horas TXT)
    @OneToMany(mappedBy = "solicitudPermiso", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SolicitudTxtPago> desglosesPago;

    @PrePersist
    protected void onCreate() {
        if (this.fechaSolicitud == null) {
            this.fechaSolicitud = LocalDateTime.now();
        }
        if (this.estatus == null) {
            this.estatus = "PENDIENTE";
        }
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resuelto_por_id")
    private Empleado resueltoPor;
}

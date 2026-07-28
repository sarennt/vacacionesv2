package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "solicitudes_vacaciones")
public class SolicitudVacaciones {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // Relación ManyToOne: Quién va a disfrutar las vacaciones
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empleado_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Empleado empleado;

    @Column(name = "fecha_inicio", nullable = false)
    private LocalDate fechaInicio;

    @Column(name = "fecha_fin", nullable = false)
    private LocalDate fechaFin;

    @Column(name = "dias_totales_calculados", nullable = false)
    private Integer diasTotalesCalculados;

    @Column(name = "tipo_solicitud", nullable = false, length = 50)
    private String tipoSolicitud;

    @Column(name = "estatus", nullable = false, length = 50)
    private String estatus;

    @Column(name = "lote_autorizacion_masiva", length = 100)
    private String loteAutorizacionMasiva;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;

    @Column(name = "jefe_autorizador_nomina")
    private Integer jefeAutorizadorNomina;

    // Relación ManyToOne: Jefe o RRHH que quizás levantó la solicitud en nombre del
    // empleado
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creada_por_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Empleado creadaPor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "aprobado_por_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Empleado aprobadoPor;

    @Column(name = "es_excepcion_limite", nullable = false)
    @Builder.Default
    private Boolean esExcepcionLimite = false;

    // Se especifica tipo TEXT ya que PostgreSQL usa ese tipo en lugar del VARCHAR
    // ordinario
    @Column(name = "comentario_excepcion", columnDefinition = "TEXT")
    private String comentarioExcepcion;

    // Se usa TEXT para evitar los cast de límite natural de JPA con strings
    // extensos
    @Column(name = "notas_sistema", columnDefinition = "TEXT")
    private String notasSistema;

    @Column(name = "es_extemporanea", columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean esExtemporanea = false;

    @Column(name = "fecha_aprobacion_jefe")
    private LocalDateTime fechaAprobacionJefe;

    @Column(name = "comentario_jefe", length = 500)
    private String comentarioJefe;

    @Column(name = "fecha_aprobacion_supervisor")
    private LocalDateTime fechaAprobacionSupervisor;

    @Column(name = "comentario_supervisor", length = 500)
    private String comentarioSupervisor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rol_descanso_id") // Se mantiene el nombre de columna física por el histórico relacional
    private Turno turno; // ✨ Cambiado: Ahora la clase mapeada es Turno

    // ✨ NUEVO: Hilo conductor para empaquetar solicitudes fraccionadas en el Dashboard
    @Column(name = "grupo_folio", length = 100)
    private String grupoFolio;
}

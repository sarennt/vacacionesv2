package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.model.SolicitudVacaciones;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SolicitudVacacionesRepository extends JpaRepository<SolicitudVacaciones, Integer> {

        List<SolicitudVacaciones> findByEmpleado_NominaOrderByFechaInicioDesc(Integer nomina);

        List<SolicitudVacaciones> findByEstatusIn(List<String> estatus);

        List<SolicitudVacaciones> findByEmpleadoInAndEstatusIn(List<Empleado> empleados, List<String> estatus);

        // ⏰ CONTRATO DE ESCALAMIENTO: Habilita el barrido automático del Cron Job para solicitudes rezagadas
        List<SolicitudVacaciones> findByEstatusAndFechaCreacionBefore(String estatus, LocalDateTime fechaCreacion);

        // 📊 CONTRATO DE PRENÓMINA: Cruza las solicitudes aprobadas que se empalman con la semana del reporte Excel
        List<SolicitudVacaciones> findByEstatusAndFechaInicioLessThanEqualAndFechaFinGreaterThanEqual(
                String estatus, LocalDate fechaInicioMax, LocalDate fechaFinMin);

        // 🪓 CONTRATO DE LA GUILLOTINA: Captura solicitudes pendientes (Jefe/Supervisor) anteriores al cierre de incidencias
        // El cast a nivel JPQL garantiza que LocalDate compare limpiamente contra la columna timestamp de PostgreSQL
        @Query("SELECT s FROM SolicitudVacaciones s WHERE s.empleado.tipoEmpleado = :tipoEmpleado " +
                "AND UPPER(s.estatus) LIKE 'PENDIENTE%' AND s.fechaCreacion < :limite")
        List<SolicitudVacaciones> findHuerfanasParaGuillotina(
                @Param("tipoEmpleado") String tipoEmpleado,
                @Param("limite") LocalDate limite);

        // 🔒 Candado de Rechazos Previos
        @Query("SELECT COUNT(s) FROM SolicitudVacaciones s WHERE s.empleado.nomina = :nomina " +
                "AND s.estatus = 'Rechazado' AND (s.fechaInicio <= :fechaFin AND s.fechaFin >= :fechaInicio)")
        long contarRechazosPrevios(@Param("nomina") Integer nomina,
                                   @Param("fechaInicio") LocalDate fechaInicio,
                                   @Param("fechaFin") LocalDate fechaFin);

        // 🔒 Detector de Empalmes de Agenda
        @Query("SELECT s FROM SolicitudVacaciones s WHERE s.empleado.nomina = :nomina " +
                "AND s.estatus <> 'Rechazado' AND (s.fechaInicio <= :fechaFin AND s.fechaFin >= :fechaInicio)")
        Optional<SolicitudVacaciones> encontrarSolicitudEmpalmada(@Param("nomina") Integer nomina,
                                                                  @Param("fechaInicio") LocalDate fechaInicio,
                                                                  @Param("fechaFin") LocalDate fechaFin);

        // ⚙️ Validador de Headcount de Línea por Día
        @Query("SELECT COUNT(s) FROM SolicitudVacaciones s WHERE s.empleado.workCenter.id = :wcId " +
                "AND s.estatus = 'Aprobado' AND :dia BETWEEN s.fechaInicio AND s.fechaFin")
        long contarSolicitudesActivasPorDiaYWc(@Param("wcId") Integer wcId, @Param("dia") LocalDate dia);

        // 📊 Métricas de Jefatura (Mes en Curso)
        @Query("SELECT COUNT(s) FROM SolicitudVacaciones s WHERE s.jefeAutorizadorNomina = :nominaJefe " +
                "AND s.estatus = 'Aprobado' AND s.fechaAprobacionJefe BETWEEN :inicio AND :fin")
        long contarAprobadasPorJefeEnRango(@Param("nominaJefe") Integer nominaJefe,
                                           @Param("inicio") LocalDateTime inicio,
                                           @Param("fin") LocalDateTime fin);

        // 🏢 Monitor de Ausencias Próximas de Equipo (7 Días de Gracia)
        @Query("SELECT COUNT(s) FROM SolicitudVacaciones s WHERE s.empleado.workCenter.id IN :wcIds " +
                "AND s.estatus = 'Aprobado' AND (s.fechaInicio <= :fechaFin AND s.fechaFin >= :fechaInicio)")
        long contarAusenciasEquipoEnRango(@Param("wcIds") List<Integer> wcIds,
                                          @Param("fechaInicio") LocalDate fechaInicio,
                                          @Param("fechaFin") LocalDate fechaFin);

        // 🔒 Cortafuegos de Interferencia Vacaciones vs Permisos
        @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM SolicitudVacaciones s " +
                "WHERE s.empleado.nomina = :nomina AND s.estatus IN :estatusList AND :fecha BETWEEN s.fechaInicio AND s.fechaFin")
        boolean verificarInterferenciaVacaciones(@Param("nomina") Integer nomina,
                                                 @Param("fecha") LocalDate fecha,
                                                 @Param("estatusList") List<String> estatusList);

        @Query("SELECT COUNT(s) FROM SolicitudVacaciones s WHERE s.empleado.workCenter.id = :wcId AND s.turno.id = :turnoId AND s.estatus NOT IN ('Rechazado', 'Cancelado') AND :dia BETWEEN s.fechaInicio AND s.fechaFin")
        long contarOcupadosPorLineaYTurno(@org.springframework.data.repository.query.Param("wcId") Integer wcId, @org.springframework.data.repository.query.Param("turnoId") Integer turnoId, @org.springframework.data.repository.query.Param("dia") java.time.LocalDate dia);

        @Query("SELECT COUNT(s) FROM SolicitudVacaciones s WHERE s.empleado.workCenter.id IN :wcs AND s.turno.id = :turnoId AND s.estatus NOT IN ('Rechazado', 'Cancelado') AND :dia BETWEEN s.fechaInicio AND s.fechaFin")
        long contarOcupadosPorAreaYTurno(@org.springframework.data.repository.query.Param("wcs") java.util.List<Integer> wcs, @org.springframework.data.repository.query.Param("turnoId") Integer turnoId, @org.springframework.data.repository.query.Param("dia") java.time.LocalDate dia);

        @Query("SELECT COUNT(s) FROM SolicitudVacaciones s " +
                "WHERE s.empleado.centroCosto.id IN :ccIds " +
                "AND s.estatus NOT IN ('Rechazado', 'Cancelada', 'CANCELADA_CORTENOMINA') " +
                "AND s.fechaInicio <= :fecha AND s.fechaFin >= :fecha")
        long countVacacionesPorGrupoYFecha(@Param("ccIds") List<Integer> ccIds, @Param("fecha") LocalDate fecha);
}
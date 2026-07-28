package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.SolicitudPermiso;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SolicitudPermisoRepository extends JpaRepository<SolicitudPermiso, Integer> {

    // 1. Para la Guillotina automática: Busca los pendientes que ya se los comió el corte de nómina
    List<SolicitudPermiso> findByEstatusAndFechaIncidenciaLessThanEqual(String estatus, LocalDate fechaCorte);

    // 2. Para el Calendario Unificado del Jefe: Trae las faltas o salidas futuras/pasadas
    @Query("SELECT s FROM SolicitudPermiso s WHERE s.estatus IN ('APROBADO', 'PENDIENTE') " +
            "AND s.fechaIncidencia BETWEEN :inicio AND :fin")
    List<SolicitudPermiso> buscarPermisosParaCalendario(@Param("inicio") LocalDate inicio, @Param("fin") LocalDate fin);

    // 3. Cruce de Cables: Evita que el chato meta una falta o permiso en un día que ya tiene algo registrado
    boolean existsByEmpleadoNominaAndFechaIncidenciaAndEstatusIn(Integer nomina, LocalDate fecha, List<String> estatus);

    // para validar si una línea (Work Center) ya fue procesada en esa fecha
    @Query("SELECT COUNT(s) > 0 FROM SolicitudPermiso s WHERE s.empleado.workCenter.id = :wcId " +
            "AND s.fechaIncidencia = :fechaParo AND s.estatus <> 'CANCELADO_GUILLOTINA'")
    boolean existeRegistroColectivoPorLinea(@Param("wcId") Integer wcId, @Param("fechaParo") LocalDate fechaParo);

}
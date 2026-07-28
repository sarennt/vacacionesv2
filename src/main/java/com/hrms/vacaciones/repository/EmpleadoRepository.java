package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.Empleado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmpleadoRepository extends JpaRepository<Empleado, Integer> {
    Optional<Empleado> findByTag(String tag);

    // --- LAS LÍNEAS PARA LAS PLANTILLAS (Shift Leader vs Supervisor) ---
    List<Empleado> findByWorkCenter_IdIn(List<Integer> workCenterIds);
    List<Empleado> findByCentroCosto_IdIn(List<Integer> centroCostoIds); // <-- CIRUGÍA: El súper poder para ver la plantilla de Supervisors
    // Busca empleados usando directamente la nómina de su jefe directo
    List<Empleado> findByJefeDirectoNomina(Integer jefeDirectoNomina);
    List<Empleado> findByWorkCenterIdInAndEstatus(List<Integer> wcIds, String estatus);

    // 1. Buscar jefes por WorkCenter
    @Query("SELECT DISTINCT e FROM Empleado e JOIN e.workCentersACargo wc WHERE wc.id = :wcId")
    List<Empleado> findEmpleadosJefesPorWcId(@Param("wcId") Integer wcId);

    // 2. Buscar jefes por Centro de Costo
    @Query("SELECT DISTINCT e FROM Empleado e JOIN e.centrosCostoACargo cc WHERE cc.id = :ccId")
    List<Empleado> findEmpleadosJefesPorCcId(@Param("ccId") Integer ccId);

    // 3. El original corregido
    @Query("SELECT e FROM Empleado e WHERE e.rolJerarquico IN ('Supervisor', 'Shift Leader', 'Gerente', 'Director')")
    List<Empleado> findTodosLosJefes();

    // ✨ RANKING VIP PLANO: Conteo en caliente amarrado directo al Supervisor (CERO MOCKS)
    // Blindado contra nulos, espacios huérfanos y variaciones de mayúsculas/minúsculas en el kárdex
    @Query(value = "SELECT COUNT(*) + 1 FROM empleados e " +
            "JOIN work_centers wc ON e.work_center_id = wc.id " +
            "WHERE wc.supervisor_nomina = :supervisorNomina " +
            "AND TRIM(UPPER(e.tipo_empleado)) = 'SINDICALIZADO' " +
            "AND TRIM(UPPER(e.estatus)) = 'ACTIVO' " +
            "AND (COALESCE(e.saldo_vacaciones_actual, 0) > COALESCE(:saldo, 0) " +
            "OR (COALESCE(e.saldo_vacaciones_actual, 0) = COALESCE(:saldo, 0) AND e.nomina < :nomina))",
            nativeQuery = true)
    int obtenerPosicionRankingSupervisorVivo(@Param("supervisorNomina") Integer supervisorNomina,
                                             @Param("saldo") java.math.BigDecimal saldo,
                                             @Param("nomina") Integer nomina);
}
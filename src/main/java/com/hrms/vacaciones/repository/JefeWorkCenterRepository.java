package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.JefeWorkCenter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JefeWorkCenterRepository extends JpaRepository<JefeWorkCenter, Integer> {

    // Borramos el método anterior y ponemos nuestro atajo directo a la base de
    // datos
    @Query(value = "SELECT work_center_id FROM jefes_work_centers WHERE nomina_jefe = :nomina", nativeQuery = true)
    List<Integer> findWorkCentersByNominaJefe(@Param("nomina") Integer nominaJefe);

}
package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.GrupoProceso;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface GrupoProcesoRepository extends JpaRepository<GrupoProceso, Integer> {

    // Este es el método que necesita el VacacionesService para encontrar el grupo de un operador
    Optional<GrupoProceso> findByCentrosCosto_Id(Integer centroCostoId);
}
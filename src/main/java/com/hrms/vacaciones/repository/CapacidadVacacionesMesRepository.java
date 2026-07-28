package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.CapacidadVacacionesMes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface CapacidadVacacionesMesRepository extends JpaRepository<CapacidadVacacionesMes, Integer> {

    // 🔥 EL NUEVO CERROJO DE HIERRO MACRO: Valida la capacidad de ausentismo cruzando Supervisor + Tiempo + Turno Único
    Optional<CapacidadVacacionesMes> findBySupervisorNominaAndMesAndAnioAndTurno_Id(
            Integer supervisorNomina, Integer mes, Integer anio, Integer turnoId);
}
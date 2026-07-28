package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.BloqueoJefatura;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface BloqueoJefaturaRepository extends JpaRepository<BloqueoJefatura, Integer> {

    // Este método nos va a servir muchísimo más adelante para que el sistema
    // sepa si una fecha está bloqueada por el jefe al momento de que el operador la solicite
    List<BloqueoJefatura> findBySupervisorNominaAndFechaInicioLessThanEqualAndFechaFinGreaterThanEqual(
            Integer supervisorNomina, LocalDate fechaFin, LocalDate fechaInicio);
}
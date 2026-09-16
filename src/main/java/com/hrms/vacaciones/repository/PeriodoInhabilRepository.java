package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.PeriodoInhabil;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PeriodoInhabilRepository extends JpaRepository<PeriodoInhabil, Integer> {
    // Para mostrarle al Jefe su lista de bloqueos, del más nuevo al más viejo
    List<PeriodoInhabil> findBySupervisorNominaOrderByFechaInicioDesc(Integer supervisorNomina);
}
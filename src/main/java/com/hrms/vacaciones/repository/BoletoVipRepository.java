package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.BoletoVip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BoletoVipRepository extends JpaRepository<BoletoVip, Integer> {
    void deleteBySupervisorNominaAndMesAndAnio(Integer supervisorNomina, Integer mes, Integer anio);
    Optional<BoletoVip> findByNominaEmpleadoAndMesAndAnio(Integer nominaEmpleado, Integer mes, Integer anio);
}
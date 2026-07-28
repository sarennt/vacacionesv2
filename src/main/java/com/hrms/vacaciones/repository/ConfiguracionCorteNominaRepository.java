package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.ConfiguracionCorteNomina;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConfiguracionCorteNominaRepository extends JpaRepository<ConfiguracionCorteNomina, Integer> {
    
    Optional<ConfiguracionCorteNomina> findByTipoEmpleado(String tipoEmpleado);
}

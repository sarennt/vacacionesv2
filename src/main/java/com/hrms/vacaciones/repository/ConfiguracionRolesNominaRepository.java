package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.ConfiguracionRolesNomina;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConfiguracionRolesNominaRepository extends JpaRepository<ConfiguracionRolesNomina, String> {
}
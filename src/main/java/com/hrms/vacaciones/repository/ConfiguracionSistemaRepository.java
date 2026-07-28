package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.ConfiguracionSistema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConfiguracionSistemaRepository extends JpaRepository<ConfiguracionSistema, String> {
}

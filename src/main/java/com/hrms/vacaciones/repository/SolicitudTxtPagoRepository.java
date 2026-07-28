package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.SolicitudTxtPago;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SolicitudTxtPagoRepository extends JpaRepository<SolicitudTxtPago, Integer> {
    // Si la dejamos limpia, Spring Boot nos regala el save(), delete() y findById() gratis
}
package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.AuditoriaSaldo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditoriaSaldoRepository extends JpaRepository<AuditoriaSaldo, Integer> {
    List<AuditoriaSaldo> findByNominaEmpleadoOrderByFechaMovimientoDesc(Integer nominaEmpleado);
}

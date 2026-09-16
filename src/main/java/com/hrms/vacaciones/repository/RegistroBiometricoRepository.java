package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.RegistroBiometrico;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RegistroBiometricoRepository extends JpaRepository<RegistroBiometrico, Integer> {

    List<RegistroBiometrico> findByEmpleadoNominaAndEstatusOrderByTimestampRegistroAsc(Integer nomina, String estatus);
    List<RegistroBiometrico> findByEmpleadoNominaAndFechaLimpiaBetween(Integer nomina, LocalDate inicio, LocalDate fin);

    // ✨ NUEVO: El detector de duplicados en base de datos
    boolean existsByEmpleadoNominaAndTimestampRegistro(Integer nomina, LocalDateTime timestampRegistro);
}
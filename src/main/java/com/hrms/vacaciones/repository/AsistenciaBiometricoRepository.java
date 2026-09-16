package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.AsistenciaBiometrico;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AsistenciaBiometricoRepository extends JpaRepository<AsistenciaBiometrico, Integer> {

    // ✨ Método para extraer la semana completa de un operador, ordenada cronológicamente
    List<AsistenciaBiometrico> findByEmpleadoNominaAndFechaReferenciaBetweenOrderByFechaReferenciaAsc(
            Integer nomina,
            LocalDate inicioSemana,
            LocalDate finSemana
    );

    // ✨ Para el escaneo global de la sábana viva
    List<AsistenciaBiometrico> findByFechaReferenciaBetweenOrderByEmpleadoNominaAscFechaReferenciaAsc(LocalDate inicio, LocalDate fin);
}
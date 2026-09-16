package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.RangoTurno;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RangoTurnoRepository extends JpaRepository<RangoTurno, Integer> {
}
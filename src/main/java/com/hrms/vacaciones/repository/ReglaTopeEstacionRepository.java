package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.ReglaTopeEstacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReglaTopeEstacionRepository extends JpaRepository<ReglaTopeEstacion, Integer> {
}
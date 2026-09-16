package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.TabuladorVacaciones;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TabuladorVacacionesRepository extends JpaRepository<TabuladorVacaciones, Integer> {
}
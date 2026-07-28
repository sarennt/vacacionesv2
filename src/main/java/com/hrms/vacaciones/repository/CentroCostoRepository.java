package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.CentroCosto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;



@Repository
public interface CentroCostoRepository extends JpaRepository<CentroCosto, Integer> {
}

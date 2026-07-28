package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.WorkCenter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;



@Repository
public interface WorkCenterRepository extends JpaRepository<WorkCenter, Integer> {
}

package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.DiasFestivos;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DiasFestivosRepository extends JpaRepository<DiasFestivos, Integer> {

    // Solo nos traemos los que están marcados como activos
    List<DiasFestivos> findByActivoTrue();
}
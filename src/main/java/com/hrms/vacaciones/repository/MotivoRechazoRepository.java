package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.MotivoRechazo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MotivoRechazoRepository extends JpaRepository<MotivoRechazo, Integer> {
    List<MotivoRechazo> findByModuloAndActivoTrue(String modulo);
    List<MotivoRechazo> findByActivoTrue();
}
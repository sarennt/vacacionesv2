package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.HistoricoConfiguracionArea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface HistoricoConfiguracionAreaRepository extends JpaRepository<HistoricoConfiguracionArea, Integer> {

    // Trae las bitácoras de auditoría de un bloque de líneas ordenadas desde la más nueva
    List<HistoricoConfiguracionArea> findByWorkCenterIdInOrderByFechaRegistroDesc(List<Integer> workCenterIds);
}
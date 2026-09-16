package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.HistoricoConfiguracionArea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface HistoricoConfiguracionAreaRepository extends JpaRepository<HistoricoConfiguracionArea, Integer> {

    // ✨ El único método V2.0 que necesitamos para el historial
    List<HistoricoConfiguracionArea> findByRealizadoPorNominaOrderByFechaRegistroDesc(Integer nominaJefe);
}
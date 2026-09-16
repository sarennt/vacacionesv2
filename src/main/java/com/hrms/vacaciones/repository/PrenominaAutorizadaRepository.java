package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.PrenominaAutorizada;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PrenominaAutorizadaRepository extends JpaRepository<PrenominaAutorizada, Long> {
    // Para cuando queramos pintar el visor por semana
    List<PrenominaAutorizada> findBySemanaContableOrderByNominaAsc(String semanaContable);

    // Para limpiar la semana antes de sobreescribirla si te equivocas y vuelves a subir el archivo
    void deleteBySemanaContable(String semanaContable);

    java.util.Optional<PrenominaAutorizada> findByNominaAndSemanaContable(Integer nomina, String semanaContable);
}
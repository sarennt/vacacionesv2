package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.TipoPermiso;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional; // 👈 ¡SÚPER IMPORTANTE! Si falta este import, la línea del Service truena

@Repository
public interface TipoPermisoRepository extends JpaRepository<TipoPermiso, Integer> {

    // Filtro para el Dropdown dinámico
    List<TipoPermiso> findByActivoTrueAndAplicaAIn(List<String> mundos);

    // 🎯 EL REMEDIO SANTO: Este método debe existir aquí para que el Service lo pueda usar
    Optional<TipoPermiso> findByCodigo(String codigo);
}
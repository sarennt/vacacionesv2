package com.hrms.vacaciones.repository;

import com.hrms.vacaciones.model.Turno;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface TurnoRepository extends JpaRepository<Turno, Integer> {

    // ✨ Conecta limpio con findByNombreTurno en PermisosService y en la Torre de Control
    Optional<Turno> findByNombreTurno(String nombreTurno);

    // 🛡️ El Escudo Anti-Fantasmas: Trae solo los turnos vigentes para las pantallas operativas
    List<Turno> findByActivoTrue();
}
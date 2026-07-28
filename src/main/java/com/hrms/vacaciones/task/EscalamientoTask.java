package com.hrms.vacaciones.task;

import com.hrms.vacaciones.model.ConfiguracionSistema;
import com.hrms.vacaciones.model.SolicitudVacaciones;
import com.hrms.vacaciones.repository.ConfiguracionSistemaRepository;
import com.hrms.vacaciones.repository.SolicitudVacacionesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class EscalamientoTask {

    @Autowired
    private SolicitudVacacionesRepository solicitudRepo;

    @Autowired
    private ConfiguracionSistemaRepository configRepo;

    // Se ejecuta cada hora en punto (ej. 1:00, 2:00, 3:00...)
    @Scheduled(cron = "0 0 * * * *")
    public void revisarEscalamientos() {
        System.out.println("⏰ Iniciando revisión de escalamientos automáticos...");

        // 1. Ir a la base de datos a preguntar cuántas horas de gracia tiene el Shift
        // Leader
        int horasGracia = 48; // Valor por defecto por si falla la BD
        ConfiguracionSistema config = configRepo.findById("HORAS_ESCALAMIENTO_SHIFT").orElse(null);

        if (config != null && config.getValor() != null) {
            horasGracia = Integer.parseInt(config.getValor());
        }

        // 2. Calcular la fecha límite (Hace X horas desde este exacto momento)
        LocalDateTime fechaLimite = LocalDateTime.now().minusHours(horasGracia);

        // 3. Buscar a las "olvidadas"
        List<SolicitudVacaciones> rezagadas = solicitudRepo.findByEstatusAndFechaCreacionBefore("Pendiente_Jefe",
                fechaLimite);

        if (rezagadas.isEmpty()) {
            System.out.println("Todo al corriente. Ninguna solicitud ignorada por más de " + horasGracia + " horas.");
            return;
        }

        // 4. Moverlas al Supervisor y dejar evidencia
        for (SolicitudVacaciones sol : rezagadas) {
            sol.setEstatus("Pendiente_Supervisor");

            String notaPrevia = sol.getNotasSistema() == null ? "" : sol.getNotasSistema() + " | ";
            sol.setNotasSistema(notaPrevia + "Escalado automáticamente al Supervisor tras " + horasGracia
                    + "h sin respuesta del Shift.");

            solicitudRepo.save(sol);
            System.out.println("🚀 Solicitud ID " + sol.getId() + " escalada al Supervisor.");
        }
    }
}
package com.hrms.vacaciones.service;

import com.hrms.vacaciones.model.ConfiguracionCorteNomina;
import com.hrms.vacaciones.model.SolicitudVacaciones;
import com.hrms.vacaciones.repository.ConfiguracionCorteNominaRepository;
import com.hrms.vacaciones.repository.SolicitudVacacionesRepository;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@Component
public class BarredoraVacacionesService {

    @Autowired
    private ConfiguracionCorteNominaRepository configCorteRepo;

    @Autowired
    private SolicitudVacacionesRepository solicitudRepo;

    @Scheduled(cron = "0 0 * * * *") // Revisa cada hora en punto
    @Transactional
    public void procesarGuillotinaAutomatica() {
        String[] universos = {"SINDICALIZADO", "ADMINISTRATIVO"};

        for (String universo : universos) {
            configCorteRepo.findByTipoEmpleado(universo).ifPresent(config -> {
                if (esMomentoDeCorte(config)) {
                    ejecutarLimpiezaPara(universo);
                }
            });
        }
    }

    private boolean esMomentoDeCorte(ConfiguracionCorteNomina config) {
        if (config.getDiaCorte() == null || config.getHoraCorte() == null) return false;

        LocalDateTime ahora = LocalDateTime.now();
        DayOfWeek diaConfigurado = (config.getDiaCorte() == 0) ? DayOfWeek.SUNDAY : DayOfWeek.of(config.getDiaCorte());

        return ahora.getDayOfWeek() == diaConfigurado && ahora.getHour() == config.getHoraCorte().getHour();
    }

    private void ejecutarLimpiezaPara(String tipoEmpleado) {
        LocalDate ahora = LocalDate.now();
        // Límite de incidencias: El domingo de la semana pasada
        LocalDate finIncidenciasPasadas = ahora.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));

        List<SolicitudVacaciones> huerfanas = solicitudRepo.findHuerfanasParaGuillotina(tipoEmpleado, finIncidenciasPasadas);

        if (!huerfanas.isEmpty()) {
            for (SolicitudVacaciones solicitud : huerfanas) {
                solicitud.setEstatus("CANCELADA");

                // Concatena la firma de auditoría sin romper comentarios anteriores
                String notasActuales = solicitud.getNotasSistema() != null ? solicitud.getNotasSistema() : "";
                solicitud.setNotasSistema(notasActuales + "\n[" + LocalDateTime.now() + "] " +
                        "SISTEMA: Solicitud CANCELADA AUTOMÁTICAMENTE por la Guillotina de Nómina. " +
                        "Razón: El periodo de incidencias de esta fecha cerró oficialmente en la Torre de Control.");
            }
            solicitudRepo.saveAll(huerfanas);
            System.out.println(">>> Barredora limpia para: " + tipoEmpleado + ". Removidas: " + huerfanas.size());
        }
    }
}
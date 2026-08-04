package com.hrms.vacaciones.service;

import com.hrms.vacaciones.model.ConfiguracionCorteNomina;
import com.hrms.vacaciones.model.SolicitudVacaciones;
import com.hrms.vacaciones.model.ConfiguracionSistema;
import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.repository.ConfiguracionCorteNominaRepository;
import com.hrms.vacaciones.repository.SolicitudVacacionesRepository;
import com.hrms.vacaciones.repository.ConfiguracionSistemaRepository;
import com.hrms.vacaciones.repository.EmpleadoRepository;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.math.BigDecimal;

@Component
public class BarredoraVacacionesService {

    @Autowired
    private ConfiguracionCorteNominaRepository configCorteRepo;

    @Autowired
    private SolicitudVacacionesRepository solicitudRepo;

    @Autowired
    private ConfiguracionSistemaRepository configuracionSistemaRepository;

    @Autowired
    private EmpleadoRepository empleadoRepository;

    // ✨ INYECTAMOS EL SERVICIO DE VACACIONES PARA EL EFECTO DOMINÓ
    @Autowired
    private VacacionesService vacacionesService;

    // ✨ EL SWITCH AHORA SOLO DECIDE SI "ESCALA" O NO AL SUPERVISOR
    private boolean isEscalamientoHaciaSupervisorHabilitado() {
        return configuracionSistemaRepository.findById("ESCALAMIENTO_AUTOMATICO")
                .map(c -> "TRUE".equalsIgnoreCase(c.getValor()) || "ENABLED".equalsIgnoreCase(c.getValor()) || "1".equals(c.getValor()))
                .orElse(false);
    }

    @Scheduled(cron = "0 0 * * * *") // Despierta cada hora en punto (24/7)
    @Transactional
    public void procesarReglasDeTiempoYGuillotina() {
        System.out.println("🤖 [BARREDORA] Despertando para barrer solicitudes...");

        // 1. PROCESAR ORDINARIAS (SLA de Auto-Aprobación / Escalamiento)
        ejecutarSlaYAutoAprobacion();

        // 2. PROCESAR EXTEMPORÁNEAS (Guillotina de Nómina)
        String[] universos = {"SINDICALIZADO", "ADMINISTRATIVO"};
        for (String universo : universos) {
            configCorteRepo.findByTipoEmpleado(universo).ifPresent(config -> {
                if (esMomentoDeCorte(config)) {
                    ejecutarLimpiezaPara(universo);
                }
            });
        }
    }

    private void ejecutarSlaYAutoAprobacion() {
        LocalDateTime ahora = LocalDateTime.now();
        int horasSla;
        try {
            horasSla = Integer.parseInt(configuracionSistemaRepository.findById("SLA_RESPUESTA_JEFE")
                    .map(ConfiguracionSistema::getValor).orElse("48").replaceAll("[^0-9]", "").trim());
        } catch (Exception e) {
            horasSla = 48;
        }

        LocalDateTime limiteGraciaShift = ahora.minusHours(horasSla);
        boolean escalaASupervisor = isEscalamientoHaciaSupervisorHabilitado();

        // A) REVISAMOS A LOS SHIFT LEADERS (Estatus: Pendiente_Jefe)
        List<SolicitudVacaciones> pendientesShift = solicitudRepo.findByEstatusAndFechaCreacionBefore("Pendiente_Jefe", limiteGraciaShift);

        for (SolicitudVacaciones sol : pendientesShift) {
            // Solo salvamos a las Ordinarias (Las extemporáneas se las come la guillotina)
            if (sol.getEsExtemporanea() == null || !sol.getEsExtemporanea()) {
                if (escalaASupervisor) {
                    // El Switch está ON: Pasa al supervisor
                    sol.setEstatus("Pendiente_Supervisor");
                    sol.setNotasSistema((sol.getNotasSistema() != null ? sol.getNotasSistema() + "\n" : "") + "[" + ahora + "] SISTEMA: Escalamiento automático a Supervisor (Shift Leader no respondió en " + horasSla + "h).");
                    solicitudRepo.save(sol);
                    System.out.println("↗️ [BARREDORA] Solicitud #" + sol.getId() + " escalada a Supervisor.");
                } else {
                    // El Switch está OFF: Se Auto-Aprueba a favor del operador de inmediato
                    aprobarSolicitudA_Favor(sol, ahora, "Auto-Aprobación (SLA Expirado para el Shift Leader).");
                }
            }
        }

        // B) REVISAMOS A LOS SUPERVISORES (Solo si el switch está prendido y llegaron a "Pendiente_Supervisor")
        // Si ya pasó el doble del tiempo (SLA * 2), significa que el Supervisor también la ignoró.
        LocalDateTime limiteGraciaSuper = ahora.minusHours(horasSla * 2L);
        List<SolicitudVacaciones> pendientesSuper = solicitudRepo.findByEstatusAndFechaCreacionBefore("Pendiente_Supervisor", limiteGraciaSuper);

        for (SolicitudVacaciones sol : pendientesSuper) {
            if (sol.getEsExtemporanea() == null || !sol.getEsExtemporanea()) {
                aprobarSolicitudA_Favor(sol, ahora, "Auto-Aprobación (SLA Expirado para el Supervisor).");
            }
        }
    }

    private void aprobarSolicitudA_Favor(SolicitudVacaciones sol, LocalDateTime ahora, String razon) {
        sol.setEstatus("Aprobado");
        sol.setComentarioJefe(razon);
        sol.setFechaAprobacionJefe(ahora);
        sol.setAprobadoPor(null); // NULL indica que fue el Sistema
        sol.setNotasSistema((sol.getNotasSistema() != null ? sol.getNotasSistema() + "\n" : "") + "[" + ahora + "] SISTEMA: " + razon);

        // Cargar matemáticas de nómina
        String tipoSol = sol.getTipoSolicitud() != null ? sol.getTipoSolicitud().trim().toUpperCase() : "";
        if (tipoSol.equals("V") || tipoSol.contains("VACACION")) {
            Empleado emp = sol.getEmpleado();
            BigDecimal saldo = emp.getSaldoVacacionesActual() != null ? emp.getSaldoVacacionesActual() : BigDecimal.ZERO;
            int dias = sol.getDiasTotalesCalculados() != null ? sol.getDiasTotalesCalculados() : 0;
            emp.setSaldoVacacionesActual(saldo.subtract(BigDecimal.valueOf(dias)));
            empleadoRepository.save(emp);
        }
        SolicitudVacaciones guardada = solicitudRepo.save(sol);

        // ✨ CORREMOS EL EFECTO DOMINÓ PORQUE ESTA SOLICITUD ACABA DE TOMAR UN LUGAR
        vacacionesService.ejecutarEfectoDominoCapacidad(guardada);

        System.out.println("✅ [BARREDORA] Solicitud #" + sol.getId() + " Auto-Aprobada y Efecto Dominó ejecutado.");
    }

    private boolean esMomentoDeCorte(ConfiguracionCorteNomina config) {
        if (config.getDiaCorte() == null || config.getHoraCorte() == null) return false;

        LocalDateTime ahora = LocalDateTime.now();
        DayOfWeek diaConfigurado = (config.getDiaCorte() == 0) ? DayOfWeek.SUNDAY : DayOfWeek.of(config.getDiaCorte());

        return ahora.getDayOfWeek() == diaConfigurado && ahora.getHour() == config.getHoraCorte().getHour();
    }

    private void ejecutarLimpiezaPara(String tipoEmpleado) {
        LocalDate ahora = LocalDate.now();
        LocalDate finIncidenciasPasadas = ahora.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));

        List<SolicitudVacaciones> huerfanas = solicitudRepo.findHuerfanasParaGuillotina(tipoEmpleado, finIncidenciasPasadas);

        if (!huerfanas.isEmpty()) {
            for (SolicitudVacaciones solicitud : huerfanas) {
                solicitud.setEstatus("CANCELADA");
                String notasActuales = solicitud.getNotasSistema() != null ? solicitud.getNotasSistema() : "";
                solicitud.setNotasSistema(notasActuales + "\n[" + LocalDateTime.now() + "] " +
                        "SISTEMA: Solicitud CANCELADA AUTOMÁTICAMENTE por la Guillotina de Nómina. " +
                        "Razón: El periodo de incidencias de esta fecha cerró oficialmente.");
            }
            solicitudRepo.saveAll(huerfanas);
            System.out.println("🪓 [BARREDORA] Guillotina ejecutada para " + tipoEmpleado + ". Removidas: " + huerfanas.size());
        }
    }
}
package com.hrms.vacaciones.service;

import com.hrms.vacaciones.model.ConfiguracionCorteNomina;
import com.hrms.vacaciones.model.ConfiguracionSistema;
import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.model.SolicitudPermiso;
import com.hrms.vacaciones.model.SolicitudVacaciones;
import com.hrms.vacaciones.repository.ConfiguracionCorteNominaRepository;
import com.hrms.vacaciones.repository.ConfiguracionSistemaRepository;
import com.hrms.vacaciones.repository.EmpleadoRepository;
import com.hrms.vacaciones.repository.SolicitudPermisoRepository;
import com.hrms.vacaciones.repository.SolicitudVacacionesRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@Slf4j
@Service
public class BarredoraVacacionesService {

    @Autowired
    private ConfiguracionCorteNominaRepository configCorteRepo;

    @Autowired
    private SolicitudVacacionesRepository solicitudRepo;

    @Autowired
    private SolicitudPermisoRepository permisoRepo;

    @Autowired
    private ConfiguracionSistemaRepository configuracionSistemaRepository;

    @Autowired
    private EmpleadoRepository empleadoRepository;

    @Autowired
    private VacacionesService vacacionesService;

    private boolean isEscalamientoHaciaSupervisorHabilitado() {
        return configuracionSistemaRepository.findById("ESCALAMIENTO_AUTOMATICO")
                .map(c -> "TRUE".equalsIgnoreCase(c.getValor()) || "ENABLED".equalsIgnoreCase(c.getValor()) || "1".equals(c.getValor()))
                .orElse(false);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ejecutarAlArrancarSistema() {
        log.info("🚀 [STARTUP] Disparando Barredora al arrancar el servidor...");
        procesarReglasDeTiempoYGuillotina();
    }

    @Scheduled(cron = "0 0 * * * *")
    public void procesarReglasDeTiempoYGuillotina() {
        log.info("🤖 [ROBOT MAESTRO] Iniciando barrido de solicitudes...");

        try {
            ejecutarSlaYAutoAprobacion();
        } catch (Exception e) {
            log.error("❌ [BARREDORA] Error crítico en Fase 1 (SLA): ", e);
        }

        try {
            ejecutarGuillotinaContable();
        } catch (Exception e) {
            log.error("❌ [BARREDORA] Error crítico en Fase 2 (Guillotina): ", e);
        }

        log.info("🤖 [ROBOT MAESTRO] Barrido finalizado con éxito.");
    }

    // =========================================================================
    // ⏳ FASE 1: ESCALAMIENTO Y AUTO-APROBACIÓN (SOLO ORDINARIAS)
    // =========================================================================
    protected void ejecutarSlaYAutoAprobacion() {
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

        List<SolicitudVacaciones> pendientesShift = solicitudRepo.findByEstatusAndFechaCreacionBefore("Pendiente_Jefe", limiteGraciaShift);

        for (SolicitudVacaciones sol : pendientesShift) {
            try {
                String tipoEmp = sol.getEmpleado() != null && sol.getEmpleado().getTipoEmpleado() != null
                        ? sol.getEmpleado().getTipoEmpleado().toUpperCase() : "SINDICALIZADO";

                if (!tipoEmp.contains("SIND")) {
                    continue; // Administradores (WC/BCI) ignoran SLA, esperan su guillotina
                }

                if (sol.getEsExtemporanea() == null || !sol.getEsExtemporanea()) {
                    if (escalaASupervisor) {
                        sol.setEstatus("Pendiente_Supervisor");
                        sol.setNotasSistema((sol.getNotasSistema() != null ? sol.getNotasSistema() + "\n" : "") + "[" + ahora + "] SISTEMA: Escalamiento automático a Supervisor (Inactividad de " + horasSla + "h).");
                        solicitudRepo.save(sol);
                        log.info("↗️ [BARREDORA] Solicitud Ordinaria #{} escalada a Supervisor.", sol.getId());
                    } else {
                        aprobarSolicitudA_Favor(sol, ahora, "Auto-Aprobación (SLA Expirado para el Shift Leader).");
                    }
                }
            } catch (Exception e) {
                log.error("Error al procesar SLA en Solicitud Ordinaria #{}: {}", sol.getId(), e.getMessage());
            }
        }

        LocalDateTime limiteGraciaSuper = ahora.minusHours(horasSla * 2L);
        List<SolicitudVacaciones> pendientesSuper = solicitudRepo.findByEstatusAndFechaCreacionBefore("Pendiente_Supervisor", limiteGraciaSuper);

        for (SolicitudVacaciones sol : pendientesSuper) {
            try {
                String tipoEmp = sol.getEmpleado() != null && sol.getEmpleado().getTipoEmpleado() != null
                        ? sol.getEmpleado().getTipoEmpleado().toUpperCase() : "SINDICALIZADO";

                if (!tipoEmp.contains("SIND")) {
                    continue; // Administradores (WC/BCI) ignoran SLA, esperan su guillotina
                }

                if (sol.getEsExtemporanea() == null || !sol.getEsExtemporanea()) {
                    aprobarSolicitudA_Favor(sol, ahora, "Auto-Aprobación (SLA Expirado para el Supervisor).");
                }
            } catch (Exception e) {
                log.error("Error al procesar SLA (Super) en Solicitud #{}: {}", sol.getId(), e.getMessage());
            }
        }
    }

    private void aprobarSolicitudA_Favor(SolicitudVacaciones sol, LocalDateTime ahora, String razon) {
        sol.setEstatus("Aprobado");
        sol.setComentarioJefe("[SISTEMA] " + razon);
        sol.setFechaAprobacionJefe(ahora);
        sol.setAprobadoPor(null);
        sol.setNotasSistema((sol.getNotasSistema() != null ? sol.getNotasSistema() + "\n" : "") + "[" + ahora + "] SISTEMA: " + razon);

        String tipoSol = sol.getTipoSolicitud() != null ? sol.getTipoSolicitud().trim().toUpperCase() : "";
        if (tipoSol.equals("V") || tipoSol.contains("VACACION")) {
            Empleado emp = sol.getEmpleado();
            BigDecimal saldo = emp.getSaldoVacacionesActual() != null ? emp.getSaldoVacacionesActual() : BigDecimal.ZERO;
            int dias = sol.getDiasTotalesCalculados() != null ? sol.getDiasTotalesCalculados() : 0;
            emp.setSaldoVacacionesActual(saldo.subtract(BigDecimal.valueOf(dias)));
            empleadoRepository.save(emp);
        }
        SolicitudVacaciones guardada = solicitudRepo.save(sol);
        vacacionesService.ejecutarEfectoDominoCapacidad(guardada);
        log.info("✅ [BARREDORA] Solicitud Ordinaria #{} Auto-Aprobada y Efecto Dominó ejecutado.", sol.getId());
    }

    // =========================================================================
    // 🪓 FASE 2: LA GUILLOTINA CONTABLE DALTÓNICA
    // =========================================================================
    protected void ejecutarGuillotinaContable() {
        LocalDateTime ahora = LocalDateTime.now();
        int permisosMatados = 0;
        int vacacionesMatadas = 0;

        // 🧪 TUBERÍA A: LAS INCIDENCIAS (HO, C, PT, TXT, etc.)
        // ¡Magia! La BD hace el filtro y nos entrega a los empleados pre-cargados.
        List<SolicitudPermiso> permisosPendientes = permisoRepo.findByEstatus("PENDIENTE");

        for (SolicitudPermiso permiso : permisosPendientes) {
            try {
                if (permiso.getEmpleado() == null || permiso.getFechaIncidencia() == null) continue;

                LocalDateTime momentoDelCierre = calcularMomentoDeCierre(
                        permiso.getEmpleado().getTipoEmpleado(),
                        permiso.getFechaIncidencia()
                );

                if (ahora.isAfter(momentoDelCierre)) {
                    permiso.setEstatus("CANCELADO");
                    String notasViejas = permiso.getJustificacionSupervisor() != null ? permiso.getJustificacionSupervisor() + " | " : "";
                    permiso.setJustificacionSupervisor(notasViejas + "[GUILLOTINA AUTOMÁTICA]: Cancelada por cierre oficial de periodo contable.");
                    permisoRepo.save(permiso);
                    permisosMatados++;
                }
            } catch (Exception e) {
                log.error("Error al guillotinar el Permiso #{}: {}", permiso.getId(), e.getMessage());
            }
        }

        // 🏖️ TUBERÍA B: LAS VACACIONES (Ordinarias atascadas y Extemporáneas)
        List<SolicitudVacaciones> vacacionesPendientes = solicitudRepo.findByEstatusIn(List.of("Pendiente_Jefe", "Pendiente_Supervisor"));

        for (SolicitudVacaciones vac : vacacionesPendientes) {
            try {
                if (vac.getEmpleado() == null || vac.getFechaInicio() == null) continue;

                LocalDateTime momentoDelCierre = calcularMomentoDeCierre(
                        vac.getEmpleado().getTipoEmpleado(),
                        vac.getFechaInicio()
                );

                if (ahora.isAfter(momentoDelCierre)) {
                    vac.setEstatus("CANCELADO");
                    vac.setComentarioSupervisor("[GUILLOTINA AUTOMÁTICA]: Solicitud abortada por cierre irrevocable de periodo contable.");
                    vac.setNotasSistema((vac.getNotasSistema() != null ? vac.getNotasSistema() + "\n" : "") + "[" + ahora + "] SISTEMA: Acción de Guillotina ejecutada.");
                    solicitudRepo.save(vac);
                    vacacionesMatadas++;
                }
            } catch (Exception e) {
                log.error("Error al guillotinar las Vacaciones #{}: {}", vac.getId(), e.getMessage());
            }
        }

        if (permisosMatados > 0 || vacacionesMatadas > 0) {
            log.info("💀 [GUILLOTINA] Se purgaron {} Permisos Operativos y {} Vacaciones estancadas tras el cierre de nómina.", permisosMatados, vacacionesMatadas);
        }
    }

    private LocalDateTime calcularMomentoDeCierre(String tipoEmpleadoDb, LocalDate fechaAfectacion) {
        String tipoKey = (tipoEmpleadoDb != null && tipoEmpleadoDb.toUpperCase().contains("SIND")) ? "SINDICALIZADO" : "ADMINISTRATIVO";
        ConfiguracionCorteNomina corteNomina = configCorteRepo.findByTipoEmpleado(tipoKey).orElse(null);

        int diaCorte = (tipoKey.equals("SINDICALIZADO")) ? 2 : 3;
        LocalTime horaCorte = (corteNomina != null && corteNomina.getHoraCorte() != null) ? corteNomina.getHoraCorte() : LocalTime.of(14, 0);

        if (corteNomina != null && corteNomina.getDiaCorte() != null) {
            diaCorte = corteNomina.getDiaCorte();
        }

        LocalDate lunesSiguiente = fechaAfectacion.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        LocalDate fechaLimite = lunesSiguiente.plusDays(diaCorte - 1);
        return LocalDateTime.of(fechaLimite, horaCorte);
    }

    // =========================================================================
    // ⚙️ FASE 3: AUTO-ROLLOVER DE CONFIGURACIONES VIP (Sábados 8:00 AM)
    // =========================================================================
    @Scheduled(cron = "0 0 8 * * SAT")
    public void autoRolloverConfiguraciones() {
        log.info("🤖 [ROBOT MAESTRO] Ejecutando verificación de Auto-Rollover (Sábados 8 AM)...");
        LocalDate hoy = LocalDate.now();
        LocalDate proximoLunes = hoy.plusDays(2);
        LocalDate proximoMes = hoy.plusMonths(1);
        int mesDestino = proximoMes.getMonthValue();
        int anioDestino = proximoMes.getYear();

        java.util.Map<String, LocalDate> semanaVip = vacacionesService.calcularSemanaVIP(mesDestino, anioDestino);

        if (proximoLunes.equals(semanaVip.get("inicio"))) {
            log.info("🤖 [ROBOT MAESTRO] ¡Es el sábado previo a la Semana VIP! Verificando configuraciones de Jefes...");

            List<Integer> supervisoresActivos = empleadoRepository.findAll().stream()
                    .filter(e -> "SUPERVISOR".equalsIgnoreCase(e.getRolJerarquico() != null ? e.getRolJerarquico().trim() : ""))
                    .map(Empleado::getNomina)
                    .distinct()
                    .toList();

            int autoConfigurados = 0;
            for (Integer supervisorNomina : supervisoresActivos) {
                List<com.hrms.vacaciones.model.HistoricoConfiguracionArea> historico = vacacionesService.obtenerHistoricoConfiguracion(supervisorNomina);
                boolean yaConfigurado = historico.stream().anyMatch(h -> h.getMes() == mesDestino && h.getAnio() == anioDestino);

                if (!yaConfigurado) {
                    LocalDate ultimoDiaMesDestino = LocalDate.of(anioDestino, mesDestino, 1).with(TemporalAdjusters.lastDayOfMonth());
                    try {
                        vacacionesService.guardarConfiguracionArea(supervisorNomina, mesDestino, anioDestino, ultimoDiaMesDestino.toString());
                        autoConfigurados++;
                        log.info("⚙️ Auto-Configuración (Clonación) aplicada para Supervisor #{}", supervisorNomina);
                    } catch (Exception e) {
                        log.error("❌ Error al auto-configurar Supervisor #{}: {}", supervisorNomina, e.getMessage());
                    }
                }
            }
            log.info("🤖 [ROBOT MAESTRO] Auto-Rollover completado. Supervisores auto-configurados por inactividad: {}", autoConfigurados);
        }
    }
}
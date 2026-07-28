package com.hrms.vacaciones.service;

import com.hrms.vacaciones.model.ConfiguracionCorteNomina;
import com.hrms.vacaciones.model.SolicitudPermiso;
import com.hrms.vacaciones.model.SolicitudVacaciones;
import com.hrms.vacaciones.repository.ConfiguracionCorteNominaRepository;
import com.hrms.vacaciones.repository.SolicitudPermisoRepository;
import com.hrms.vacaciones.repository.SolicitudVacacionesRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@Slf4j
@Service
public class GuillotinaContableJob {

    @Autowired
    private SolicitudPermisoRepository permisoRepo;

    @Autowired
    private SolicitudVacacionesRepository vacacionesRepo;

    @Autowired
    private ConfiguracionCorteNominaRepository configCorteRepo;

    /**
     * 🎯 EL VERDADERO SALVAVIDAS CONTABLE.
     * Este método corre de forma automatizada en el servidor cada hora.
     * No necesita que ningún usuario, jefe o administrador inicie sesión.
     */
    @Scheduled(cron = "0 0 * * * *") // Se ejecuta al segundo 0 de cada hora, las 24 horas del día
    @Transactional
    public void ejecutarBarredoraAutomaticaTorreControl() {
        LocalDateTime ahora = LocalDateTime.now();
        log.info("🤖 [CRON JOB] Guillotina Automática escaneando el sistema: {}", ahora);

        // =========================================================================
        // 🧪 TUBERÍA A: LAS INCIDENCIAS INDIVIDUALES Y TXT (solicitudes_permisos)
        // =========================================================================
        List<SolicitudPermiso> permisosPendientes = permisoRepo.findAll().stream()
                .filter(p -> "PENDIENTE".equalsIgnoreCase(p.getEstatus()))
                .toList();

        int permisosMatados = 0;

        for (SolicitudPermiso permiso : permisosPendientes) {
            // Buscamos si el empleado es Sindicalizado o Administrativo para aplicar su regla justa
            String tipoEmpleado = permiso.getEmpleado().getTipoEmpleado();
            String tipoKey = (tipoEmpleado != null && tipoEmpleado.toUpperCase().contains("SIND"))
                    ? "SINDICALIZADO" : "ADMINISTRATIVO";

            // Jalamos la hora y día de corte que guardaste en la Torre de Control
            ConfiguracionCorteNomina corteNomina = configCorteRepo.findByTipoEmpleado(tipoKey).orElse(null);

            int diaCorte = (tipoKey.equals("SINDICALIZADO")) ? 2 : 3; // Martes (2) o Miércoles (3) por defecto
            LocalTime horaCorte = (corteNomina != null && corteNomina.getHoraCorte() != null)
                    ? corteNomina.getHoraCorte() : LocalTime.of(14, 0); // Las 2:00 PM por defecto

            if (corteNomina != null && corteNomina.getDiaCorte() != null) {
                diaCorte = corteNomina.getDiaCorte();
            }

            // Calculamos matemáticamente el momento exacto en que debió morir la solicitud:
            // Lunes de la siguiente semana de cuando ocurrió la falta
            LocalDate lunesSiguiente = permiso.getFechaIncidencia().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            LocalDate fechaLimite = lunesSiguiente.plusDays(diaCorte - 1);
            LocalDateTime momentoDelCierre = LocalDateTime.of(fechaLimite, horaCorte);

            // Si el reloj del servidor ya superó ese momento, la guillotina cae de inmediato
            if (ahora.isAfter(momentoDelCierre)) {
                permiso.setEstatus("RECHAZADO_SISTEMA");
                permiso.setJustificacionSupervisor(
                        "[GUILLOTINA AUTOMÁTICA]: Solicitud cancelada por el sistema. El supervisor no autorizó " +
                                "la incidencia a tiempo y el periodo contable asignado cerró de forma irrevocable el " +
                                fechaLimite + " a las " + horaCorte + " hrs."
                );
                permisoRepo.save(permiso);
                permisosMatados++;
            }
        }

        // =========================================================================
        // 🏖️ TUBERÍA B: LAS VACACIONES Y PAROS TÉCNICOS (solicitudes_vacaciones)
        // =========================================================================
        List<SolicitudVacaciones> vacacionesPendientes = vacacionesRepo.findAll().stream()
                .filter(v -> "PENDIENTE_JEFE".equalsIgnoreCase(v.getEstatus()) || "PENDIENTE_SUPERVISOR".equalsIgnoreCase(v.getEstatus()))
                .toList();

        int vacacionesMatadas = 0;

        for (SolicitudVacaciones vac : vacacionesPendientes) {
            String tipoEmpleado = vac.getEmpleado().getTipoEmpleado();
            String tipoKey = (tipoEmpleado != null && tipoEmpleado.toUpperCase().contains("SIND"))
                    ? "SINDICALIZADO" : "ADMINISTRATIVO";

            ConfiguracionCorteNomina corteNomina = configCorteRepo.findByTipoEmpleado(tipoKey).orElse(null);

            int diaCorte = (tipoKey.equals("SINDICALIZADO")) ? 2 : 3;
            LocalTime horaCorte = (corteNomina != null && corteNomina.getHoraCorte() != null)
                    ? corteNomina.getHoraCorte() : LocalTime.of(14, 0);

            if (corteNomina != null && corteNomina.getDiaCorte() != null) {
                diaCorte = corteNomina.getDiaCorte();
            }

            LocalDate lunesSiguiente = vac.getFechaInicio().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            LocalDate fechaLimite = lunesSiguiente.plusDays(diaCorte - 1);
            LocalDateTime momentoDelCierre = LocalDateTime.of(fechaLimite, horaCorte);

            if (ahora.isAfter(momentoDelCierre)) {
                vac.setEstatus("CANCELADA_CORTENOMINA");
                // 💡 Aquí tu motor ya tiene la lógica de devolverle los días al saldo del empleado si es necesario
                vacacionesRepo.save(vac);
                vacacionesMatadas++;
            }
        }

        if (permisosMatados > 0 || vacacionesMatadas > 0) {
            log.info("💀 [GUILLOTINA] Barredora contable exitosa: Se purgaron {} TXT/Permisos y {} Vacaciones estancadas.",
                    permisosMatados, vacacionesMatadas);
        }
    }
}
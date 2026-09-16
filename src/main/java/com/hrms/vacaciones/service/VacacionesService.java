package com.hrms.vacaciones.service;

import com.hrms.vacaciones.dto.SolicitudVacacionesRequest;
import com.hrms.vacaciones.dto.RespuestaJefeRequest;
import com.hrms.vacaciones.model.*;
import com.hrms.vacaciones.repository.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.math.BigDecimal;

import com.hrms.vacaciones.model.GrupoProceso;
import com.hrms.vacaciones.model.CentroCosto;

@Service
public class VacacionesService {

    private final EmpleadoRepository empleadoRepository;
    private final SolicitudVacacionesRepository solicitudVacacionesRepository;
    private final SolicitudPermisoRepository solicitudPermisoRepository;
    private final TurnoRepository turnoRepository;
    private final ConfiguracionCorteNominaRepository configuracionCorteNominaRepository;
    private final JefeWorkCenterRepository jefeWorkCenterRepository;
    private final ConfiguracionSistemaRepository configuracionSistemaRepository;
    private final AuditoriaSaldoRepository auditoriaSaldoRepository;
    private final CapacidadVacacionesMesRepository capacidadRepo;
    private final HistoricoConfiguracionAreaRepository historicoRepo;
    private final WorkCenterRepository workCenterRepository;
    private final DiasFestivosRepository diasFestivosRepository;
    private final AduanaService aduanaService;
    private final CalendarioService calendarioService;
    private final GrupoProcesoRepository grupoProcesoRepository;
    private final CentroCostoRepository centroCostoRepository;
    private final PeriodoInhabilRepository periodoInhabilRepository;
    private final BoletoVipRepository boletoVipRepository;

    @Autowired
    public VacacionesService(
            EmpleadoRepository empleadoRepository,
            SolicitudVacacionesRepository solicitudVacacionesRepository,
            SolicitudPermisoRepository solicitudPermisoRepository,
            TurnoRepository turnoRepository,
            ConfiguracionCorteNominaRepository configuracionCorteNominaRepository,
            JefeWorkCenterRepository jefeWorkCenterRepository,
            ConfiguracionSistemaRepository configuracionSistemaRepository,
            AuditoriaSaldoRepository auditoriaSaldoRepository,
            CapacidadVacacionesMesRepository capacidadRepo,
            HistoricoConfiguracionAreaRepository historicoRepo,
            WorkCenterRepository workCenterRepository,
            DiasFestivosRepository diasFestivosRepository,
            AduanaService aduanaService,
            CalendarioService calendarioService,
            CentroCostoRepository centroCostoRepository,
            PeriodoInhabilRepository periodoInhabilRepository,
            GrupoProcesoRepository grupoProcesoRepository,
            BoletoVipRepository boletoVipRepository) {

        this.empleadoRepository = empleadoRepository;
        this.solicitudVacacionesRepository = solicitudVacacionesRepository;
        this.solicitudPermisoRepository = solicitudPermisoRepository;
        this.turnoRepository = turnoRepository;
        this.configuracionCorteNominaRepository = configuracionCorteNominaRepository;
        this.jefeWorkCenterRepository = jefeWorkCenterRepository;
        this.configuracionSistemaRepository = configuracionSistemaRepository;
        this.auditoriaSaldoRepository = auditoriaSaldoRepository;
        this.capacidadRepo = capacidadRepo;
        this.historicoRepo = historicoRepo;
        this.workCenterRepository = workCenterRepository;
        this.diasFestivosRepository = diasFestivosRepository;
        this.aduanaService = aduanaService;
        this.calendarioService = calendarioService;
        this.grupoProcesoRepository = grupoProcesoRepository;
        this.centroCostoRepository = centroCostoRepository;
        this.periodoInhabilRepository = periodoInhabilRepository;
        this.boletoVipRepository = boletoVipRepository;
    }

    private String obtenerDiaSemanaEspNormalizado(LocalDate fecha) {
        return switch (fecha.getDayOfWeek()) {
            case MONDAY -> "LUNES";
            case TUESDAY -> "MARTES";
            case WEDNESDAY -> "MIERCOLES";
            case THURSDAY -> "JUEVES";
            case FRIDAY -> "VIERNES";
            case SATURDAY -> "SABADO";
            case SUNDAY -> "DOMINGO";
        };
    }

    private String sanitizarDiasDescanso(String diasDescanso) {
        if (diasDescanso == null || diasDescanso.isBlank()) {
            return "DOMINGO";
        }
        return diasDescanso.toUpperCase()
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U")
                .replaceAll("\\s+", "");
    }

    public boolean esAdminORH(Integer nomina) {
        if (java.util.List.of(1555, 1302, 1550).contains(nomina)) {
            return true;
        }
        return empleadoRepository.findById(nomina)
                .map(emp -> {
                    String rol = emp.getRolJerarquico();
                    return rol != null && (rol.equalsIgnoreCase("NOMINAS")
                            || rol.equalsIgnoreCase("RH")
                            || rol.equalsIgnoreCase("ADMIN"));
                }).orElse(false);
    }

    public boolean esDesarrolladorDios(Integer nomina) {
        return nomina == 1555;
    }

    @Transactional
    public void modificarSaldoManual(Integer nominaOperador, Integer nominaEmpleadoAfectado,
                                     String accionTipo, BigDecimal diasAjuste, String justificacion,
                                     Integer solicitudId, String fechasDescuentoStr) {

        if (!esAdminORH(nominaOperador)) {
            throw new SecurityException("Acceso Denegado: No tienes permisos de Administrador para alterar saldos.");
        }

        if (justificacion == null || justificacion.trim().isEmpty() || justificacion.trim().length() < 10) {
            throw new IllegalArgumentException("¡EL TAPA-BOCAS HA ACTUADO! Es obligatorio ingresar una justificación detallada (mínimo 10 letras).");
        }

        Empleado empleado = empleadoRepository.findById(nominaEmpleadoAfectado)
                .orElseThrow(() -> new RuntimeException("Empleado no encontrado."));
        Empleado operador = empleadoRepository.findById(nominaOperador).get();

        BigDecimal saldoActual = empleado.getSaldoVacacionesActual() != null ? empleado.getSaldoVacacionesActual() : BigDecimal.ZERO;
        String notaAuditoria = "";

        if ("REEMBOLSO".equalsIgnoreCase(accionTipo)) {
            if (solicitudId == null || solicitudId == 0) {
                throw new IllegalArgumentException("Para un reembolso es obligatorio vincular una solicitud aprobada previa.");
            }
            SolicitudVacaciones sol = solicitudVacacionesRepository.findById(solicitudId)
                    .orElseThrow(() -> new RuntimeException("Solicitud de referencia no encontrada."));

            if (!"Aprobado".equalsIgnoreCase(sol.getEstatus())) {
                throw new IllegalArgumentException("Solo se puede reembolsar saldo de solicitudes con estatus 'Aprobado'.");
            }

            empleado.setSaldoVacacionesActual(saldoActual.add(diasAjuste));
            notaAuditoria = "[REEMBOLSO Folio #" + sol.getId() + " | Modificación: +" + diasAjuste + " días]: " + justificacion.trim();

            sol.setDiasTotalesCalculados(Math.max(0, sol.getDiasTotalesCalculados() - diasAjuste.intValue()));
            solicitudVacacionesRepository.save(sol);

        } else if ("DESCUENTO".equalsIgnoreCase(accionTipo)) {
            if (fechasDescuentoStr == null || fechasDescuentoStr.trim().isEmpty()) {
                throw new IllegalArgumentException("Para registrar una falta es obligatorio seleccionar al menos una fecha en el calendario.");
            }

            List<LocalDate> fechas = Arrays.stream(fechasDescuentoStr.split(","))
                    .map(String::trim)
                    .map(LocalDate::parse)
                    .collect(Collectors.toList());

            for (LocalDate fechaEspecifica : fechas) {
                boolean yaTieneRegistro = solicitudVacacionesRepository.findAll().stream()
                        .anyMatch(s -> s.getEmpleado().getNomina().equals(nominaEmpleadoAfectado)
                                && !"Rechazado".equalsIgnoreCase(s.getEstatus())
                                && !"CANCELADO".equalsIgnoreCase(s.getEstatus()) // ✨ Ignoramos las canceladas
                                && !fechaEspecifica.isBefore(s.getFechaInicio())
                                && !fechaEspecifica.isAfter(s.getFechaFin()));

                if (yaTieneRegistro) {
                    throw new IllegalArgumentException("¡Día Ocupado! El colaborador ya cuenta con una solicitud registrada o activa para la fecha: " + fechaEspecifica);
                }
            }

            empleado.setSaldoVacacionesActual(saldoActual.subtract(diasAjuste));
            notaAuditoria = "[RECUPERACIÓN DE FALTAS - Fechas: " + fechasDescuentoStr + " | -" + diasAjuste + " días]: " + justificacion.trim();

            for (LocalDate fechaEspecifica : fechas) {
                SolicitudVacaciones descuentoVisual = SolicitudVacaciones.builder()
                        .empleado(empleado)
                        .creadaPor(operador)
                        .fechaInicio(fechaEspecifica)
                        .fechaFin(fechaEspecifica)
                        .diasTotalesCalculados(1)
                        .tipoSolicitud("Recuperación de Falta")
                        .estatus("Aprobado")
                        .fechaCreacion(LocalDateTime.now())
                        .comentarioJefe(justificacion.trim())
                        .build();
                solicitudVacacionesRepository.save(descuentoVisual);
            }
        }

        empleadoRepository.save(empleado);

        double deltaValor = "REEMBOLSO".equalsIgnoreCase(accionTipo) ? diasAjuste.doubleValue() : -diasAjuste.doubleValue();
        AuditoriaSaldo auditoria = new AuditoriaSaldo(
                nominaEmpleadoAfectado,
                deltaValor,
                notaAuditoria,
                LocalDateTime.now(),
                operador.getNombreCompleto() + " (Nómina " + nominaOperador + ")"
        );
        auditoriaSaldoRepository.save(auditoria);
    }

    public List<AuditoriaSaldo> obtenerHistorialAuditoriaSaldos(Integer nominaEmpleado) {
        return auditoriaSaldoRepository.findByNominaEmpleadoOrderByFechaMovimientoDesc(nominaEmpleado);
    }

    @Transactional
    public void crearSolicitudRecuperacion(Integer nominaSupervisor, Integer nominaEmpleado, LocalDate fechaFalta, String justificacion) {
        Empleado supervisor = empleadoRepository.findById(nominaSupervisor)
                .orElseThrow(() -> new RuntimeException("Supervisor no encontrado."));

        if (!"SUPERVISOR".equalsIgnoreCase(supervisor.getRolJerarquico() != null ? supervisor.getRolJerarquico().trim() : "")) {
            throw new SecurityException("Permiso Denegado: Solo los perfiles con rol 'SUPERVISOR' pueden iniciar recuperaciones de faltas.");
        }

        Empleado empleado = empleadoRepository.findById(nominaEmpleado)
                .orElseThrow(() -> new RuntimeException("El empleado al que intentas aplicar la recuperación no existe."));

        boolean yaTieneRegistro = solicitudVacacionesRepository.findAll().stream()
                .anyMatch(s -> s.getEmpleado().getNomina().equals(nominaEmpleado)
                        && !"Rechazado".equalsIgnoreCase(s.getEstatus())
                        && !"CANCELADO".equalsIgnoreCase(s.getEstatus()) // ✨ Ignoramos las canceladas
                        && !fechaFalta.isBefore(s.getFechaInicio())
                        && !fechaFalta.isAfter(s.getFechaFin()));

        if (yaTieneRegistro) {
            throw new IllegalArgumentException("¡Conflicto Contable! El colaborador ya tiene programada una solicitud activa o vacaciones asignadas para el día: " + fechaFalta);
        }

        LocalDateTime ahora = LocalDateTime.now();
        java.time.temporal.WeekFields weekFields = java.time.temporal.WeekFields.of(java.util.Locale.getDefault());

        String tipoEmpleado = empleado.getTipoEmpleado() != null ? empleado.getTipoEmpleado() : "SINDICALIZADO";
        ConfiguracionCorteNomina configCorte = configuracionCorteNominaRepository.findByTipoEmpleado(tipoEmpleado).orElse(null);

        int weekNo = ahora.get(weekFields.weekOfWeekBasedYear());
        int yearNo = ahora.get(weekFields.weekBasedYear());

        if (configCorte != null) {
            java.time.DayOfWeek diaCorte = java.time.DayOfWeek.of(configCorte.getDiaCorte());
            LocalDateTime limiteGuillotina = ahora.with(java.time.DayOfWeek.MONDAY)
                    .plusDays(diaCorte.getValue() - 1)
                    .toLocalDate()
                    .atTime(configCorte.getHoraCorte());

            if (ahora.isAfter(limiteGuillotina)) {
                LocalDateTime siguienteSemana = ahora.plusWeeks(1);
                weekNo = siguienteSemana.get(weekFields.weekOfWeekBasedYear());
                yearNo = siguienteSemana.get(weekFields.weekBasedYear());
            }
        }

        String semanaDestinoCalculada = weekNo + "/" + yearNo;

        SolicitudVacaciones recuperacion = SolicitudVacaciones.builder()
                .empleado(empleado)
                .creadaPor(supervisor)
                .fechaInicio(fechaFalta)
                .fechaFin(fechaFalta)
                .diasTotalesCalculados(1)
                .tipoSolicitud("Recuperación de Falta")
                .estatus("Pendiente_Nomina")
                .fechaCreacion(ahora)
                .loteAutorizacionMasiva(semanaDestinoCalculada)
                .comentarioJefe("[Supervisor " + supervisor.getNombreCompleto() + " solicita justificar falta del " + fechaFalta + "]: " + justificacion)
                .build();

        solicitudVacacionesRepository.save(recuperacion);
    }

    public List<SolicitudVacaciones> obtenerPendientesDeNomina() {
        // ✨ RESTAURADO: Solo pendientes reales, nada de históricos
        return solicitudVacacionesRepository.findByEstatusIn(List.of("Pendiente_Nomina"));
    }

    public List<SolicitudVacaciones> obtenerHistorialRecuperacionesGlobal() {
        // ✨ RESTAURADO: Vuelve a jalar Aprobado y Rechazado
        return solicitudVacacionesRepository.findAll().stream()
                .filter(s -> "Recuperación de Falta".equalsIgnoreCase(s.getTipoSolicitud()))
                .filter(s -> List.of("Aprobado", "Rechazado").contains(s.getEstatus()))
                .sorted((a, b) -> b.getFechaCreacion().compareTo(a.getFechaCreacion()))
                .collect(Collectors.toList());
    }

    public List<SolicitudVacaciones> obtenerDepuracionesGlobales() {
        return solicitudVacacionesRepository.findAll().stream()
                .filter(s -> "Depuración Vacaciones Vencidas".equalsIgnoreCase(s.getTipoSolicitud()))
                .sorted((a, b) -> b.getFechaCreacion().compareTo(a.getFechaCreacion()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void resolverRecuperacionNomina(Integer solicitudId, boolean aprobado, Integer nominaLogueada, String comentarioRechazo) {
        SolicitudVacaciones solicitud = solicitudVacacionesRepository.findById(solicitudId)
                .orElseThrow(() -> new RuntimeException("Error: No se encontró la solicitud de recuperación #" + solicitudId));

        solicitud.setEstatus(aprobado ? "Aprobado" : "Rechazado");

        if (comentarioRechazo != null && !comentarioRechazo.trim().isEmpty()) {
            solicitud.setComentarioSupervisor(comentarioRechazo.trim());
        }

        if (aprobado) {
            Empleado emp = solicitud.getEmpleado();
            String comentario = solicitud.getComentarioJefe() != null ? solicitud.getComentarioJefe() : "";

            if (comentario.contains("[TXT]") || comentario.contains("[HO]") ||
                    comentario.contains("[C]") || comentario.contains("[P]") || comentario.contains("[TET]")) {
                System.out.println("NÓMINA AUDIT: Solicitud #" + solicitudId + " aprobada como incidence justificada de piso. Saldo vacacional intacto.");
            } else {
                if (emp.getSaldoVacacionesActual() != null) {
                    emp.setSaldoVacacionesActual(emp.getSaldoVacacionesActual().subtract(BigDecimal.ONE));
                    empleadoRepository.save(emp);
                }
            }
        }
        solicitudVacacionesRepository.save(solicitud);
    }

    @Transactional
    public SolicitudVacaciones crearSolicitud(SolicitudVacacionesRequest request) {
        long rechazosEncontrados = solicitudVacacionesRepository.contarRechazosPrevios(
                request.nominaEmpleado(), request.fechaInicio(), request.fechaFin());

        if (rechazosEncontrados > 0) {
            throw new IllegalArgumentException("No puedes solicitar este periodo porque ya tienes una solicitud previa rechazada en estas fechas.");
        }

        var solicitudEmpalmada = solicitudVacacionesRepository.encontrarSolicitudEmpalmada(
                request.nominaEmpleado(), request.fechaInicio(), request.fechaFin());

        if (solicitudEmpalmada.isPresent()) {
            throw new RuntimeException("Ya tienes una solicitud ingresada que se cruza con estas fechas.");
        }

        Empleado empleado = empleadoRepository.findById(request.nominaEmpleado())
                .orElseThrow(() -> new RuntimeException("Empleado no encontrado"));

        Turno turnoAsignado = turnoRepository.findById(request.rolDescansoId())
                .orElseThrow(() -> new IllegalArgumentException("El turno seleccionado no existe"));

        Integer diasCalculados = calcularDiasEfectivos(request.fechaInicio(), request.fechaFin(), turnoAsignado);

        if (diasCalculados == 0) {
            throw new RuntimeException("Las fechas seleccionadas no contienen días hábiles para tu jornada de trabajo.");
        }

        String tipoSol = request.tipoSolicitud() != null ? request.tipoSolicitud().trim() : "Vacaciones";
        boolean esVacacion = "V".equalsIgnoreCase(tipoSol) || tipoSol.toUpperCase().contains("VACACION");

        if (esVacacion) {
            BigDecimal saldoTotalDisponible = calcularSaldoTotalDisponible(empleado);
            if (BigDecimal.valueOf(diasCalculados).compareTo(saldoTotalDisponible) > 0) {
                throw new RuntimeException("Rechazado: La solicitud de " + diasCalculados + " días excede tu saldo total disponible a disfrutar (" + saldoTotalDisponible + " días).");
            }
        }

        if (request.jefeAutorizadorNomina() == null) {
            if (!"SINDICALIZADO".equalsIgnoreCase(empleado.getTipoEmpleado())) {
                if (request.comentarios() == null || request.comentarios().trim().isEmpty() || request.comentarios().trim().length() < 10) {
                    throw new IllegalArgumentException("¡Candado de Evidencia! Al no contar con un jefe directo asignado en tu ficha, es estrictamente obligatorio escribir una justificación o referencia de correo (Mínimo 10 letras) como evidencia.");
                }
            } else {
                throw new IllegalArgumentException("Error Operativo: No se seleccionó un jefe autorizador para la línea de producción.");
            }
        }

        if ("SINDICALIZADO".equalsIgnoreCase(empleado.getTipoEmpleado()) && esVacacion) {
            if (empleado.getWorkCenter() == null || empleado.getWorkCenter().getSupervisorNomina() == null) {
                throw new RuntimeException("Error: No tienes un Work Center o Supervisor asignado. Acude con Recursos Humanos para regularizar tu perfil.");
            }

            int mesSol = request.fechaInicio().getMonthValue();
            int anioSol = request.fechaInicio().getYear();
            Integer supervisorNomina = empleado.getWorkCenter().getSupervisorNomina();

            // ✨ NUEVO ESCUDO: BLACKOUT DATES (Periodos Inhábiles)
            List<PeriodoInhabil> bloqueos = periodoInhabilRepository.findBySupervisorNominaOrderByFechaInicioDesc(supervisorNomina);
            for (PeriodoInhabil bloqueo : bloqueos) {
                // Lógica de intersección de fechas:
                // (InicioSolicitud <= FinBloqueo) Y (FinSolicitud >= InicioBloqueo)
                if (!request.fechaInicio().isAfter(bloqueo.getFechaFin()) && !request.fechaFin().isBefore(bloqueo.getFechaInicio())) {
                    throw new RuntimeException("🛑 ¡Fechas Bloqueadas! No puedes pedir vacaciones en estos días porque tu área completa estará en: '"
                            + bloqueo.getMotivo() + "' (Del " + bloqueo.getFechaInicio() + " al " + bloqueo.getFechaFin() + ").");
                }
            }

            // ✨ 1. CERO MOCKS - LECTURA DIRECTA DE LA BOLSA GLOBAL DEL SUPERVISOR
            CapacidadVacacionesMes configLinea = capacidadRepo
                    .findBySupervisorNominaAndMesAndAnioAndTurno_Id(supervisorNomina, mesSol, anioSol, turnoAsignado.getId())
                    .orElseThrow(() -> new RuntimeException("El periodo vacacional para tu turno en este mes aún no ha sido configurado ni abierto por tu Supervisor."));

            LocalDate hoy = LocalDate.now();
            boolean esSemanaVip = calendarioService.esSemanaAperturaVip(hoy);

            // 1. Obtenemos el saldo en vivo
            BigDecimal saldoDevengadoReal = empleado.getSaldoVacacionesActual() != null ? empleado.getSaldoVacacionesActual() : BigDecimal.ZERO;

            // 2. Leemos el Boleto VIP congelado de la base de datos
            java.util.Optional<BoletoVip> miBoleto = boletoVipRepository.findByNominaEmpleadoAndMesAndAnio(empleado.getNomina(), mesSol, anioSol);
            java.time.DayOfWeek diaQuintil = null;

            if (miBoleto.isPresent()) {
                switch(miBoleto.get().getDiaAsignado()) {
                    case "LUNES": diaQuintil = java.time.DayOfWeek.MONDAY; break;
                    case "MARTES": diaQuintil = java.time.DayOfWeek.TUESDAY; break;
                    case "MIÉRCOLES": diaQuintil = java.time.DayOfWeek.WEDNESDAY; break;
                    case "JUEVES": diaQuintil = java.time.DayOfWeek.THURSDAY; break;
                    case "VIERNES": diaQuintil = java.time.DayOfWeek.FRIDAY; break;
                }
            }

            // 3. La Aduana dicta sentencia final
            boolean accesoPermitido = aduanaService.puedeSolicitarVacaciones(
                    saldoDevengadoReal.doubleValue(),
                    hoy,
                    diaQuintil,
                    esSemanaVip
            );

            if (!accesoPermitido) {
                if (saldoDevengadoReal.compareTo(BigDecimal.ZERO) < 0) {
                    throw new RuntimeException("Acceso Bloqueado: Tu saldo de vacaciones es negativo. Debes gestionar cualquier permiso directamente con tu Jefe Directo.");
                } else if (esSemanaVip) {
                    throw new RuntimeException("Semana de Apertura VIP: Tu saldo actual y tu posición en el ranking te asignan acceso exclusivamente el día " + aduanaService.obtenerNombreDiaEspanol(diaQuintil) + " de esta semana. Regresa en tu día asignado.");
                } else {
                    throw new RuntimeException("El calendario de vacaciones se encuentra cerrado. El registro abre durante la última semana del mes.");
                }
            }

            if (request.fechaFin().isAfter(configLinea.getFechaLimiteRegistro())) {
                throw new RuntimeException("Error: Tu Shift Leader/Supervisor bloqueó el registro para este mes a partir del día: "
                        + configLinea.getFechaLimiteRegistro());
            }

            // Para contar cuántos del área de este jefe están de vacaciones en este turno,
            // triangulamos rápido sus WCs asignados
            List<WorkCenter> lineasArea = obtenerWorkCentersPorJefe(supervisorNomina);
            List<Integer> wcsDelArea = lineasArea.stream().map(WorkCenter::getId).collect(Collectors.toList());
            if (wcsDelArea.isEmpty()) wcsDelArea.add(empleado.getWorkCenter().getId());

            // ✨ BUSCAMOS LA BOLSA HÍBRIDA DEL OPERADOR ANTES DE EVALUAR (OPTIMIZACIÓN DE MEMORIA)
            GrupoProceso grupoAfectado = null;
            List<GrupoProceso> gruposDelJefe = obtenerGruposProcesoPorJefe(supervisorNomina);

            for (GrupoProceso g : gruposDelJefe) {
                if ("CC".equalsIgnoreCase(g.getTipoAgrupacion()) && empleado.getCentroCosto() != null) {
                    if (g.getCentrosCosto().stream().anyMatch(cc -> cc.getId().equals(empleado.getCentroCosto().getId()))) {
                        grupoAfectado = g; break;
                    }
                } else if ("WC".equalsIgnoreCase(g.getTipoAgrupacion()) && empleado.getWorkCenter() != null) {
                    if (g.getWorkCenters().stream().anyMatch(wc -> wc.getId().equals(empleado.getWorkCenter().getId()))) {
                        grupoAfectado = g; break;
                    }
                }
            }

            // --- INICIO DE LA NUEVA ADUANA HÍBRIDA ---
            LocalDate diaCursor = request.fechaInicio();
            while (!diaCursor.isAfter(request.fechaFin())) {
                final LocalDate fechaEvaluada = diaCursor;

                if (grupoAfectado != null && "WC".equalsIgnoreCase(grupoAfectado.getTipoAgrupacion())) {
                    // 🛡️ MODO WC: EL GRUPO MACRO DICTA LAS REGLAS DE TURNO Y DÍA PARA ESTAS LÍNEAS
                    List<Integer> wcIdsDelGrupo = grupoAfectado.getWorkCenters().stream().map(WorkCenter::getId).collect(Collectors.toList());

                    // Filtramos en caliente las vacaciones vivas de este bloque
                    List<SolicitudVacaciones> vivasDelGrupo = solicitudVacacionesRepository.findAll().stream()
                            .filter(s -> !List.of("RECHAZADO", "CANCELADO").contains(s.getEstatus().toUpperCase()))
                            .filter(s -> s.getTipoSolicitud() != null && (s.getTipoSolicitud().toUpperCase().contains("VACACION") || s.getTipoSolicitud().trim().equalsIgnoreCase("V")))
                            .filter(s -> s.getEmpleado().getWorkCenter() != null && wcIdsDelGrupo.contains(s.getEmpleado().getWorkCenter().getId()))
                            .filter(s -> !fechaEvaluada.isBefore(s.getFechaInicio()) && !fechaEvaluada.isAfter(s.getFechaFin()))
                            .collect(Collectors.toList());

                    long ocupadosEnElDia = vivasDelGrupo.size();
                    if (ocupadosEnElDia >= grupoAfectado.getCupoMaximo()) {
                        throw new RuntimeException("🛑 Bolsa Agotada: El límite máximo de " + grupoAfectado.getCupoMaximo()
                                + " lugares para tu grupo ('" + grupoAfectado.getNombre() + "') ha sido alcanzado para el día " + fechaEvaluada + ".");
                    }

                    long ocupadosEnElTurno = vivasDelGrupo.stream()
                            .filter(s -> s.getTurno() != null && s.getTurno().getId().equals(turnoAsignado.getId()))
                            .count();

                    if (ocupadosEnElTurno >= grupoAfectado.getCupoMaximoTurno()) {
                        throw new RuntimeException("🚨 Capacidad de Turno Agotada: Ya se alcanzó el máximo de " + grupoAfectado.getCupoMaximoTurno()
                                + " persona(s) por turno en el grupo '" + grupoAfectado.getNombre() + "' para el día " + fechaEvaluada + ".");
                    }

                } else {
                    // 🛡️ MODO CC (O SIN GRUPO): REGLA INDIVIDUAL DE LÍNEA + BOLSA CC
                    long ocupadosMismoWcTurno = solicitudVacacionesRepository.contarOcupadosPorLineaYTurno(
                            empleado.getWorkCenter().getId(), turnoAsignado.getId(), diaCursor
                    );
                    int cupoMaximoWc = (empleado.getWorkCenter().getCupoConcurrenteTurno() != null && empleado.getWorkCenter().getCupoConcurrenteTurno() > 0)
                            ? empleado.getWorkCenter().getCupoConcurrenteTurno() : 1;

                    if (ocupadosMismoWcTurno >= cupoMaximoWc) {
                        throw new RuntimeException("🚨 Capacidad de Línea Agotada: Ya se alcanzó el cupo máximo de " + cupoMaximoWc
                                + " persona(s) autorizada(s) para tu línea (WC " + empleado.getWorkCenter().getId() + ") en el turno '"
                                + turnoAsignado.getNombreTurno() + "' para el día " + diaCursor + ".");
                    }

                    if (grupoAfectado != null && "CC".equalsIgnoreCase(grupoAfectado.getTipoAgrupacion())) {
                        List<Integer> ccIdsDelGrupo = grupoAfectado.getCentrosCosto().stream().map(CentroCosto::getId).collect(Collectors.toList());
                        long ocupadosEnElMacroGrupo = solicitudVacacionesRepository.countVacacionesPorGrupoYFecha(ccIdsDelGrupo, fechaEvaluada);

                        if (ocupadosEnElMacroGrupo >= grupoAfectado.getCupoMaximo()) {
                            throw new RuntimeException("🛑 Bolsa Agotada: El límite máximo de " + grupoAfectado.getCupoMaximo()
                                    + " lugares para tu grupo ('" + grupoAfectado.getNombre() + "') ha sido alcanzado para el día " + fechaEvaluada + ".");
                        }
                    }
                }

                diaCursor = diaCursor.plusDays(1);
            }
            // --- FIN DE LA NUEVA ADUANA HÍBRIDA ---
        }

        boolean esExtemporanea = determinarSiEsExtemporanea(empleado, request.fechaInicio());

        SolicitudVacaciones solicitud = SolicitudVacaciones.builder()
                .empleado(empleado)
                .creadaPor(empleado)
                .turno(turnoAsignado)
                .fechaInicio(request.fechaInicio())
                .fechaFin(request.fechaFin())
                .diasTotalesCalculados(diasCalculados)
                .tipoSolicitud(tipoSol)
                .estatus("Pendiente_Jefe")
                .fechaCreacion(LocalDateTime.now())
                .esExtemporanea(esExtemporanea)
                .jefeAutorizadorNomina(request.jefeAutorizadorNomina())
                .comentarioJefe(request.comentarios())
                .build();

        return solicitudVacacionesRepository.save(solicitud);
    }

    public BigDecimal calcularSaldoTotalDisponible(Empleado empleado) {
        BigDecimal devengado = empleado.getSaldoVacacionesActual() != null ? empleado.getSaldoVacacionesActual() : BigDecimal.ZERO;
        BigDecimal proporcional = empleado.getSaldoProporcional() != null ? empleado.getSaldoProporcional() : BigDecimal.ZERO;

        // 🌟 CORRECCIÓN BACKEND: Regla de Oro del Saldo Adelantado
        // Si el devengado es negativo (ej. -14), lo tratamos como CERO exclusivamente para calcular el tope disponible
        BigDecimal devengadoEfectivo = devengado.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : devengado;
        BigDecimal saldoTotal = devengadoEfectivo.add(proporcional);

        if (empleado.getTipoEmpleado() != null && empleado.getTipoEmpleado().toUpperCase().contains("SIND")) {
            double valorDouble = saldoTotal.doubleValue();
            double parteDecimal = valorDouble - Math.floor(valorDouble);

            if (parteDecimal >= 0.9) {
                return BigDecimal.valueOf(Math.ceil(valorDouble));
            }
        }
        return saldoTotal;
    }

    private boolean determinarSiEsExtemporanea(Empleado empleado, LocalDate fechaInicio) {
        LocalDateTime ahora = LocalDateTime.now();
        String tipoLargo = (empleado.getTipoEmpleado() != null && empleado.getTipoEmpleado().equals("SINDICALIZADO")) ? "SINDICALIZADO" : "ADMINISTRATIVO";
        String tipoCorto = tipoLargo.equals("SINDICALIZADO") ? "SIND" : "ADMIN";

        ConfiguracionCorteNomina configCorte = configuracionCorteNominaRepository.findByTipoEmpleado(tipoLargo)
                .orElseThrow(() -> new RuntimeException("Falta configuración de corte para: " + tipoLargo));

        String valorConfig = configuracionSistemaRepository.findById("HORAS_ANTICIPACION_" + tipoCorto)
                .map(ConfiguracionSistema::getValor)
                .orElse("48");

        int horasRequeridas;
        try {
            String soloNumeros = valorConfig.replaceAll("[^0-9]", "").trim();
            horasRequeridas = Integer.parseInt(soloNumeros);
        } catch (Exception e) {
            horasRequeridas = 48;
        }

        LocalDate inicioSemanaSolicitud = fechaInicio.with(java.time.DayOfWeek.MONDAY);
        LocalDate inicioSemanaActual = ahora.toLocalDate().with(java.time.DayOfWeek.MONDAY);

        if (inicioSemanaSolicitud.isBefore(inicioSemanaActual)) {
            java.time.DayOfWeek diaCorte = java.time.DayOfWeek.of(configCorte.getDiaCorte());
            LocalDateTime limiteParaJustificarPasado = inicioSemanaActual.with(diaCorte).atTime(configCorte.getHoraCorte());

            if (ahora.isAfter(limiteParaJustificarPasado)) {
                throw new RuntimeException("El periodo de nómina para estas fechas ya ha cerrado.");
            }
        }

        LocalDateTime inicioVacaciones = fechaInicio.atStartOfDay();
        long horasDeDiferencia = java.time.Duration.between(ahora, inicioVacaciones).toHours();

        return horasDeDiferencia < horasRequeridas;
    }

    private java.util.Optional<ConfiguracionCorteNomina> findConfiguracionCorteNomina(String tipoEmpleado) {
        return configuracionCorteNominaRepository.findByTipoEmpleado(tipoEmpleado);
    }

    private Integer calcularDiasEfectivos(LocalDate fechaInicio, LocalDate fechaFin, Turno turno) {
        if (fechaInicio.isAfter(fechaFin)) {
            throw new RuntimeException("La fecha de inicio no puede ser mayor a la fecha de fin");
        }

        int dias = 0;
        LocalDate currentDay = fechaInicio;
        String descansosSaneados = sanitizarDiasDescanso(turno.getDiasDescanso());

        while (!currentDay.isAfter(fechaFin)) {
            String diaSemanaActualStr = obtenerDiaSemanaEspNormalizado(currentDay);
            if (!descansosSaneados.contains(diaSemanaActualStr)) {
                dias++;
            }
            currentDay = currentDay.plusDays(1);
        }
        return dias;
    }

    public com.hrms.vacaciones.dto.TableroEmpleadoResponse obtenerTablero(Integer nomina) {
        Empleado empleado = empleadoRepository.findById(nomina)
                .orElseThrow(() -> new RuntimeException("Empleado no encontrado"));

        List<SolicitudVacaciones> solicitudes = solicitudVacacionesRepository
                .findByEmpleado_NominaOrderByFechaInicioDesc(nomina);

        List<com.hrms.vacaciones.dto.SolicitudDTO> historial = solicitudes.stream()
                .map(s -> {
                    String nombreJefe = "N/A";
                    if (s.getJefeAutorizadorNomina() != null) {
                        nombreJefe = empleadoRepository.findById(s.getJefeAutorizadorNomina())
                                .map(Empleado::getNombreCompleto)
                                .orElse(s.getJefeAutorizadorNomina().toString());
                    }

                    return new com.hrms.vacaciones.dto.SolicitudDTO(
                            s.getId(),
                            s.getFechaInicio(),
                            s.getFechaFin(),
                            s.getDiasTotalesCalculados(),
                            s.getEstatus(),
                            s.getComentarioJefe(),
                            s.getFechaCreacion(),
                            nombreJefe,
                            s.getTipoSolicitud(),
                            s.getComentarioSupervisor(),
                            s.getComentarioExcepcion(),
                            s.getGrupoFolio(),
                            s.getTurno() != null ? s.getTurno().getNombreTurno() : "N/A",
                            false,
                            java.math.BigDecimal.ZERO,
                            new java.util.ArrayList<String>()
                    );
                })
                .toList();

        return new com.hrms.vacaciones.dto.TableroEmpleadoResponse(
                empleado.getNomina(),
                empleado.getNombreCompleto(),
                empleado.getSaldoVacacionesActual(),
                historial);
    }

    public List<Empleado> obtenerPlantillaDelJefe(Integer nominaJefe) {
        Empleado jefe = empleadoRepository.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Jefe no encontrado"));

        java.util.Set<Empleado> plantillaSet = new java.util.HashSet<>();
        String rol = jefe.getRolJerarquico() != null ? jefe.getRolJerarquico().trim().toUpperCase() : "";

        List<Empleado> directos = empleadoRepository.findByJefeDirectoNomina(nominaJefe);
        if (directos != null) {
            plantillaSet.addAll(directos);
        }

        if ("SHIFT LEADER".equals(rol)) {
            if (jefe.getWorkCentersACargo() != null) {
                List<Integer> wcs = jefe.getWorkCentersACargo().stream()
                        .map(WorkCenter::getId).toList();
                if (!wcs.isEmpty()) {
                    plantillaSet.addAll(empleadoRepository.findByWorkCenter_IdIn(wcs));
                }
            }
        } else if ("SUPERVISOR".equals(rol)) {
            // ✨ 1. NUEVO ESCUDO DIRECTO [CERO MOCKS]: Traer a los operadores de los WCs donde él es el dueño en PostgreSQL
            List<Integer> idsMisLineasDirectas = workCenterRepository.findAll().stream()
                    .filter(wc -> wc.getSupervisorNomina() != null && wc.getSupervisorNomina().equals(nominaJefe))
                    .map(WorkCenter::getId)
                    .collect(Collectors.toList());

            if (!idsMisLineasDirectas.isEmpty()) {
                plantillaSet.addAll(empleadoRepository.findByWorkCenter_IdIn(idsMisLineasDirectas));
            }

            // 2. Triangulación Legacy (Se mantiene como respaldo)
            if (directos != null) {
                for (Empleado sub : directos) {
                    if (sub.getWorkCentersACargo() != null) {
                        List<Integer> subWcs = sub.getWorkCentersACargo().stream()
                                .map(WorkCenter::getId).toList();
                        if (!subWcs.isEmpty()) {
                            plantillaSet.addAll(empleadoRepository.findByWorkCenter_IdIn(subWcs));
                        }
                    }
                }
            }
        }

        return plantillaSet.stream()
                .filter(e -> !e.getNomina().equals(nominaJefe))
                .toList();
    }

    public List<SolicitudVacaciones> obtenerPendientesPorJefe(Integer nominaJefe) {
        Empleado jefe = empleadoRepository.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Jefe no encontrado en el sistema"));
        String rol = jefe.getRolJerarquico() != null ? jefe.getRolJerarquico().trim().toUpperCase() : "";

        List<WorkCenter> misLineas = obtenerWorkCentersPorJefe(nominaJefe);
        List<Integer> wcIds = misLineas != null ? misLineas.stream().map(WorkCenter::getId).toList() : new ArrayList<>();
        List<Empleado> plantilla = obtenerPlantillaDelJefe(nominaJefe);
        List<Integer> nominasPlantilla = plantilla.stream().map(Empleado::getNomina).toList();

        return solicitudVacacionesRepository.findAll().stream()
                .filter(s -> s.getEstatus() != null && (
                        "PENDIENTE_JEFE".equalsIgnoreCase(s.getEstatus().trim()) ||
                                "PENDIENTE".equalsIgnoreCase(s.getEstatus().trim()) ||
                                "PENDIENTE_SUPERVISOR".equalsIgnoreCase(s.getEstatus().trim())
                ))
                .filter(s -> {
                    boolean coincidenciaDirecta = s.getJefeAutorizadorNomina() != null && s.getJefeAutorizadorNomina().equals(nominaJefe);
                    boolean coincidenciaFicha = s.getEmpleado() != null && nominasPlantilla.contains(s.getEmpleado().getNomina());
                    boolean coincidenciaLinea = s.getEmpleado() != null && s.getEmpleado().getWorkCenter() != null && wcIds.contains(s.getEmpleado().getWorkCenter().getId());

                    if (!(coincidenciaDirecta || coincidenciaFicha || coincidenciaLinea)) {
                        return false;
                    }

                    String estatus = s.getEstatus().trim().toUpperCase();
                    String tipoEmp = s.getEmpleado() != null && s.getEmpleado().getTipoEmpleado() != null ? s.getEmpleado().getTipoEmpleado().trim().toUpperCase() : "SINDICALIZADO";

                    if ("SUPERVISOR".equals(rol)) {
                        if ("SINDICALIZADO".equals(tipoEmp)) {
                            if ("PENDIENTE_SUPERVISOR".equals(estatus)) {
                                return true;
                            }
                            return coincidenciaDirecta;
                        } else {
                            return "PENDIENTE_JEFE".equals(estatus) || "PENDIENTE".equals(estatus);
                        }
                    } else {
                        return "PENDIENTE_JEFE".equals(estatus) || "PENDIENTE".equals(estatus);
                    }
                })
                .filter(s -> {
                    if (s.getTipoSolicitud() == null || s.getTipoSolicitud().trim().isEmpty()) return true;
                    String t = s.getTipoSolicitud().trim().toUpperCase();
                    return t.equals("V") || t.contains("VACACION");
                })
                // ✨ ESTA ES LA LÍNEA MÁGICA: Ordena de la fecha más antigua a la más nueva
                .sorted(java.util.Comparator.comparing(SolicitudVacaciones::getFechaCreacion))
                .collect(Collectors.toList());
    }

    public List<SolicitudVacaciones> obtenerRangosPermisosPendientesPorJefe(Integer nominaJefe) {
        Empleado jefe = empleadoRepository.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Jefe no encontrado en el sistema"));
        String rol = jefe.getRolJerarquico() != null ? jefe.getRolJerarquico().trim().toUpperCase() : "";

        List<WorkCenter> misLineas = obtenerWorkCentersPorJefe(nominaJefe);
        List<Integer> wcIds = misLineas != null ? misLineas.stream().map(WorkCenter::getId).toList() : new ArrayList<>();
        List<Empleado> plantilla = obtenerPlantillaDelJefe(nominaJefe);
        List<Integer> nominasPlantilla = plantilla.stream().map(Empleado::getNomina).toList();

        return solicitudVacacionesRepository.findAll().stream()
                .filter(s -> s.getEstatus() != null && (
                        "PENDIENTE_JEFE".equalsIgnoreCase(s.getEstatus().trim()) ||
                                "PENDIENTE".equalsIgnoreCase(s.getEstatus().trim()) ||
                                "PENDIENTE_SUPERVISOR".equalsIgnoreCase(s.getEstatus().trim())
                ))
                .filter(s -> {
                    boolean coincidenciaDirecta = s.getJefeAutorizadorNomina() != null && s.getJefeAutorizadorNomina().equals(nominaJefe);
                    boolean coincidenciaFicha = s.getEmpleado() != null && nominasPlantilla.contains(s.getEmpleado().getNomina());
                    boolean coincidenciaLinea = s.getEmpleado() != null && s.getEmpleado().getWorkCenter() != null && wcIds.contains(s.getEmpleado().getWorkCenter().getId());

                    if (!(coincidenciaDirecta || coincidenciaFicha || coincidenciaLinea)) {
                        return false;
                    }

                    String estatus = s.getEstatus().trim().toUpperCase();
                    String tipoEmp = s.getEmpleado() != null && s.getEmpleado().getTipoEmpleado() != null ? s.getEmpleado().getTipoEmpleado().trim().toUpperCase() : "SINDICALIZADO";

                    if ("SUPERVISOR".equals(rol)) {
                        if ("SINDICALIZADO".equals(tipoEmp)) {
                            if ("PENDIENTE_SUPERVISOR".equals(estatus)) {
                                return true;
                            }
                            return coincidenciaDirecta;
                        } else {
                            return "PENDIENTE_JEFE".equals(estatus) || "PENDIENTE".equals(estatus);
                        }
                    } else {
                        return "PENDIENTE_JEFE".equals(estatus) || "PENDIENTE".equals(estatus);
                    }
                })

                .filter(s -> {
                    if (s.getTipoSolicitud() == null || s.getTipoSolicitud().trim().isEmpty()) return false;
                    String t = s.getTipoSolicitud().trim().toUpperCase();
                    return !t.equals("V") && !t.contains("VACACION");
                })
                // ✨ ESTA ES LA LÍNEA MÁGICA: Ordena de la fecha más antigua a la más nueva
                .sorted(java.util.Comparator.comparing(SolicitudVacaciones::getFechaCreacion))
                .collect(Collectors.toList());
    }

    @Transactional
    public SolicitudVacaciones procesarRespuestaJefe(RespuestaJefeRequest request) {
        SolicitudVacaciones solicitud = solicitudVacacionesRepository.findById(request.idSolicitud())
                .orElseThrow(() -> new RuntimeException("Solicitud no encontrada"));

        solicitud.setFechaAprobacionJefe(LocalDateTime.now());
        solicitud.setComentarioJefe(request.comentario());
        solicitud.setEstatus(request.aprobado() ? "Aprobado" : "Rechazado");

        // ✨ NUEVO: Descontar saldo si se aprueba y es vacación ordinaria
        if (request.aprobado()) {
            String tipoSol = solicitud.getTipoSolicitud() != null ? solicitud.getTipoSolicitud().trim().toUpperCase() : "";
            if (tipoSol.equals("V") || tipoSol.contains("VACACION")) {
                Empleado emp = solicitud.getEmpleado();
                BigDecimal saldo = emp.getSaldoVacacionesActual() != null ? emp.getSaldoVacacionesActual() : BigDecimal.ZERO;
                int dias = solicitud.getDiasTotalesCalculados() != null ? solicitud.getDiasTotalesCalculados() : 0;
                emp.setSaldoVacacionesActual(saldo.subtract(BigDecimal.valueOf(dias)));
                empleadoRepository.save(emp);
            }
        }

        SolicitudVacaciones guardada = solicitudVacacionesRepository.save(solicitud);
        if (request.aprobado()) {
            ejecutarEfectoDominoCapacidad(guardada);
        }
        return guardada;
    }

    @Transactional
    public SolicitudVacaciones aprobarPorJefe(Integer idSolicitud, Integer nominaJefe) {
        SolicitudVacaciones solicitud = solicitudVacacionesRepository.findById(idSolicitud)
                .orElseThrow(() -> new RuntimeException("Solicitud no encontrada"));

        Empleado jefe = empleadoRepository.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Jefe no encontrado"));

        solicitud.setEstatus("Aprobado");
        solicitud.setAprobadoPor(jefe);
        solicitud.setFechaAprobacionJefe(LocalDateTime.now());

        // ✨ NUEVO: Descontar saldo al aprobar de forma rápida
        String tipoSol = solicitud.getTipoSolicitud() != null ? solicitud.getTipoSolicitud().trim().toUpperCase() : "";
        if (tipoSol.equals("V") || tipoSol.contains("VACACION")) {
            Empleado emp = solicitud.getEmpleado();
            BigDecimal saldo = emp.getSaldoVacacionesActual() != null ? emp.getSaldoVacacionesActual() : BigDecimal.ZERO;
            int dias = solicitud.getDiasTotalesCalculados() != null ? solicitud.getDiasTotalesCalculados() : 0;
            emp.setSaldoVacacionesActual(saldo.subtract(BigDecimal.valueOf(dias)));
            empleadoRepository.save(emp);
        }

        SolicitudVacaciones guardada = solicitudVacacionesRepository.save(solicitud);
        ejecutarEfectoDominoCapacidad(guardada);
        return guardada;
    }

    public java.util.Map<String, Long> obtenerEstadisticasJefe(Integer nominaJefe) {
        java.util.Map<String, Object> jefeFichaMap = new java.util.HashMap<>();
        java.util.Map<String, Long> estadisticas = new java.util.HashMap<>();
        LocalDateTime inicioMes = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        LocalDateTime finMes = LocalDate.now().with(java.time.temporal.TemporalAdjusters.lastDayOfMonth()).atTime(23, 59, 59);

        long aprobadasMes = solicitudVacacionesRepository.contarAprobadasPorJefeEnRango(nominaJefe, inicioMes, finMes);
        estadisticas.put("aprobadasMes", aprobadasMes);

        Empleado jefe = empleadoRepository.findById(nominaJefe).orElse(null);
        long proximasAusencias = 0;

        if (jefe != null) {
            List<WorkCenter> wcsList = obtenerWorkCentersPorJefe(nominaJefe);
            if (wcsList != null && !wcsList.isEmpty()) {
                List<Integer> wcsIds = wcsList.stream().map(WorkCenter::getId).toList();
                LocalDate hoy = LocalDate.now();
                LocalDate enSieteDias = hoy.plusDays(7);
                proximasAusencias = solicitudVacacionesRepository.contarAusenciasEquipoEnRango(wcsIds, hoy, enSieteDias);
            }
        }
        estadisticas.put("proximasAusencias", proximasAusencias);
        return estadisticas;
    }

    @Transactional
    public void procesarRespuestasMasivas(List<Integer> ids, String status, String comentario, Integer nominaJefe) {
        Empleado jefe = empleadoRepository.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Error: Jefe no encontrado en el sistema"));

        Iterable<SolicitudVacaciones> solicitudes = solicitudVacacionesRepository.findAllById(ids);
        List<SolicitudVacaciones> aprobadasAProcesar = new ArrayList<>();

        for (SolicitudVacaciones solicitud : solicitudes) {
            if ("Pendiente_Jefe".equals(solicitud.getEstatus()) || "Pendiente_Supervisor".equals(solicitud.getEstatus())) {
                solicitud.setFechaAprobacionJefe(LocalDateTime.now());
                solicitud.setComentarioJefe(comentario);
                solicitud.setAprobadoPor(jefe);
                boolean seAprueba = "APROBADO".equalsIgnoreCase(status);
                solicitud.setEstatus(seAprueba ? "Aprobado" : "Rechazado");
                if (seAprueba) {
                    aprobadasAProcesar.add(solicitud);

                    // ✨ NUEVO: Descontar saldo al aprobar masivamente
                    String tipoSol = solicitud.getTipoSolicitud() != null ? solicitud.getTipoSolicitud().trim().toUpperCase() : "";
                    if (tipoSol.equals("V") || tipoSol.contains("VACACION")) {
                        Empleado emp = solicitud.getEmpleado();
                        BigDecimal saldo = emp.getSaldoVacacionesActual() != null ? emp.getSaldoVacacionesActual() : BigDecimal.ZERO;
                        int dias = solicitud.getDiasTotalesCalculados() != null ? solicitud.getDiasTotalesCalculados() : 0;
                        emp.setSaldoVacacionesActual(saldo.subtract(BigDecimal.valueOf(dias)));
                        empleadoRepository.save(emp);
                    }
                }
            }
        }
        solicitudVacacionesRepository.saveAll(solicitudes);

        for (SolicitudVacaciones solAprobada : aprobadasAProcesar) {
            ejecutarEfectoDominoCapacidad(solAprobada);
        }
    }

    @Transactional
    public SolicitudVacaciones aplicarOverrideSupervisor(Integer idSolicitud, Integer nominaSupervisor, String nuevoEstatus, String comentario) {
        SolicitudVacaciones solicitud = solicitudVacacionesRepository.findById(idSolicitud)
                .orElseThrow(() -> new RuntimeException("Solicitud no encontrada"));

        Empleado supervisor = empleadoRepository.findById(nominaSupervisor)
                .orElseThrow(() -> new RuntimeException("Supervisor no encontrado"));

        if (!"SUPERVISOR".equalsIgnoreCase(supervisor.getRolJerarquico() != null ? supervisor.getRolJerarquico().trim() : "")) {
            throw new SecurityException("Permiso Denegado: Solo los perfiles con rol 'SUPERVISOR' pueden aplicar el derecho de revocación.");
        }

        boolean esOrdinaria = solicitud.getEsExtemporanea() == null || !solicitud.getEsExtemporanea();
        boolean esAprobadaActual = "Aprobado".equalsIgnoreCase(solicitud.getEstatus());
        boolean esAprobadaPorSistema = solicitud.getAprobadoPor() == null;
        boolean tieneMarcaSistema = (solicitud.getNotasSistema() != null && solicitud.getNotasSistema().toUpperCase().contains("SISTEMA"))
                || (solicitud.getComentarioJefe() != null && solicitud.getComentarioJefe().toUpperCase().contains("SISTEMA"));

        if (esOrdinaria && esAprobadaActual && (esAprobadaPorSistema || tieneMarcaSistema)) {
            throw new RuntimeException("¡Bloqueo de Auditoría! Esta solicitud ordinaria fue aprobada automáticamente por el sistema debido a la falta de respuesta oportuna de las jefaturas de piso. El estatus es inmutable para el piso de producción; cualquier ajuste o aclaración debe ser gestionado exclusivamente por los Administradores desde la pestaña de Gestión de Saldos.");
        }

        String tipoEmpleado = solicitud.getEmpleado().getTipoEmpleado() != null ? solicitud.getEmpleado().getTipoEmpleado() : "SINDICALIZADO";
        ConfiguracionCorteNomina configCorte = configuracionCorteNominaRepository.findByTipoEmpleado(tipoEmpleado).orElse(null);
        LocalDateTime ahora = LocalDateTime.now();

        if (configCorte != null) {
            LocalDate inicioSemanaSolicitud = solicitud.getFechaInicio().with(java.time.DayOfWeek.MONDAY);
            LocalDate inicioSemanaActual = ahora.toLocalDate().with(java.time.DayOfWeek.MONDAY);

            if (inicioSemanaSolicitud.isBefore(inicioSemanaActual)) {
                java.time.DayOfWeek diaCorte = java.time.DayOfWeek.of(configCorte.getDiaCorte());
                LocalDateTime limiteGuillotina = inicioSemanaActual.with(diaCorte).atTime(configCorte.getHoraCorte());

                if (ahora.isAfter(limiteGuillotina)) {
                    throw new RuntimeException("¡Bloqueo Contable! No se puede revocar la decisión de esta solicitud ya que pertenece a un periodo de nómina cerrado.");
                }
            }
        }

        String estatusAnterior = solicitud.getEstatus() != null ? solicitud.getEstatus() : "";

        if (!estatusAnterior.equalsIgnoreCase(nuevoEstatus)) {
            Empleado emp = solicitud.getEmpleado();
            BigDecimal saldoActual = emp.getSaldoVacacionesActual() != null ? emp.getSaldoVacacionesActual() : BigDecimal.ZERO;
            int diasVal = solicitud.getDiasTotalesCalculados() != null ? solicitud.getDiasTotalesCalculados() : 0;
            BigDecimal diasSolicitados = new BigDecimal(diasVal);

            if ("Aprobado".equalsIgnoreCase(estatusAnterior) && "Rechazado".equalsIgnoreCase(nuevoEstatus)) {
                emp.setSaldoVacacionesActual(saldoActual.add(diasSolicitados));
                empleadoRepository.save(emp);
            }
            else if ("Rechazado".equalsIgnoreCase(estatusAnterior) && "Aprobado".equalsIgnoreCase(nuevoEstatus)) {
                BigDecimal saldoDisponible = calcularSaldoTotalDisponible(emp);
                if (diasSolicitados.compareTo(saldoDisponible) > 0) {
                    throw new IllegalArgumentException("Imposible Revocar: El operador no cuenta con saldo suficiente (" + saldoDisponible + " días) para autorizar esta solicitud.");
                }
                emp.setSaldoVacacionesActual(saldoActual.subtract(diasSolicitados));
                empleadoRepository.save(emp);
            }
        }

        String timestamp = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(ahora);
        String notaBitacora = "[REVOCACIÓN OVERRIDE - " + timestamp + " por Sup. " + supervisor.getNombreCompleto() + "]: " + comentario.trim();

        solicitud.setEstatus(nuevoEstatus);
        solicitud.setComentarioSupervisor(comentario.trim());
        solicitud.setNotasSistema((solicitud.getNotasSistema() != null ? solicitud.getNotasSistema() + "\n" : "") + notaBitacora);
        solicitud.setAprobadoPor("Aprobado".equalsIgnoreCase(nuevoEstatus) ? supervisor : null);
        solicitud.setFechaAprobacionSupervisor(ahora);

        SolicitudVacaciones guardada = solicitudVacacionesRepository.save(solicitud);
        if ("Aprobado".equalsIgnoreCase(nuevoEstatus)) {
            ejecutarEfectoDominoCapacidad(guardada);
        }
        return guardada;
    }

    @Transactional
    public void procesarAprobacionParcial(Integer idSolicitud, Integer nominaJefe, List<String> fechasRechazadas, List<String> motivos) {
        SolicitudVacaciones original = solicitudVacacionesRepository.findById(idSolicitud)
                .orElseThrow(() -> new RuntimeException("Solicitud maestra no encontrada."));
        Empleado jefe = empleadoRepository.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Jefe no encontrado."));

        boolean yaEstabaAprobada = "Aprobado".equalsIgnoreCase(original.getEstatus());
        String grupoFolio = original.getGrupoFolio() != null ? original.getGrupoFolio() : "GRP-" + original.getId();

        // 1. Mapeo de días hábiles reales (Saltando descansos del turno)
        List<LocalDate> diasHabiles = new ArrayList<>();
        LocalDate cursor = original.getFechaInicio();
        String descansosSaneados = sanitizarDiasDescanso(original.getTurno().getDiasDescanso());
        while (!cursor.isAfter(original.getFechaFin())) {
            if (!descansosSaneados.contains(obtenerDiaSemanaEspNormalizado(cursor))) {
                diasHabiles.add(cursor);
            }
            cursor = cursor.plusDays(1);
        }

        // 2. Si rechazaron TODO o NADA, lo mandamos al flujo normal
        int diasRechazadosReales = (int) diasHabiles.stream().filter(d -> fechasRechazadas.contains(d.toString())).count();
        if (diasRechazadosReales == 0 && !yaEstabaAprobada) {
            aprobarPorJefe(idSolicitud, nominaJefe);
            return;
        } else if (diasRechazadosReales == diasHabiles.size()) {
            if (yaEstabaAprobada) { aplicarOverrideSupervisor(idSolicitud, nominaJefe, "Rechazado", "Rechazo Total Override"); }
            else { procesarRespuestaJefe(new RespuestaJefeRequest(idSolicitud, false, "Rechazo Total en Revisión Parcial")); }
            return;
        }

        // 3. Algoritmo de Empaquetado de Chunks Contiguos
        List<SolicitudVacaciones> chunks = new ArrayList<>();
        LocalDate cInicio = null, cFin = null;
        boolean cRechazado = false;
        String cMotivo = "";
        int cDias = 0;

        for (LocalDate dia : diasHabiles) {
            int idx = fechasRechazadas.indexOf(dia.toString());
            boolean diaRechazado = idx >= 0;
            String motivoDia = diaRechazado ? motivos.get(idx) : "Aprobado Parcialmente";

            if (cInicio == null) {
                cInicio = cFin = dia; cRechazado = diaRechazado; cMotivo = motivoDia; cDias = 1;
            } else if (cRechazado == diaRechazado && cMotivo.equals(motivoDia)) {
                cFin = dia; cDias++; // Se expande el bloque
            } else {
                chunks.add(crearChunk(original, cInicio, cFin, cDias, cRechazado, cMotivo, grupoFolio, jefe));
                cInicio = cFin = dia; cRechazado = diaRechazado; cMotivo = motivoDia; cDias = 1;
            }
        }
        if (cInicio != null) chunks.add(crearChunk(original, cInicio, cFin, cDias, cRechazado, cMotivo, grupoFolio, jefe));

        // 4. Matemáticas de Cobro / Devolución
        Empleado emp = original.getEmpleado();
        BigDecimal saldo = emp.getSaldoVacacionesActual() != null ? emp.getSaldoVacacionesActual() : BigDecimal.ZERO;

        if (yaEstabaAprobada) {
            // Ya había pagado todo. Como le quitamos días, le DEVOLVEMOS el saldo de lo rechazado.
            emp.setSaldoVacacionesActual(saldo.add(BigDecimal.valueOf(diasRechazadosReales)));
        } else {
            // Estaba pendiente (No había pagado nada). Le COBRAMOS solo los días aprobados.
            int diasAprobados = diasHabiles.size() - diasRechazadosReales;
            emp.setSaldoVacacionesActual(saldo.subtract(BigDecimal.valueOf(diasAprobados)));
        }
        empleadoRepository.save(emp);

        // 5. Suplantación de Base de Datos
        SolicitudVacaciones pChunk = chunks.get(0);
        original.setFechaInicio(pChunk.getFechaInicio());
        original.setFechaFin(pChunk.getFechaFin());
        original.setDiasTotalesCalculados(pChunk.getDiasTotalesCalculados());
        original.setEstatus(pChunk.getEstatus());
        original.setComentarioJefe(pChunk.getComentarioJefe());
        original.setGrupoFolio(grupoFolio);
        original.setAprobadoPor(pChunk.getAprobadoPor());
        original.setFechaAprobacionJefe(pChunk.getFechaAprobacionJefe());
        original.setNotasSistema((original.getNotasSistema() != null ? original.getNotasSistema() + "\n" : "") + "[SISTEMA] Petición Fraccionada (Original de " + diasHabiles.size() + " días).");

        solicitudVacacionesRepository.save(original);
        if ("Aprobado".equals(original.getEstatus())) ejecutarEfectoDominoCapacidad(original);

        for (int i = 1; i < chunks.size(); i++) {
            SolicitudVacaciones guardado = solicitudVacacionesRepository.save(chunks.get(i));
            if ("Aprobado".equals(guardado.getEstatus())) ejecutarEfectoDominoCapacidad(guardado);
        }
    }

    private SolicitudVacaciones crearChunk(SolicitudVacaciones og, LocalDate in, LocalDate fin, int dias, boolean rechazo, String motivo, String grp, Empleado jefe) {
        return SolicitudVacaciones.builder()
                .empleado(og.getEmpleado()).turno(og.getTurno()).tipoSolicitud(og.getTipoSolicitud())
                .fechaInicio(in).fechaFin(fin).diasTotalesCalculados(dias)
                .estatus(rechazo ? "Rechazado" : "Aprobado")
                .comentarioJefe(motivo).comentarioSupervisor(og.getComentarioSupervisor())
                .esExtemporanea(og.getEsExtemporanea()).esExcepcionLimite(og.getEsExcepcionLimite())
                .fechaCreacion(og.getFechaCreacion()).creadaPor(og.getCreadaPor())
                .fechaAprobacionJefe(LocalDateTime.now()).aprobadoPor(rechazo ? null : jefe)
                .grupoFolio(grp).notasSistema("[SISTEMA] Derivada de Partición. Folio Grupo: " + grp)
                .build();
    }

    @Transactional
    public void guardarConfiguracionArea(Integer nominaJefe, Integer mes, Integer anio, String fechaLimiteStr) {

        LocalDate hoy = LocalDate.now();
        LocalDate inicioMesConfig = LocalDate.of(anio, mes, 1);
        LocalDate finMesConfig = inicioMesConfig.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());

        if (hoy.isAfter(finMesConfig)) {
            throw new IllegalArgumentException("El periodo seleccionado corresponde a un mes ya cerrado en el sistema contable.");
        }

        LocalDate fechaLimiteRegistro = LocalDate.parse(fechaLimiteStr);

        // ✨ AQUÍ CONECTAMOS LA MÁQUINA MATEMÁTICA PARA FECHAS VIP
        java.util.Map<String, LocalDate> semanaVip = calcularSemanaVIP(mes, anio);
        LocalDate fechaAperturaRezago = semanaVip.get("inicio"); // Empieza el Lunes de la semana VIP
        LocalDate fechaAperturaGeneral = semanaVip.get("fin").plusDays(1); // El repechaje general abre en Sábado

        // ✨ 1. ANCLA GLOBAL: Traemos todos los turnos activos para inyectarles las fechas
        List<Turno> todosLosTurnos = turnoRepository.findByActivoTrue();

        for (Turno turno : todosLosTurnos) {
            CapacidadVacacionesMes config = capacidadRepo
                    .findBySupervisorNominaAndMesAndAnioAndTurno_Id(nominaJefe, mes, anio, turno.getId())
                    .orElse(new CapacidadVacacionesMes());

            config.setSupervisorNomina(nominaJefe);
            config.setTurno(turno);
            config.setMes(mes);
            config.setAnio(anio);
            // El cupo aquí ya no importa, la aduana la hace el Grupo de Proceso. Le ponemos 999.
            config.setMaxEmpleadosPorDia(999);
            config.setFechaLimiteRegistro(fechaLimiteRegistro);
            config.setFechaAperturaRezago(fechaAperturaRezago);
            config.setFechaAperturaGeneral(fechaAperturaGeneral);
            config.setDiasMinimosRezago(10); // Valor legacy por seguridad
            config.setUltimaModificacionPor(nominaJefe);
            config.setFechaUltimaModificacion(LocalDateTime.now());

            capacidadRepo.save(config);
        }

        // ✨ 2. TOMAMOS LA "FOTO" DE LOS GRUPOS ACTUALES
        List<GrupoProceso> gruposActivos = obtenerGruposProcesoPorJefe(nominaJefe);
        String resumenGrupos = gruposActivos.isEmpty() ? "Sin grupos configurados" :
                gruposActivos.stream()
                        .map(g -> g.getNombre() + " (Cupo: " + g.getCupoMaximo() + ")")
                        .collect(Collectors.joining(" | "));

        // ✨ 3. TOMAMOS LA "FOTO" DE LOS BLOQUEOS QUE CAEN EN ESTE MES
        List<PeriodoInhabil> bloqueos = periodoInhabilRepository.findBySupervisorNominaOrderByFechaInicioDesc(nominaJefe);
        String resumenBloqueos = bloqueos.stream()
                .filter(b -> b.getFechaInicio().getMonthValue() == mes || b.getFechaFin().getMonthValue() == mes)
                .map(b -> b.getMotivo() + " (" + b.getFechaInicio().getDayOfMonth() + " al " + b.getFechaFin().getDayOfMonth() + ")")
                .collect(Collectors.joining(" | "));

        if (resumenBloqueos.isEmpty()) {
            resumenBloqueos = "Sin bloqueos para este mes";
        }

        // ✨ VALIDACIÓN FORENSE: ¿Está sobreescribiendo en zona de riesgo (Sábado de congelamiento o después)?
        LocalDate sabadoPrevio = fechaAperturaRezago.minusDays(2);
        String etiquetaAccion = "APERTURA_PERIODO";
        if (!hoy.isBefore(sabadoPrevio)) {
            etiquetaAccion = "SOBREESCRITO_EN_SEMANA_VIP";
            System.out.println("⚠️ ALERTA: Supervisor #" + nominaJefe + " forzó la sobreescritura del periodo " + mes + "/" + anio + " en zona de riesgo.");
        }

        // ✨ 4. GUARDAMOS LA BITÁCORA V2.0 (Snapshot Contable)
        HistoricoConfiguracionArea log = HistoricoConfiguracionArea.builder()
                .mes(mes)
                .anio(anio)
                .fechaLimiteRegistro(fechaLimiteRegistro)
                .fechaAperturaRezago(fechaAperturaRezago)
                .fechaAperturaGeneral(fechaAperturaGeneral)
                .resumenGrupos(resumenGrupos)
                .resumenBloqueos(resumenBloqueos)
                .accion(etiquetaAccion)
                .realizadoPorNomina(nominaJefe)
                .fechaRegistro(LocalDateTime.now())
                .build();

        historicoRepo.save(log);

        // ✨ CONGELAMOS LOS BOLETOS VIP EN ESTE EXACTO SEGUNDO
        boletoVipRepository.deleteBySupervisorNominaAndMesAndAnio(nominaJefe, mes, anio);
        java.util.Map<String, java.util.Map<String, List<Empleado>>> quintiles = obtenerDistribucionQuintiles(nominaJefe);
        List<BoletoVip> nuevosBoletos = new ArrayList<>();

        for (java.util.Map<String, List<Empleado>> diasMap : quintiles.values()) {
            for (java.util.Map.Entry<String, List<Empleado>> entry : diasMap.entrySet()) {
                String dia = entry.getKey();
                for (Empleado emp : entry.getValue()) {
                    nuevosBoletos.add(new BoletoVip(emp.getNomina(), nominaJefe, mes, anio, dia));
                }
            }
        }
        boletoVipRepository.saveAll(nuevosBoletos);
    }

    public List<HistoricoConfiguracionArea> obtenerHistoricoConfiguracion(Integer nominaJefe) {
        // ✨ Consulta directa V2 por supervisor
        return historicoRepo.findByRealizadoPorNominaOrderByFechaRegistroDesc(nominaJefe);
    }

    public List<WorkCenter> obtenerWorkCentersPorJefe(Integer nominaJefe) {
        Empleado jefe = empleadoRepository.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Línea de comando no encontrada para nómina: " + nominaJefe));

        java.util.Set<WorkCenter> lineasAsAssignadas = new java.util.LinkedHashSet<>();
        String rol = jefe.getRolJerarquico() != null ? jefe.getRolJerarquico().trim().toUpperCase() : "";

        if (jefe.getWorkCentersACargo() != null && !jefe.getWorkCentersACargo().isEmpty()) {
            lineasAsAssignadas.addAll(jefe.getWorkCentersACargo());
        }

        if ("SUPERVISOR".equals(rol)) {
            // ✨ 1. NUEVO ESCUDO DIRECTO [CERO MOCKS]: Leer directo la columna supervisor_nomina
            List<WorkCenter> lineasPorColumna = workCenterRepository.findAll().stream()
                    .filter(wc -> wc.getSupervisorNomina() != null && wc.getSupervisorNomina().equals(nominaJefe))
                    .collect(Collectors.toList());
            lineasAsAssignadas.addAll(lineasPorColumna);

            // 2. Triangulación Legacy (Se queda por seguridad si hay WCs de Shift Leaders compartidos)
            List<Empleado> misShiftLeaders = empleadoRepository.findByJefeDirectoNomina(nominaJefe);
            if (misShiftLeaders != null) {
                for (Empleado shift : misShiftLeaders) {
                    if (shift.getWorkCentersACargo() != null && !shift.getWorkCentersACargo().isEmpty()) {
                        lineasAsAssignadas.addAll(shift.getWorkCentersACargo());
                    }
                }
            }
        }
        return new java.util.ArrayList<>(lineasAsAssignadas);
    }

    @Scheduled(cron = "0 1 0 * * ?")
    @Transactional
    public void cronOtorgarVacacionesPorAniversario() {
        sincronizarAniversariosMasivos(LocalDate.now(), LocalDate.now());
    }

    @Transactional
    public void sincronizarAniversariosMasivos(LocalDate fechaInicio, LocalDate fechaFin) {
        if (fechaInicio.isAfter(fechaFin)) {
            LocalDate temp = fechaInicio;
            fechaInicio = fechaFin;
            fechaFin = temp;
        }

        List<Empleado> plantilla = empleadoRepository.findAll();
        for (Empleado emp : plantilla) {
            if (emp.getFechaIngreso() != null) {
                int mesCumple = emp.getFechaIngreso().getMonthValue();
                int diaCumple = emp.getFechaIngreso().getDayOfMonth();
                LocalDate cumpleDeEsteAno = LocalDate.of(fechaFin.getYear(), mesCumple, diaCumple);

                if (!cumpleDeEsteAno.isBefore(fechaInicio) && !cumpleDeEsteAno.isAfter(fechaFin)) {
                    int aniosAntiguedad = cumpleDeEsteAno.getYear() - emp.getFechaIngreso().getYear();

                    if (aniosAntiguedad > 0) {
                        int diasNuevos = emp.calcularDiasDerecho(aniosAntiguedad);
                        String etiquetaBono = "[ANIVERSARIO #" + aniosAntiguedad + "]";

                        List<AuditoriaSaldo> historialDeEsteEmp = auditoriaSaldoRepository.findByNominaEmpleadoOrderByFechaMovimientoDesc(emp.getNomina());
                        boolean yaPagadoEsteAno = historialDeEsteEmp.stream()
                                .anyMatch(a -> a.getComentarioJustificacion() != null && a.getComentarioJustificacion().contains(etiquetaBono) && a.getFechaMovimiento().getYear() == cumpleDeEsteAno.getYear());

                        if (!yaPagadoEsteAno) {
                            BigDecimal saldoDevengado = emp.getSaldoVacacionesActual() != null ? emp.getSaldoVacacionesActual() : BigDecimal.ZERO;
                            emp.setSaldoVacacionesActual(saldoDevengado.add(BigDecimal.valueOf(diasNuevos)));
                            empleadoRepository.save(emp);

                            AuditoriaSaldo auditoria = new AuditoriaSaldo(
                                    emp.getNomina(),
                                    (double) diasNuevos,
                                    etiquetaBono + " El sistema otorgó " + diasNuevos + " días de vacaciones anuales de forma retroactiva.",
                                    LocalDateTime.now(),
                                    "SÚPER BARREDORA (SISTEMA)"
                            );
                            auditoriaSaldoRepository.save(auditoria);
                        }
                    }
                }
            }
        }
    }

    @Scheduled(cron = "0 0 8,14,20 * * ?") // 🤖 Se ejecuta TODOS LOS DÍAS a las 8am, 2pm y 8pm
    @Transactional
    public void cronRenovacionAutomaticaReglas() {
        LocalDate hoy = LocalDate.now();

        // 1. Descubrimos el "Mes Objetivo". Sumar 15 días garantiza proyectar el mes correcto sin importar si es 28 o 31.
        LocalDate mesObjetivoDate = hoy.plusDays(15);
        int mesProx = mesObjetivoDate.getMonthValue();
        int anioProx = mesObjetivoDate.getYear();

        // 2. Calculamos cuándo es realmente la semana VIP de ese mes
        java.util.Map<String, LocalDate> semanaVip = calcularSemanaVIP(mesProx, anioProx);
        LocalDate fechaAperturaRezago = semanaVip.get("inicio");

        // 3. ✨ EL DOBLE CHECK AUTOMÁTICO (Resiliencia contra apagones)
        // La "Zona de Riesgo" empieza el sábado previo al lunes VIP.
        LocalDate sabadoPrevio = fechaAperturaRezago.minusDays(2);

        // Si aún no entramos en la zona de congelamiento, el robot se vuelve a dormir
        if (hoy.isBefore(sabadoPrevio)) {
            return;
        }

        // ---------------------------------------------------------
        // 🚨 SI LLEGAMOS AQUÍ: ESTAMOS EN ZONA DE CONGELAMIENTO O VIP
        // ---------------------------------------------------------

        int mesActual = hoy.getMonthValue();
        int anioActual = hoy.getYear();

        // 4. Buscamos a los supervisores que tuvieron actividad en el mes actual (o el que está terminando)
        List<Integer> supervisoresActivos = capacidadRepo.findAll().stream()
                .filter(c -> c.getMes() == mesActual && c.getAnio() == anioActual)
                .map(CapacidadVacacionesMes::getSupervisorNomina)
                .distinct()
                .collect(Collectors.toList());

        LocalDate fechaAperturaGeneral = semanaVip.get("fin").plusDays(1);
        LocalDate ultimoDiaProxMes = LocalDate.of(anioProx, mesProx, 1).with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());

        for (Integer nominaJefe : supervisoresActivos) {
            // 5. Verificamos si este jefe ya configuró. Este es el seguro anti-duplicados para las corridas diarias.
            boolean yaConfigurado = capacidadRepo.findAll().stream()
                    .anyMatch(c -> c.getSupervisorNomina().equals(nominaJefe) && c.getMes() == mesProx && c.getAnio() == anioProx);

            if (!yaConfigurado) {
                // 6. El Jefe olvidó abrir el mes (o hubo un apagón el sábado). El sistema lo clona.
                List<Turno> todosLosTurnos = turnoRepository.findByActivoTrue();

                for (Turno turno : todosLosTurnos) {
                    CapacidadVacacionesMes configAuto = new CapacidadVacacionesMes();
                    configAuto.setSupervisorNomina(nominaJefe);
                    configAuto.setTurno(turno);
                    configAuto.setMes(mesProx);
                    configAuto.setAnio(anioProx);
                    configAuto.setMaxEmpleadosPorDia(999); // La aduana la hace el Grupo de Proceso
                    configAuto.setFechaLimiteRegistro(ultimoDiaProxMes);
                    configAuto.setFechaAperturaRezago(fechaAperturaRezago);
                    configAuto.setFechaAperturaGeneral(fechaAperturaGeneral);
                    configAuto.setDiasMinimosRezago(10);
                    configAuto.setUltimaModificacionPor(1555); // 1555 = Firma de SISTEMA
                    configAuto.setFechaUltimaModificacion(LocalDateTime.now());

                    capacidadRepo.save(configAuto);
                }

                // 7. Tomamos la "Foto" de los grupos para la Auditoría
                List<GrupoProceso> gruposActivos = obtenerGruposProcesoPorJefe(nominaJefe);
                String resumenGrupos = gruposActivos.isEmpty() ? "Sin grupos configurados" :
                        gruposActivos.stream()
                                .map(g -> g.getNombre() + " (Cupo: " + g.getCupoMaximo() + ")")
                                .collect(Collectors.joining(" | "));

                HistoricoConfiguracionArea logAuto = HistoricoConfiguracionArea.builder()
                        .mes(mesProx)
                        .anio(anioProx)
                        .fechaLimiteRegistro(ultimoDiaProxMes)
                        .fechaAperturaRezago(fechaAperturaRezago)
                        .fechaAperturaGeneral(fechaAperturaGeneral)
                        .resumenGrupos(resumenGrupos)
                        .resumenBloqueos("Ninguno (Apertura Automática del Sistema)")
                        .accion("AUTOGENERADO_SISTEMA")
                        .realizadoPorNomina(1555) // Firma del sistema
                        .fechaRegistro(LocalDateTime.now())
                        .build();

                historicoRepo.save(logAuto);

                // ✨ CONGELAMOS LOS BOLETOS VIP EN AUTOMÁTICO
                boletoVipRepository.deleteBySupervisorNominaAndMesAndAnio(nominaJefe, mesProx, anioProx);
                java.util.Map<String, java.util.Map<String, List<Empleado>>> quintiles = obtenerDistribucionQuintiles(nominaJefe);
                List<BoletoVip> nuevosBoletos = new ArrayList<>();
                for (java.util.Map<String, List<Empleado>> diasMap : quintiles.values()) {
                    for (java.util.Map.Entry<String, List<Empleado>> entry : diasMap.entrySet()) {
                        String dia = entry.getKey();
                        for (Empleado emp : entry.getValue()) {
                            nuevosBoletos.add(new BoletoVip(emp.getNomina(), nominaJefe, mesProx, anioProx, dia));
                        }
                    }
                }
                boletoVipRepository.saveAll(nuevosBoletos);

                System.out.println("🤖 [AUTOPILOT RESILIENTE] Reglas renovadas y Calculos VIP listos para Supervisor #" + nominaJefe);
            }
        }
    }

    @Transactional
    public void depurarSaldoVencidoManual(Integer nominaOperador, Integer nominaEmpleadoAfectado, String justificacion) {
        if (!esAdminORH(nominaOperador)) {
            throw new SecurityException("Acceso Denegado: No tienes permisos de Administrador para realizar depuraciones.");
        }

        if (justificacion == null || justificacion.trim().isEmpty() || justificacion.trim().length() < 10) {
            throw new IllegalArgumentException("¡Auditoría Activa! Es obligatorio ingresar una justificación detallada (mínimo 10 letras) para borrar saldos.");
        }

        Empleado empleado = empleadoRepository.findById(nominaEmpleadoAfectado)
                .orElseThrow(() -> new RuntimeException("Empleado no encontrado en la base de datos."));
        Empleado operador = empleadoRepository.findById(nominaOperador).get();

        BigDecimal diasVencidos = empleado.getSaldoCiclosAnteriores() != null ? empleado.getSaldoCiclosAnteriores() : BigDecimal.ZERO;

        if (diasVencidos.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Operación Cancelada: El colaborador no cuenta con saldo vencido de ciclos anteriores.");
        }

        BigDecimal saldoActual = empleado.getSaldoVacacionesActual() != null ? empleado.getSaldoVacacionesActual() : BigDecimal.ZERO;
        empleado.setSaldoVacacionesActual(saldoActual.subtract(diasVencidos));
        empleadoRepository.save(empleado);

        SolicitudVacaciones depuracionVisual = SolicitudVacaciones.builder()
                .empleado(empleado)
                .creadaPor(operador)
                .fechaInicio(LocalDate.now())
                .fechaFin(LocalDate.now())
                .diasTotalesCalculados(diasVencidos.intValue())
                .tipoSolicitud("Depuración Vacaciones Vencidas")
                .estatus("Aprobado")
                .fechaCreacion(LocalDateTime.now())
                .comentarioJefe(justificacion.trim())
                .build();
        solicitudVacacionesRepository.save(depuracionVisual);

        String notaAuditoria = "[DEPURACIÓN VENCIDOS | -" + diasVencidos + " días]: " + justificacion.trim();
        AuditoriaSaldo auditoria = new AuditoriaSaldo(
                nominaEmpleadoAfectado,
                -diasVencidos.doubleValue(),
                notaAuditoria,
                LocalDateTime.now(),
                operador.getNombreCompleto() + " (Nómina " + nominaOperador + ")"
        );
        auditoriaSaldoRepository.save(auditoria);
    }

    @Transactional
    public java.util.List<java.util.Map<String, Object>> aplicarVacacionesColectivas(
            java.util.List<Integer> empleadoIds,
            java.util.List<Integer> rolDescansoIds,
            LocalDate fechaInicio,
            LocalDate fechaFin,
            Integer nominaJefe) {

        Empleado jefe = empleadoRepository.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Jefe autorizador no encontrado."));

        java.util.List<java.util.Map<String, Object>> reporteAplicacion = new java.util.ArrayList<>();
        List<SolicitudVacaciones> todasLasSolicitudes = solicitudVacacionesRepository.findAll();

        String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        String loteToken = "LOTE-COLECTIVO-" + timestamp;

        for (int i = 0; i < empleadoIds.size(); i++) {
            Integer nominaEmp = empleadoIds.get(i);
            Integer idRol = rolDescansoIds.get(i);

            Empleado emp = empleadoRepository.findById(nominaEmp).orElse(null);
            Turno rol = turnoRepository.findById(idRol).orElse(null);

            if (emp != null && rol != null) {
                final Integer finalNomina = emp.getNomina();
                List<SolicitudVacaciones> solicitudesPrevias = todasLasSolicitudes.stream()
                        .filter(s -> s.getEmpleado().getNomina().equals(finalNomina)
                                && !"Rechazado".equalsIgnoreCase(s.getEstatus())
                                && !"CANCELADO".equalsIgnoreCase(s.getEstatus())) // ✨ Ignoramos las canceladas
                        .collect(Collectors.toList());

                int diasPedidoOriginal = 0;
                int diasCruzadosConVacaciones = 0;
                int diasNetosADescontar = 0;
                LocalDate cursor = fechaInicio;

                String descansosSaneados = sanitizarDiasDescanso(rol.getDiasDescanso());

                while (!cursor.isAfter(fechaFin)) {
                    String diaSemanaActualStr = obtenerDiaSemanaEspNormalizado(cursor);
                    boolean esLaboral = !descansosSaneados.contains(diaSemanaActualStr);

                    if (esLaboral) {
                        diasPedidoOriginal++;
                        final LocalDate fechaCursor = cursor;

                        boolean tieneVacacionesEseDia = solicitudesPrevias.stream()
                                .anyMatch(s -> !fechaCursor.isBefore(s.getFechaInicio()) && !fechaCursor.isAfter(s.getFechaFin()));

                        if (tieneVacacionesEseDia) {
                            diasCruzadosConVacaciones++;
                        } else {
                            diasNetosADescontar++;
                        }
                    }
                    cursor = cursor.plusDays(1);
                }

                // 🧠 ESCUDO CONTABLE: Jalamos el saldo total EXACTO de la entidad (el mismo que lee el HTML)
                BigDecimal saldoDisponible = emp.getSaldoTotalDisponible() != null ? emp.getSaldoTotalDisponible() : BigDecimal.ZERO;

                int diasAplicadosFinales = diasNetosADescontar;
                String estatusValidacion = "Aplicado Completo";
                LocalDate fechaFinAjustada = fechaFin;

                // Verificamos si los días netos a descontar superan el saldo disponible
                if (BigDecimal.valueOf(diasAplicadosFinales).compareTo(saldoDisponible) > 0) {
                    // 🧠 CLONAMOS LA MATEMÁTICA DEL FRONT-END: Hacia abajo (floor) y topado a cero absoluto (Math.max)
                    diasAplicadosFinales = Math.max(0, (int) Math.floor(saldoDisponible.doubleValue()));
                    estatusValidacion = "Capiado por Saldo Insuficiente";

                    // 🧠 RECALCULAMOS LA FECHA FIN SALTÁNDONOS LOS DÍAS DE DESCANSO OFICIALES DEL TURNO
                    if (diasAplicadosFinales > 0) {
                        LocalDate cursorAjuste = fechaInicio;
                        int diasLaboralesContados = 0;
                        while (diasLaboralesContados < diasAplicadosFinales) {
                            String diaStr = obtenerDiaSemanaEspNormalizado(cursorAjuste);
                            if (!descansosSaneados.contains(diaStr)) {
                                diasLaboralesContados++;
                            }
                            if (diasLaboralesContados < diasAplicadosFinales) {
                                cursorAjuste = cursorAjuste.plusDays(1);
                            }
                        }
                        fechaFinAjustada = cursorAjuste;
                    } else {
                        fechaFinAjustada = fechaInicio;
                    }
                }

                if (diasCruzadosConVacaciones > 0 && diasAplicadosFinales == diasNetosADescontar) {
                    estatusValidacion = "Aplicado Ajustado (Se omitieron " + diasCruzadosConVacaciones + " días por vacaciones previas)";
                } else if (diasCruzadosConVacaciones > 0) {
                    estatusValidacion = "Capiado + Cruce Omitido (" + diasCruzadosConVacaciones + " días ya programados)";
                }

                if (diasAplicadosFinales > 0) {
                    SolicitudVacaciones colectiva = SolicitudVacaciones.builder()
                            .empleado(emp)
                            .creadaPor(jefe)
                            .aprobadoPor(jefe)
                            .turno(rol)
                            .fechaInicio(fechaInicio)
                            .fechaFin(fechaFinAjustada)
                            .diasTotalesCalculados(diasAplicadosFinales)
                            .tipoSolicitud("Vacaciones Colectivas")
                            .estatus("Aprobado")
                            .loteAutorizacionMasiva(loteToken)
                            .fechaCreacion(LocalDateTime.now())
                            .fechaAprobacionJefe(LocalDateTime.now())
                            .comentarioJefe("[Vacaciones Colectivas]")
                            .build();
                    solicitudVacacionesRepository.save(colectiva);

                    emp.setSaldoVacacionesActual(emp.getSaldoVacacionesActual().subtract(BigDecimal.valueOf(diasAplicadosFinales)));
                    empleadoRepository.save(emp);
                } else if (diasCruzadosConVacaciones > 0 && diasNetosADescontar == 0) {
                    estatusValidacion = "No Descontado (Periodo cubierto por vacaciones previas)";
                } else {
                    estatusValidacion = "No Aplicado (Saldo 0)";
                }

                java.util.Map<String, Object> filaReporte = new java.util.HashMap<>();
                filaReporte.put("lote", loteToken);
                filaReporte.put("nomina", emp.getNomina());
                filaReporte.put("nombre", emp.getNombreCompleto());
                filaReporte.put("turno", rol.getNombreTurno());
                filaReporte.put("solicitados", diasPedidoOriginal);
                filaReporte.put("aplicados", diasAplicadosFinales);
                filaReporte.put("observaciones", estatusValidacion);
                reporteAplicacion.add(filaReporte);
            }
        }
        return reporteAplicacion;
    }

    public List<SolicitudVacaciones> obtenerTodasColectivasPorJefe(Integer nominaJefe) {
        return solicitudVacacionesRepository.findAll().stream()
                .filter(s -> "Vacaciones Colectivas".equalsIgnoreCase(s.getTipoSolicitud()))
                .filter(s -> s.getCreadaPor() != null && s.getCreadaPor().getNomina().equals(nominaJefe))
                .collect(Collectors.toList());
    }

    public List<java.util.Map<String, Object>> obtenerTodasColectivasPorJefeProyectado(Integer nominaJefe) {
        List<SolicitudVacaciones> listaRaw = obtenerTodasColectivasPorJefe(nominaJefe);
        java.util.List<java.util.Map<String, Object>> proyectado = new java.util.ArrayList<>();

        for (SolicitudVacaciones s : listaRaw) {
            java.util.Map<String, Object> proyectadoMap = new java.util.HashMap<>();
            proyectadoMap.put("loteAutorizacionMasiva", s.getLoteAutorizacionMasiva());
            proyectadoMap.put("fechaInicio", s.getFechaInicio() != null ? s.getFechaInicio().toString() : "N/A");
            proyectadoMap.put("fechaFin", s.getFechaFin() != null ? s.getFechaFin().toString() : "N/A");
            proyectadoMap.put("fechaCreacion", s.getFechaCreacion());
            proyectadoMap.put("estatus", s.getEstatus());
            proyectado.add(proyectadoMap);
        }
        return proyectado;
    }

    // ✨ EL REGRESO DEL REY: RESTAURADO PARA DAR SOPORTE A TU CONTROLADOR DE JEFES
    public List<java.util.Map<String, Object>> obtenerResumenLotesColectivos(Integer nominaJefe) {
        List<SolicitudVacaciones> todas = obtenerTodasColectivasPorJefe(nominaJefe);
        java.util.Map<String, List<SolicitudVacaciones>> agrupados = todas.stream()
                .filter(s -> s.getLoteAutorizacionMasiva() != null)
                .collect(Collectors.groupingBy(SolicitudVacaciones::getLoteAutorizacionMasiva));

        List<java.util.Map<String, Object>> resumen = new java.util.ArrayList<>();
        agrupados.forEach((lote, lista) -> {
            if (!lista.isEmpty()) {
                SolicitudVacaciones ref = lista.get(0);
                long totalEmpleados = lista.size();

                String wcsAfectados = lista.stream()
                        .map(s -> s.getEmpleado().getWorkCenter() != null ? String.valueOf(s.getEmpleado().getWorkCenter().getId()) : "N/A")
                        .distinct()
                        .collect(Collectors.joining(", "));

                java.util.Map<String, Object> item = new java.util.HashMap<>();
                item.put("lote", lote);
                item.put("wcs", "WCs: " + wcsAfectados);
                item.put("fechaInicio", ref.getFechaInicio());
                item.put("fechaFin", ref.getFechaFin());
                item.put("totalEmpleados", totalEmpleados);
                item.put("fechaCreacion", ref.getFechaCreacion());
                resumen.add(item);
            }
        });

        resumen.sort((a, b) -> ((LocalDateTime) b.get("fechaCreacion")).compareTo((LocalDateTime) a.get("fechaCreacion")));
        return resumen;
    }

    private boolean esDiaDescanso(LocalDate fecha, SolicitudVacaciones sol) {
        Turno turno = sol.getTurno();
        if (turno == null && sol.getEmpleado() != null) {
            turno = sol.getEmpleado().getTurno();
        }
        if (turno == null || turno.getDiasDescanso() == null) {
            return fecha.getDayOfWeek().getValue() == 7;
        }

        String[] nombresDias = {"", "LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO"};
        String diaSemanaActual = nombresDias[fecha.getDayOfWeek().getValue()];
        String descansosSaneados = sanitizarDiasDescanso(turno.getDiasDescanso());

        return descansosSaneados.contains(diaSemanaActual);
    }

    public void ejecutarEfectoDominoCapacidad(SolicitudVacaciones solicitudAprobada) {
        if (solicitudAprobada == null || solicitudAprobada.getEmpleado() == null ||
                solicitudAprobada.getEmpleado().getWorkCenter() == null || solicitudAprobada.getTurno() == null) {
            return;
        }

        Integer turnoId = solicitudAprobada.getTurno().getId();
        Integer wcId = solicitudAprobada.getEmpleado().getWorkCenter().getId();
        LocalDate inicio = solicitudAprobada.getFechaInicio();
        LocalDate fin = solicitudAprobada.getFechaFin();
        Integer supervisorNomina = solicitudAprobada.getEmpleado().getWorkCenter().getSupervisorNomina();

        if (inicio == null || fin == null || supervisorNomina == null) return;

        // ✨ BUSCAMOS LA BOLSA HÍBRIDA
        GrupoProceso grupoAfectado = null;
        List<GrupoProceso> gruposDelJefe = obtenerGruposProcesoPorJefe(supervisorNomina);

        for (GrupoProceso g : gruposDelJefe) {
            if ("CC".equalsIgnoreCase(g.getTipoAgrupacion()) && solicitudAprobada.getEmpleado().getCentroCosto() != null) {
                if (g.getCentrosCosto().stream().anyMatch(cc -> cc.getId().equals(solicitudAprobada.getEmpleado().getCentroCosto().getId()))) {
                    grupoAfectado = g; break;
                }
            } else if ("WC".equalsIgnoreCase(g.getTipoAgrupacion())) {
                if (g.getWorkCenters().stream().anyMatch(wc -> wc.getId().equals(wcId))) {
                    grupoAfectado = g; break;
                }
            }
        }

        LocalDate diaCursor = inicio;
        while (!diaCursor.isAfter(fin)) {
            final LocalDate diaEvaluado = diaCursor;

            if (grupoAfectado != null && "WC".equalsIgnoreCase(grupoAfectado.getTipoAgrupacion())) {
                // MODO WC: BARRIDO POR GRUPO MACRO (Día y Turno)
                List<Integer> wcIdsDelGrupo = grupoAfectado.getWorkCenters().stream().map(WorkCenter::getId).collect(Collectors.toList());

                List<SolicitudVacaciones> vivasDelGrupo = solicitudVacacionesRepository.findAll().stream()
                        .filter(s -> !List.of("RECHAZADO", "CANCELADO").contains(s.getEstatus().toUpperCase()))
                        .filter(s -> s.getTipoSolicitud() != null && (s.getTipoSolicitud().toUpperCase().contains("VACACION") || s.getTipoSolicitud().trim().equalsIgnoreCase("V")))
                        .filter(s -> s.getEmpleado().getWorkCenter() != null && wcIdsDelGrupo.contains(s.getEmpleado().getWorkCenter().getId()))
                        .filter(s -> !diaEvaluado.isBefore(s.getFechaInicio()) && !diaEvaluado.isAfter(s.getFechaFin()))
                        .collect(Collectors.toList());

                // 1. Efecto Dominó por Saturación Total de la Bolsa
                if (vivasDelGrupo.size() >= grupoAfectado.getCupoMaximo()) {
                    rechazarPendientesCascada(
                            solicitudVacacionesRepository.findAll().stream()
                                    .filter(s -> s.getEstatus() != null && s.getEstatus().toUpperCase().startsWith("PENDIENTE"))
                                    .filter(s -> s.getEmpleado() != null && s.getEmpleado().getWorkCenter() != null && wcIdsDelGrupo.contains(s.getEmpleado().getWorkCenter().getId()))
                                    .filter(s -> !s.getId().equals(solicitudAprobada.getId()))
                                    .filter(s -> !diaEvaluado.isBefore(s.getFechaInicio()) && !diaEvaluado.isAfter(s.getFechaFin()))
                                    .collect(Collectors.toList()),
                            "La capacidad máxima del grupo ('" + grupoAfectado.getNombre() + "') se ha agotado para este día.",
                            diaEvaluado, "[SISTEMA - EFECTO DOMINÓ] Saturación de Grupo WC"
                    );
                }

                // 2. Efecto Dominó por Saturación de Turno en el Grupo
                long ocupadosEnElTurno = vivasDelGrupo.stream().filter(s -> s.getTurno() != null && s.getTurno().getId().equals(turnoId)).count();
                int limiteTurno = (grupoAfectado.getCupoMaximoTurno() != null) ? grupoAfectado.getCupoMaximoTurno() : 1;

                if (ocupadosEnElTurno >= limiteTurno) {
                    rechazarPendientesCascada(
                            solicitudVacacionesRepository.findAll().stream()
                                    .filter(s -> s.getEstatus() != null && s.getEstatus().toUpperCase().startsWith("PENDIENTE"))
                                    .filter(s -> s.getEmpleado() != null && s.getEmpleado().getWorkCenter() != null && wcIdsDelGrupo.contains(s.getEmpleado().getWorkCenter().getId()))
                                    .filter(s -> s.getTurno() != null && s.getTurno().getId().equals(turnoId))
                                    .filter(s -> !s.getId().equals(solicitudAprobada.getId()))
                                    .filter(s -> !diaEvaluado.isBefore(s.getFechaInicio()) && !diaEvaluado.isAfter(s.getFechaFin()))
                                    .collect(Collectors.toList()),
                            "El cupo máximo para el turno '" + solicitudAprobada.getTurno().getNombreTurno() + "' en el grupo ('" + grupoAfectado.getNombre() + "') se ha completado.",
                            diaEvaluado, "[SISTEMA - EFECTO DOMINÓ] Límite de Turno Grupo WC"
                    );
                }

            } else {
                // MODO CC: BARRIDO CLÁSICO (WC Individual + Bolsa CC)
                long ocupadosMismoWcTurno = solicitudVacacionesRepository.contarOcupadosPorLineaYTurno(wcId, turnoId, diaEvaluado);
                int cupoPermitidoWc = (solicitudAprobada.getEmpleado().getWorkCenter().getCupoConcurrenteTurno() != null && solicitudAprobada.getEmpleado().getWorkCenter().getCupoConcurrenteTurno() > 0)
                        ? solicitudAprobada.getEmpleado().getWorkCenter().getCupoConcurrenteTurno() : 1;

                if (ocupadosMismoWcTurno >= cupoPermitidoWc) {
                    rechazarPendientesCascada(
                            solicitudVacacionesRepository.findAll().stream()
                                    .filter(s -> s.getEstatus() != null && s.getEstatus().toUpperCase().startsWith("PENDIENTE"))
                                    .filter(s -> s.getEmpleado() != null && s.getEmpleado().getWorkCenter() != null && s.getEmpleado().getWorkCenter().getId().equals(wcId))
                                    .filter(s -> s.getTurno() != null && s.getTurno().getId().equals(turnoId))
                                    .filter(s -> !s.getId().equals(solicitudAprobada.getId()))
                                    .filter(s -> !diaEvaluado.isBefore(s.getFechaInicio()) && !diaEvaluado.isAfter(s.getFechaFin()))
                                    .collect(Collectors.toList()),
                            "El cupo máximo (" + cupoPermitidoWc + ") para tu turno en el WC " + wcId + " se ha completado.",
                            diaEvaluado, "[SISTEMA - EFECTO DOMINÓ] Límite de WC alcanzado"
                    );
                }

                if (grupoAfectado != null && "CC".equalsIgnoreCase(grupoAfectado.getTipoAgrupacion())) {
                    List<Integer> ccIdsDelGrupo = grupoAfectado.getCentrosCosto().stream().map(CentroCosto::getId).collect(Collectors.toList());
                    long ocupadosEnElMacroGrupo = solicitudVacacionesRepository.countVacacionesPorGrupoYFecha(ccIdsDelGrupo, diaEvaluado);

                    if (ocupadosEnElMacroGrupo >= grupoAfectado.getCupoMaximo()) {
                        rechazarPendientesCascada(
                                solicitudVacacionesRepository.findAll().stream()
                                        .filter(s -> s.getEstatus() != null && s.getEstatus().toUpperCase().startsWith("PENDIENTE"))
                                        .filter(s -> s.getEmpleado() != null && s.getEmpleado().getCentroCosto() != null && ccIdsDelGrupo.contains(s.getEmpleado().getCentroCosto().getId()))
                                        .filter(s -> !s.getId().equals(solicitudAprobada.getId()))
                                        .filter(s -> !diaEvaluado.isBefore(s.getFechaInicio()) && !diaEvaluado.isAfter(s.getFechaFin()))
                                        .collect(Collectors.toList()),
                                "La capacidad máxima del grupo ('" + grupoAfectado.getNombre() + "') se ha agotado para este día.",
                                diaEvaluado, "[SISTEMA - EFECTO DOMINÓ] Saturación de Grupo CC"
                        );
                    }
                }
            }
            diaCursor = diaCursor.plusDays(1);
        }
    }

    private void rechazarPendientesCascada(List<SolicitudVacaciones> pendientes, String razon, LocalDate dia, String tagSistema) {
        String timestamp = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(LocalDateTime.now());
        for (SolicitudVacaciones solRechazada : pendientes) {
            solRechazada.setEstatus("Rechazado");
            solRechazada.setComentarioSupervisor("[SISTEMA]: Solicitud RECHAZADA AUTOMÁTICAMENTE. Razón: " + razon + " Autorización previa asentada el " + timestamp + ".");
            solRechazada.setNotasSistema((solRechazada.getNotasSistema() != null ? solRechazada.getNotasSistema() + "\n" : "") + tagSistema + " el " + dia);
            solicitudVacacionesRepository.save(solRechazada);
        }
    }

    public List<Map<String, Object>> obtenerEventosCalendarioJefe(Integer nominaJefe) {
        List<Map<String, Object>> eventos = new ArrayList<>();

        List<DiasFestivos> feriados = diasFestivosRepository.findByActivoTrue();
        for (DiasFestivos f : feriados) {
            Map<String, Object> evento = new HashMap<>();
            evento.put("id", "FESTIVO_" + f.getId());
            evento.put("title", "🇲🇽 " + f.getDescripcion());
            evento.put("start", f.getFecha().toString());
            evento.put("allDay", true);
            evento.put("backgroundColor", "#212529");
            evento.put("borderColor", "#212529");
            evento.put("textColor", "#ffffff");

            Map<String, Object> props = new HashMap<>();
            props.put("tipo", "FERIADO");
            evento.put("extendedProps", props);
            eventos.add(evento);
        }

        List<Empleado> plantilla = obtenerPlantillaDelJefe(nominaJefe);
        if (!plantilla.isEmpty()) {
            List<SolicitudVacaciones> aprobadas = solicitudVacacionesRepository.findByEmpleadoInAndEstatusIn(
                    plantilla, List.of("Aprobado", "Aprobada")
            );

            Map<LocalDate, Map<String, List<Map<String, Object>>>> acumuladorSemantico = new HashMap<>();

            for (SolicitudVacaciones sol : aprobadas) {
                LocalDate cursor = sol.getFechaInicio();
                LocalDate fin = sol.getFechaFin();

                while (!cursor.isAfter(fin)) {
                    if (!esDiaDescanso(cursor, sol)) {
                        String tipo = sol.getEmpleado().getTipoEmpleado();
                        if (tipo == null) tipo = "SINDICALIZADO";
                        tipo = tipo.trim().toUpperCase();

                        acumuladorSemantico.putIfAbsent(cursor, new HashMap<>());
                        acumuladorSemantico.get(cursor).putIfAbsent(tipo, new ArrayList<>());

                        Map<String, Object> datosEmpleado = new HashMap<>();
                        datosEmpleado.put("nomina", sol.getEmpleado().getNomina());
                        datosEmpleado.put("nombre", sol.getEmpleado().getNombreCompleto());
                        datosEmpleado.put("wc", sol.getEmpleado().getWorkCenter() != null ? "WC " + sol.getEmpleado().getWorkCenter().getId() : "N/A");

                        acumuladorSemantico.get(cursor).get(tipo).add(datosEmpleado);
                    }
                    cursor = cursor.plusDays(1);
                }
            }

            acumuladorSemantico.forEach((fecha, mapaTipos) -> {
                mapaTipos.forEach((tipo, listaInvolucrados) -> {
                    Map<String, Object> evento = new HashMap<>();
                    int totalAusentes = listaInvolucrados.size();

                    evento.put("id", "VAC_" + fecha + "_" + tipo);
                    evento.put("start", fecha.toString());
                    evento.put("allDay", true);

                    if ("SINDICALIZADO".equals(tipo)) {
                        evento.put("title", "⚙️ " + totalAusentes + " Planta (Sind)");
                        evento.put("backgroundColor", "#198754");
                        evento.put("borderColor", "#198754");
                    } else {
                        evento.put("title", "🏢 " + totalAusentes + " Admin");
                        evento.put("backgroundColor", "#0d6efd");
                        evento.put("borderColor", "#0d6efd");
                    }
                    evento.put("textColor", "#ffffff");

                    Map<String, Object> props = new HashMap<>();
                    props.put("tipo", tipo);
                    props.put("empleados", listaInvolucrados);
                    evento.put("extendedProps", props);

                    eventos.add(evento);
                });
            });
        }
        return eventos;
    }

    public List<SolicitudVacaciones> obtenerHistorialDecisionesLinea(Integer nominaJefe) {
        List<Empleado> plantilla = obtenerPlantillaDelJefe(nominaJefe);
        if (plantilla.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        return solicitudVacacionesRepository.findByEmpleadoInAndEstatusIn(
                        plantilla,
                        java.util.Arrays.asList("Aprobado", "Rechazado")
                ).stream()
                .filter(s -> s.getTipoSolicitud() != null && (s.getTipoSolicitud().toUpperCase().contains("VACACION") || s.getTipoSolicitud().trim().equalsIgnoreCase("V")))
                .sorted((a, b) -> b.getFechaCreacion().compareTo(a.getFechaCreacion()))
                .limit(40)
                .collect(Collectors.toList());
    }

    public java.util.Map<String, List<com.hrms.vacaciones.dto.SolicitudDTO>> obtenerHistorialSeparadoYFiltrado(
            Integer nomina, LocalDate inicio, LocalDate fin) {

        Empleado empleado = empleadoRepository.findById(nomina)
                .orElseThrow(() -> new RuntimeException("Empleado no encontrado con la nómina: " + nomina));

        List<com.hrms.vacaciones.dto.SolicitudDTO> todosDtos = new ArrayList<>();

        List<SolicitudVacaciones> solicitudesVac = solicitudVacacionesRepository
                .findByEmpleado_NominaOrderByFechaInicioDesc(nomina);

        solicitudesVac.forEach(s -> {
            String nombreJefe = "N/A";
            if (s.getJefeAutorizadorNomina() != null) {
                nombreJefe = empleadoRepository.findById(s.getJefeAutorizadorNomina())
                        .map(Empleado::getNombreCompleto)
                        .orElse(s.getJefeAutorizadorNomina().toString());
            }

            Integer sId = s.getId() != null ? s.getId() : 0;
            LocalDate fInicio = s.getFechaInicio();
            LocalDate fFin = s.getFechaFin();
            Integer sDias = s.getDiasTotalesCalculados() != null ? s.getDiasTotalesCalculados() : 0;
            String sEstatus = s.getEstatus();
            String sComentarioJefe = s.getComentarioJefe();
            LocalDateTime sFechaCreacion = s.getFechaCreacion() != null ? s.getFechaCreacion() : LocalDateTime.now();
            String sTipoSol = s.getTipoSolicitud();
            String sComentarioSup = s.getComentarioSupervisor();
            String sComentarioExc = s.getComentarioExcepcion();

            todosDtos.add(new com.hrms.vacaciones.dto.SolicitudDTO(
                    sId,
                    fInicio,
                    fFin,
                    sDias,
                    sEstatus,
                    sComentarioJefe,
                    sFechaCreacion,
                    nombreJefe,
                    sTipoSol,
                    sComentarioSup,
                    sComentarioExc,
                    s.getGrupoFolio(),
                    s.getTurno() != null ? s.getTurno().getNombreTurno() : "N/A",
                    false,
                    java.math.BigDecimal.ZERO,
                    new java.util.ArrayList<String>()
            ));
        });

        List<com.hrms.vacaciones.model.SolicitudPermiso> solicitudesPerm = solicitudPermisoRepository.findAll().stream()
                .filter(p -> p.getEmpleado() != null && p.getEmpleado().getNomina().equals(nomina))
                // ✨ FILTRO ANTI-DUPLICADOS: Ocultamos la tarjeta gemela de piso (TXT).
                // Así el trabajador solo verá el estatus real de RH dictado por la tarjeta de Recuperación.
                .filter(p -> p.getJustificacionSupervisor() == null || !p.getJustificacionSupervisor().contains("[RECUPERACIÓN EXTRAORDINARIA]"))
                .collect(Collectors.toList());

        solicitudesPerm.forEach(p -> {
            String nombreJefe = "Pendiente de Validación";

            if (p.getResueltoPor() != null) {
                nombreJefe = p.getResueltoPor().getNombreCompleto();
            } else if (p.getEmpleado() != null && p.getEmpleado().getJefeDirectoNomina() != null) {
                nombreJefe = empleadoRepository.findById(p.getEmpleado().getJefeDirectoNomina())
                        .map(Empleado::getNombreCompleto)
                        .orElse("Mesa de Comando Operativa");
            } else {
                nombreJefe = "Mesa de Comando Operativa";
            }

            String fullComment = p.getJustificacionSupervisor() != null ? p.getJustificacionSupervisor() : "";
            String comentarioJefe = fullComment;
            String comentarioSupervisor = "";

            if (fullComment.contains("[RECHAZADO POR LÍNEA]:")) {
                int splitIndex = fullComment.indexOf("[RECHAZADO POR LÍNEA]:");
                comentarioJefe = fullComment.substring(0, splitIndex).trim();
                comentarioSupervisor = fullComment.substring(splitIndex + "[RECHAZADO POR LÍNEA]:".length()).trim();
                if (comentarioJefe.isEmpty()) {
                    comentarioJefe = "Sin observaciones adicionales.";
                }
            }

            String codigoPermiso = p.getTipoPermiso() != null ? p.getTipoPermiso().getCodigo() : "TXT";

            Integer pId = p.getId() != null ? p.getId().intValue() : 0;
            LocalDate pFecha = p.getFechaIncidencia();
            Integer pDias = 1;
            String pEstatus = p.getEstatus();
            LocalDateTime pFechaSol = p.getFechaSolicitud();
            String pComentarioExc = null;

            // ✨ EXTRACCIÓN DEL RECIBO TXT
            List<String> pagosTxt = new ArrayList<>();
            if (p.getDesglosesPago() != null && !p.getDesglosesPago().isEmpty()) {
                for (com.hrms.vacaciones.model.SolicitudTxtPago pago : p.getDesglosesPago()) {
                    pagosTxt.add(pago.getFechaPago().toString() + " (" + pago.getHorasPago() + " hrs)");
                }
            }

            todosDtos.add(new com.hrms.vacaciones.dto.SolicitudDTO(
                    pId,
                    pFecha,
                    pFecha,
                    pDias,
                    pEstatus,
                    comentarioJefe,
                    pFechaSol,
                    nombreJefe,
                    codigoPermiso,
                    comentarioSupervisor,
                    pComentarioExc,
                    (String) null,
                    p.getTurno() != null ? p.getTurno().getNombreTurno() : "N/A",
                    p.getEsPorHoras() != null ? p.getEsPorHoras() : false,
                    p.getHorasPermiso() != null ? p.getHorasPermiso() : java.math.BigDecimal.ZERO,
                    pagosTxt
            ));
        });

        todosDtos.sort((a, b) -> {
            if (a.fechaInicio() == null || b.fechaInicio() == null) return 0;
            int cmp = b.fechaInicio().compareTo(a.fechaInicio());
            if (cmp == 0 && b.fechaCreacion() != null && a.fechaCreacion() != null) {
                return b.fechaCreacion().compareTo(a.fechaCreacion());
            }
            return cmp;
        });

        List<com.hrms.vacaciones.dto.SolicitudDTO> listaFiltrada = todosDtos;
        if (inicio != null && fin != null) {
            listaFiltrada = todosDtos.stream()
                    .filter(s -> s.fechaInicio() != null && s.fechaFin() != null &&
                            !s.fechaInicio().isBefore(inicio) && !s.fechaFin().isAfter(fin))
                    .collect(Collectors.toList());
        }

        List<com.hrms.vacaciones.dto.SolicitudDTO> listaVacaciones = listaFiltrada.stream()
                .filter(s -> {
                    if (s.tipoSolicitud() == null) return false;
                    String tipo = s.tipoSolicitud().toUpperCase();
                    if (tipo.contains("VACACION") || tipo.trim().equalsIgnoreCase("V")) return true;
                    if (tipo.contains("FALTA")) {
                        String comentario = s.comentarioJefe() != null ? s.comentarioJefe().toUpperCase() : "";
                        return !(comentario.contains("[TXT]") || comentario.contains("[HO]") ||
                                comentario.contains("[C]") || comentario.contains("[P]") ||
                                comentario.contains("[TET]"));
                    }
                    return false;
                })
                .collect(Collectors.toList());

        List<com.hrms.vacaciones.dto.SolicitudDTO> listaPermisos = listaFiltrada.stream()
                .filter(s -> {
                    if (s.tipoSolicitud() == null) return true;
                    String tipo = s.tipoSolicitud().toUpperCase();
                    if (tipo.contains("VACACION") || tipo.trim().equalsIgnoreCase("V")) return false;
                    if (tipo.contains("FALTA")) {
                        String comentario = s.comentarioJefe() != null ? s.comentarioJefe().toUpperCase() : "";
                        return (comentario.contains("[TXT]") || comentario.contains("[HO]") ||
                                comentario.contains("[C]") || comentario.contains("[P]") ||
                                comentario.contains("[TET]"));
                    }
                    return true;
                })
                .collect(Collectors.toList());

        if (inicio == null || fin == null) {
            listaVacaciones = listaVacaciones.stream().limit(5).collect(Collectors.toList());
            listaPermisos = listaPermisos.stream().limit(5).collect(Collectors.toList());
        }

        java.util.Map<String, List<com.hrms.vacaciones.dto.SolicitudDTO>> resultado = new java.util.HashMap<>();
        resultado.put("vacaciones", listaVacaciones);
        resultado.put("permisos", listaPermisos);

        return resultado;
    }

    public List<Empleado> obtenerShiftLeadersDeSupervisor(Integer nominaSupervisor) {
        return empleadoRepository.findByJefeDirectoNomina(nominaSupervisor).stream()
                .filter(e -> e.getRolJerarquico() != null && "SHIFT LEADER".equalsIgnoreCase(e.getRolJerarquico().trim()))
                .collect(Collectors.toList());
    }

    public java.util.Map<Integer, Long> obtenerHeadcountPorWorkCenter(List<Empleado> plantilla) {
        return plantilla.stream()
                .filter(e -> e.getWorkCenter() != null && e.getEstatus() != null && "ACTIVO".equalsIgnoreCase(e.getEstatus().trim()))
                .collect(Collectors.groupingBy(e -> e.getWorkCenter().getId(), Collectors.counting()));
    }

    public java.util.Map<String, Object> obtenerDatosRankingDashboard(Empleado empleado) {
        java.util.Map<String, Object> datos = new java.util.HashMap<>();
        boolean esSind = "SINDICALIZADO".equalsIgnoreCase(empleado.getTipoEmpleado());
        datos.put("esSindicalizado", esSind);

        if (!esSind) {
            return datos;
        }

        BigDecimal saldoDevengadoReal = empleado.getSaldoVacacionesActual() != null ? empleado.getSaldoVacacionesActual() : BigDecimal.ZERO;
        Integer supervisorNomina = (empleado.getWorkCenter() != null) ? empleado.getWorkCenter().getSupervisorNomina() : null;

        int posicionRanking = 0;
        if (supervisorNomina != null) {
            posicionRanking = empleadoRepository.obtenerPosicionRankingSupervisorVivo(
                    supervisorNomina, saldoDevengadoReal, empleado.getNomina());
        }
        datos.put("posicionRanking", posicionRanking);

        LocalDate hoy = LocalDate.now();
        int mesConfig = hoy.getMonthValue();
        int anioConfig = hoy.getYear();

        // ✨ BISTURÍ DASHBOARD: Conectamos la misma lógica del Robot
        java.util.Map<String, LocalDate> semanaVip = calcularSemanaVIP(mesConfig, anioConfig);
        LocalDate fechaAperturaRezago = semanaVip.get("inicio");

        // Si ya llegamos a la semana VIP de este mes (o la pasamos), proyectamos hacia el próximo mes
        if (!hoy.isBefore(fechaAperturaRezago)) {
            LocalDate proximoMes = hoy.plusMonths(1);
            mesConfig = proximoMes.getMonthValue();
            anioConfig = proximoMes.getYear();

            // Recalculamos la ventana VIP para el próximo mes (para el anuncio del modal)
            semanaVip = calcularSemanaVIP(mesConfig, anioConfig);
        }

        Integer turnoId = empleado.getTurno() != null ? empleado.getTurno().getId() : 1;

        java.util.Optional<CapacidadVacacionesMes> configOpt = java.util.Optional.empty();
        if (supervisorNomina != null) {
            configOpt = capacidadRepo.findBySupervisorNominaAndMesAndAnioAndTurno_Id(
                    supervisorNomina, mesConfig, anioConfig, turnoId);
        }

        // ✨ NUEVA LÓGICA VIP SMART V3: Boleto VIP Congelado
        String diaVIPAsignado = "SÁBADO (Repechaje Libre)";
        boolean perteneceAlTop = false;

        if (supervisorNomina != null && saldoDevengadoReal.compareTo(BigDecimal.ZERO) > 0) {
            java.util.Optional<BoletoVip> miBoleto = boletoVipRepository.findByNominaEmpleadoAndMesAndAnio(empleado.getNomina(), mesConfig, anioConfig);
            if (miBoleto.isPresent()) {
                diaVIPAsignado = miBoleto.get().getDiaAsignado();
                perteneceAlTop = true;
            } else {
                diaVIPAsignado = "SÁBADO (Repechaje Libre)";
            }
        } else if (saldoDevengadoReal.compareTo(BigDecimal.ZERO) <= 0) {
            diaVIPAsignado = "SIN ACCESO (Saldo Insuficiente)";
            perteneceAlTop = false;
        }

        datos.put("diaVIPAsignado", diaVIPAsignado);
        datos.put("perteneceAlTop", perteneceAlTop);

        if (configOpt.isPresent()) {
            CapacidadVacacionesMes config = configOpt.get();
            boolean enVentanaVIP = !hoy.isBefore(config.getFechaAperturaRezago()) && hoy.isBefore(config.getFechaAperturaGeneral());

            datos.put("fechaAperturaRezago", config.getFechaAperturaRezago());
            datos.put("fechaAperturaGeneral", config.getFechaAperturaGeneral());
            datos.put("mostrarModalVIPAnuncio", enVentanaVIP && perteneceAlTop);
        } else {
            datos.put("mostrarModalVIPAnuncio", false);
        }

        return datos;
    }

    // 💡 Método auxiliar para leer el Switch Maestro en DB
    public boolean isEscalamientoAutomaticoHabilitado() {
        return configuracionSistemaRepository.findById("ESCALAMIENTO_AUTOMATICO")
                .map(c -> "TRUE".equalsIgnoreCase(c.getValor()) || "ENABLED".equalsIgnoreCase(c.getValor()) || "1".equals(c.getValor()))
                .orElse(false);
    }

    // =========================================================================
    // ⚙️ MÓDULO: GRUPOS DE PROCESO (EL CADENERO V2)
    // =========================================================================

    public List<CentroCosto> obtenerCentrosCostoPorJefe(Integer nominaJefe) {
        Empleado jefe = empleadoRepository.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Jefe no encontrado"));

        java.util.Set<CentroCosto> misCentros = new java.util.HashSet<>();

        // 1. Centros de Costo directos del Supervisor
        if (jefe.getCentrosCostoACargo() != null) {
            misCentros.addAll(jefe.getCentrosCostoACargo());
        }

        // 2. Centros de Costo heredados de sus Shift Leaders
        List<Empleado> shiftLeaders = empleadoRepository.findByJefeDirectoNomina(nominaJefe);
        for (Empleado sl : shiftLeaders) {
            if (sl.getCentrosCostoACargo() != null) {
                misCentros.addAll(sl.getCentrosCostoACargo());
            }
        }
        return new java.util.ArrayList<>(misCentros);
    }

    public List<GrupoProceso> obtenerGruposProcesoPorJefe(Integer nominaJefe) {
        Empleado empleado = empleadoRepository.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Empleado no encontrado"));

        String rol = empleado.getRolJerarquico() != null ? empleado.getRolJerarquico().trim().toUpperCase() : "";

        if ("SUPERVISOR".equals(rol)) {
            // ✨ CERO MOCKS: El Supervisor ve SOLO los grupos que él creó
            return grupoProcesoRepository.findAll().stream()
                    .filter(g -> g.getSupervisorNomina() != null && g.getSupervisorNomina().equals(nominaJefe))
                    .collect(Collectors.toList());
        }

        // 🧠 ESTRATEGIA DEFINITIVA (BALA DE PLATA): Usar la columna numérica nativa
        Integer nominaSupervisor = empleado.getJefeDirectoNomina();

        // Respaldo de seguridad por si la ficha no tiene jefe directo configurado
        if (nominaSupervisor == null && empleado.getWorkCentersACargo() != null) {
            nominaSupervisor = empleado.getWorkCentersACargo().stream()
                    .map(WorkCenter::getSupervisorNomina)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        if (nominaSupervisor == null) {
            return new ArrayList<>(); // Si de plano el operador es huérfano contable
        }

        // ✨ ALIMENTAMOS EL RADAR CON LA MATRIZ DEL SUPERVISOR
        // Esto le da al Shift Leader opciones reales en su desplegable
        Integer finalSupervisor = nominaSupervisor;
        return grupoProcesoRepository.findAll().stream()
                .filter(g -> g.getSupervisorNomina() != null && g.getSupervisorNomina().equals(finalSupervisor))
                .collect(Collectors.toList());
    }

    @Transactional
    public void crearGrupoProceso(String nombre, Integer cupoMaximo, String tipoAgrupacion, Integer cupoMaximoTurno, List<Integer> centroCostoIds, List<Integer> workCenterIds, Integer nominaJefe) {
        GrupoProceso nuevoGrupo = new GrupoProceso();
        nuevoGrupo.setNombre(nombre.trim().toUpperCase());
        nuevoGrupo.setCupoMaximo(cupoMaximo);
        nuevoGrupo.setSupervisorNomina(nominaJefe);
        nuevoGrupo.setTipoAgrupacion(tipoAgrupacion != null ? tipoAgrupacion : "CC");

        if ("CC".equalsIgnoreCase(tipoAgrupacion)) {
            if (centroCostoIds == null || centroCostoIds.isEmpty()) throw new IllegalArgumentException("Debes seleccionar al menos un Centro de Costo (CC).");
            nuevoGrupo.setCentrosCosto(centroCostoRepository.findAllById(centroCostoIds));
        } else if ("WC".equalsIgnoreCase(tipoAgrupacion)) {
            if (workCenterIds == null || workCenterIds.isEmpty()) throw new IllegalArgumentException("Debes seleccionar al menos un Work Center (WC).");
            if (cupoMaximoTurno == null || cupoMaximoTurno < 1) throw new IllegalArgumentException("Debes especificar un cupo máximo por turno para el grupo de WCs.");
            nuevoGrupo.setCupoMaximoTurno(cupoMaximoTurno);
            nuevoGrupo.setWorkCenters(workCenterRepository.findAllById(workCenterIds));
        }

        grupoProcesoRepository.save(nuevoGrupo);
    }

    @Transactional
    public void eliminarGrupoProceso(Integer idGrupo) {
        GrupoProceso grupo = grupoProcesoRepository.findById(idGrupo)
                .orElseThrow(() -> new IllegalArgumentException("El grupo no existe."));

        if (grupo.getCentrosCosto() != null) grupo.getCentrosCosto().clear();
        if (grupo.getWorkCenters() != null) grupo.getWorkCenters().clear();

        grupoProcesoRepository.save(grupo);
        grupoProcesoRepository.delete(grupo);
    }

    // =========================================================================
    // 📊 DISTRIBUCIÓN VIP SMART V3 CON CONSOLIDADOR DE REPECHAJE INTELIGENTE
    // =========================================================================
    public java.util.Map<String, java.util.Map<String, List<Empleado>>> obtenerDistribucionQuintiles(Integer nominaJefe) {

        java.util.Map<String, java.util.Map<String, List<Empleado>>> distribucionPorGrupo = new java.util.LinkedHashMap<>();
        List<GrupoProceso> misGrupos = obtenerGruposProcesoPorJefe(nominaJefe);
        String[] diasApertura = {"LUNES", "MARTES", "MIÉRCOLES", "JUEVES", "VIERNES"};

        for (GrupoProceso grupo : misGrupos) {
            java.util.Map<String, List<Empleado>> distribucion = new java.util.LinkedHashMap<>();
            for (String dia : diasApertura) {
                distribucion.put(dia, new ArrayList<>());
            }

            List<Integer> idsCcGrupo = grupo.getCentrosCosto().stream()
                    .map(CentroCosto::getId)
                    .collect(Collectors.toList());

            List<Empleado> plantillaGrupo = obtenerPlantillaDelJefe(nominaJefe).stream()
                    .filter(e -> e.getEstatus() != null && "ACTIVO".equalsIgnoreCase(e.getEstatus()))
                    .filter(e -> "SINDICALIZADO".equalsIgnoreCase(e.getTipoEmpleado()))
                    .filter(e -> e.getCentroCosto() != null && idsCcGrupo.contains(e.getCentroCosto().getId()))
                    .filter(e -> e.getSaldoVacacionesActual() != null && e.getSaldoVacacionesActual().compareTo(BigDecimal.ZERO) > 0)
                    .sorted((a, b) -> {
                        int cmp = b.getSaldoVacacionesActual().compareTo(a.getSaldoVacacionesActual());
                        if (cmp == 0) {
                            LocalDate fechaA = a.getFechaIngreso() != null ? a.getFechaIngreso() : LocalDate.MAX;
                            LocalDate fechaB = b.getFechaIngreso() != null ? b.getFechaIngreso() : LocalDate.MAX;
                            return fechaA.compareTo(fechaB);
                        }
                        return cmp;
                    })
                    .collect(Collectors.toList());

            if (plantillaGrupo.isEmpty()) {
                distribucionPorGrupo.put(grupo.getNombre(), distribucion);
                continue;
            }

            // 🧠 ALGORITMO DINÁMICO Y REPECHAJE SMART V3
            int totalEmpleados = plantillaGrupo.size();
            double cuotaIdealOriginal = totalEmpleados / 5.0;
            double cuotaDinamica = cuotaIdealOriginal;

            int diaActualIndex = 0;
            int asignadosEnElDia = 0;
            BigDecimal saldoAnterior = null;

            for (int i = 0; i < totalEmpleados; i++) {
                Empleado emp = plantillaGrupo.get(i);
                BigDecimal saldoActual = emp.getSaldoVacacionesActual();

                // 🛡️ ESCUDO AGRUPADOR (Solo evaluamos avanzar si el saldo cambia)
                if (saldoAnterior != null && saldoActual.compareTo(saldoAnterior) != 0) {

                    // ¿Ya llenamos la cuota del día actual?
                    if (asignadosEnElDia >= Math.ceil(cuotaDinamica) && diaActualIndex < 4) {
                        int genteRestante = totalEmpleados - i;
                        boolean forzarRepechaje = false;

                        // 🔍 ANÁLISIS DE REPECHAJE
                        if (diaActualIndex == 2) {
                            // Si terminamos Miércoles, ¿lo que sobra cabe en UN solo día? (Permitimos 20% de tolerancia)
                            // Ej: Si el ideal es 10, y sobran 11 o 12, los junta en Jueves. Si sobran 13, los parte Jueves y Viernes.
                            if (genteRestante <= Math.ceil(cuotaIdealOriginal * 1.2)) {
                                forzarRepechaje = true;
                            }
                        } else if (diaActualIndex == 3) {
                            // Si terminamos Jueves, ¿quedan muy poquitos para el Viernes? (Menos del 30% o 3 personas)
                            if (genteRestante <= Math.max(3, cuotaIdealOriginal * 0.3)) {
                                forzarRepechaje = true;
                            }
                        }

                        if (forzarRepechaje) {
                            if (diaActualIndex == 2) {
                                diaActualIndex = 3; // Brincamos a Jueves
                                asignadosEnElDia = 0;
                            }
                            // Congelamos el avance: Jueves absorbe a todos (o Viernes se queda en 0)
                            cuotaDinamica = Double.MAX_VALUE;
                        } else {
                            // Avance Normal Equitativo (Auto-Balanceo)
                            diaActualIndex++;
                            int diasRestantes = 5 - diaActualIndex;
                            cuotaDinamica = genteRestante / (double) diasRestantes; // Recalcula la meta para los días que faltan
                            asignadosEnElDia = 0;
                        }
                    }
                }

                distribucion.get(diasApertura[diaActualIndex]).add(emp);
                asignadosEnElDia++;
                saldoAnterior = saldoActual;
            }

            distribucionPorGrupo.put(grupo.getNombre(), distribucion);
        }

        return distribucionPorGrupo;
    }

    // =========================================================================
    // 🧮 MÓDULO: DETERMINADOR AUTOMÁTICO DE LA SEMANA VIP
    // =========================================================================
    public java.util.Map<String, LocalDate> calcularSemanaVIP(Integer mesConfig, Integer anioConfig) {
        // La semana VIP para disfrutar vacaciones en el mes "mesConfig" ocurre
        // en la ÚLTIMA semana completa (Lunes a Viernes) del mes ANTERIOR.

        LocalDate primeroMesConfig = LocalDate.of(anioConfig, mesConfig, 1);
        LocalDate ultimoDiaMesAnterior = primeroMesConfig.minusDays(1);

        // Retrocedemos hasta encontrar el último viernes del mes anterior
        LocalDate viernesVIP = ultimoDiaMesAnterior;
        while (viernesVIP.getDayOfWeek() != java.time.DayOfWeek.FRIDAY) {
            viernesVIP = viernesVIP.minusDays(1);
        }

        // El lunes de esa misma semana
        LocalDate lunesVIP = viernesVIP.minusDays(4);

        java.util.Map<String, LocalDate> resultado = new java.util.HashMap<>();
        resultado.put("inicio", lunesVIP);
        resultado.put("fin", viernesVIP);

        return resultado;
    }

    // =========================================================================
    // 🍿 API CINÉPOLIS: ESCANER DE DÍAS BLOQUEADOS (Próximos 90 días)
    // =========================================================================
    public List<String> obtenerDiasBloqueadosCinepolis(Integer nominaEmpleado, Integer turnoId) {
        List<String> diasBloqueados = new ArrayList<>();
        Empleado empleado = empleadoRepository.findById(nominaEmpleado).orElse(null);

        if (empleado == null || empleado.getWorkCenter() == null || empleado.getWorkCenter().getSupervisorNomina() == null) {
            return diasBloqueados;
        }

        Integer supervisorNomina = empleado.getWorkCenter().getSupervisorNomina();
        LocalDate hoy = LocalDate.now();
        LocalDate limiteBusqueda = hoy.plusMonths(3); // Escaneamos 90 días al futuro

        // 1. Blackout Dates (Periodos Inhábiles del Supervisor)
        List<PeriodoInhabil> bloqueos = periodoInhabilRepository.findBySupervisorNominaOrderByFechaInicioDesc(supervisorNomina);
        for (PeriodoInhabil bloqueo : bloqueos) {
            LocalDate cursor = bloqueo.getFechaInicio();
            while (!cursor.isAfter(bloqueo.getFechaFin())) {
                diasBloqueados.add(cursor.toString());
                cursor = cursor.plusDays(1);
            }
        }

        // 2. Cupos de Grupo de Proceso y Choques de Turno Híbrido
        GrupoProceso grupoAfectado = null;
        List<GrupoProceso> gruposDelJefe = obtenerGruposProcesoPorJefe(supervisorNomina);

        for (GrupoProceso g : gruposDelJefe) {
            if ("CC".equalsIgnoreCase(g.getTipoAgrupacion()) && empleado.getCentroCosto() != null) {
                if (g.getCentrosCosto().stream().anyMatch(cc -> cc.getId().equals(empleado.getCentroCosto().getId()))) {
                    grupoAfectado = g; break;
                }
            } else if ("WC".equalsIgnoreCase(g.getTipoAgrupacion()) && empleado.getWorkCenter() != null) {
                if (g.getWorkCenters().stream().anyMatch(wc -> wc.getId().equals(empleado.getWorkCenter().getId()))) {
                    grupoAfectado = g; break;
                }
            }
        }

        int cupoMaximo = grupoAfectado != null ? grupoAfectado.getCupoMaximo() : 999;

        // --- INICIO AJUSTE ESCÁNER RETROACTIVO ---
        String tipoEmpleado = empleado.getTipoEmpleado() != null ? empleado.getTipoEmpleado() : "SINDICALIZADO";
        ConfiguracionCorteNomina configCorte = configuracionCorteNominaRepository.findByTipoEmpleado(tipoEmpleado).orElse(null);

        LocalDate fechaMinima = hoy.with(java.time.DayOfWeek.MONDAY);
        if (configCorte != null) {
            LocalDateTime ahora = LocalDateTime.now();
            java.time.DayOfWeek diaCorte = java.time.DayOfWeek.of(configCorte.getDiaCorte() != null ? configCorte.getDiaCorte() : 2);
            java.time.LocalTime horaCorte = configCorte.getHoraCorte() != null ? configCorte.getHoraCorte() : java.time.LocalTime.of(16, 0);

            LocalDateTime limiteGuillotina = ahora.with(java.time.DayOfWeek.MONDAY)
                    .plusDays(diaCorte.getValue() - 1)
                    .toLocalDate()
                    .atTime(horaCorte);

            if (ahora.isBefore(limiteGuillotina)) {
                fechaMinima = ahora.with(java.time.DayOfWeek.MONDAY).minusWeeks(1).toLocalDate();
            }
        }
        LocalDate cursor = fechaMinima;
        // --- FIN AJUSTE ---

        // ✨ BISTURÍ 1: Calcular regla VIP para meses futuros
        boolean esSemanaVip = calendarioService.esSemanaAperturaVip(hoy);
        java.time.DayOfWeek diaQuintil = null;
        if (esSemanaVip) {
            int mesProx = hoy.plusMonths(1).getMonthValue();
            int anioProx = hoy.plusMonths(1).getYear();
            java.util.Optional<BoletoVip> miBoleto = boletoVipRepository.findByNominaEmpleadoAndMesAndAnio(nominaEmpleado, mesProx, anioProx);
            if (miBoleto.isPresent()) {
                switch(miBoleto.get().getDiaAsignado()) {
                    case "LUNES": diaQuintil = java.time.DayOfWeek.MONDAY; break;
                    case "MARTES": diaQuintil = java.time.DayOfWeek.TUESDAY; break;
                    case "MIÉRCOLES": diaQuintil = java.time.DayOfWeek.WEDNESDAY; break;
                    case "JUEVES": diaQuintil = java.time.DayOfWeek.THURSDAY; break;
                    case "VIERNES": diaQuintil = java.time.DayOfWeek.FRIDAY; break;
                }
            }
        }

        while (!cursor.isAfter(limiteBusqueda)) {
            final LocalDate diaEval = cursor;

            // ✨ BISTURÍ 2: Bloquear solo si es mes futuro y no es su turno VIP
            if (esSemanaVip && (diaEval.getMonthValue() != hoy.getMonthValue() || diaEval.getYear() != hoy.getYear())) {
                if (hoy.getDayOfWeek() != diaQuintil && hoy.getDayOfWeek() != java.time.DayOfWeek.SATURDAY) {
                    diasBloqueados.add(diaEval.toString());
                    cursor = cursor.plusDays(1);
                    continue;
                }
            }

            if (!diasBloqueados.contains(diaEval.toString())) {
                if (grupoAfectado != null) {
                    if ("WC".equalsIgnoreCase(grupoAfectado.getTipoAgrupacion())) {
                        List<Integer> wcIdsDelGrupo = grupoAfectado.getWorkCenters().stream().map(WorkCenter::getId).collect(Collectors.toList());

                        List<SolicitudVacaciones> vivasDelGrupo = solicitudVacacionesRepository.findAll().stream()
                                .filter(s -> !List.of("RECHAZADO", "CANCELADO").contains(s.getEstatus().toUpperCase()))
                                .filter(s -> s.getTipoSolicitud() != null && (s.getTipoSolicitud().toUpperCase().contains("VACACION") || s.getTipoSolicitud().trim().equalsIgnoreCase("V")))
                                .filter(s -> s.getEmpleado().getWorkCenter() != null && wcIdsDelGrupo.contains(s.getEmpleado().getWorkCenter().getId()))
                                .filter(s -> !diaEval.isBefore(s.getFechaInicio()) && !diaEval.isAfter(s.getFechaFin()))
                                .collect(Collectors.toList());

                        long ocupadosGrupo = vivasDelGrupo.size();
                        if (ocupadosGrupo >= cupoMaximo) {
                            diasBloqueados.add(diaEval.toString());
                            cursor = cursor.plusDays(1);
                            continue;
                        }

                        long ocupadosTurno = vivasDelGrupo.stream().filter(s -> s.getTurno() != null && s.getTurno().getId().equals(turnoId)).count();
                        int limiteTurno = (grupoAfectado.getCupoMaximoTurno() != null) ? grupoAfectado.getCupoMaximoTurno() : 1;
                        if (ocupadosTurno >= limiteTurno) {
                            diasBloqueados.add(diaEval.toString());
                        }

                    } else {
                        List<Integer> ccIds = grupoAfectado.getCentrosCosto().stream().map(CentroCosto::getId).collect(Collectors.toList());
                        long ocupadosGrupo = solicitudVacacionesRepository.countVacacionesPorGrupoYFecha(ccIds, diaEval);
                        if (ocupadosGrupo >= cupoMaximo) {
                            diasBloqueados.add(diaEval.toString());
                            cursor = cursor.plusDays(1);
                            continue;
                        }

                        int cupoWc = (empleado.getWorkCenter().getCupoConcurrenteTurno() != null && empleado.getWorkCenter().getCupoConcurrenteTurno() > 0)
                                ? empleado.getWorkCenter().getCupoConcurrenteTurno() : 1;
                        long ocupadosMismoWcTurno = solicitudVacacionesRepository.contarOcupadosPorLineaYTurno(empleado.getWorkCenter().getId(), turnoId, diaEval);
                        if (ocupadosMismoWcTurno >= cupoWc) {
                            diasBloqueados.add(diaEval.toString());
                        }
                    }
                } else {
                    int cupoWc = (empleado.getWorkCenter().getCupoConcurrenteTurno() != null && empleado.getWorkCenter().getCupoConcurrenteTurno() > 0)
                            ? empleado.getWorkCenter().getCupoConcurrenteTurno() : 1;
                    long ocupadosMismoWcTurno = solicitudVacacionesRepository.contarOcupadosPorLineaYTurno(empleado.getWorkCenter().getId(), turnoId, diaEval);
                    if (ocupadosMismoWcTurno >= cupoWc) {
                        diasBloqueados.add(diaEval.toString());
                    }
                }
            }
            cursor = cursor.plusDays(1);
        }

        return diasBloqueados.stream().distinct().collect(Collectors.toList());
    }

    // =========================================================================
    // 🎟️ MOTOR DE LA CARTELERA: ANÁLISIS DE ASIENTOS POR DÍA
    // =========================================================================
    public Map<String, Object> obtenerCarteleraPorFecha(Integer nominaEmpleado, LocalDate fecha) {
        Map<String, Object> respuesta = new HashMap<>();
        Empleado empleado = empleadoRepository.findById(nominaEmpleado)
                .orElseThrow(() -> new RuntimeException("Empleado no encontrado."));

        if (empleado.getWorkCenter() == null || empleado.getWorkCenter().getSupervisorNomina() == null) {
            respuesta.put("estatusGlobal", "ERROR");
            respuesta.put("mensaje", "No cuenta con una línea de trabajo o supervisor asignado en su perfil.");
            return respuesta;
        }

        Integer supervisorNomina = empleado.getWorkCenter().getSupervisorNomina();
        LocalDate hoy = LocalDate.now();

        // 1. REVISIÓN DE SEMANA VIP (Boleto Congelado)
        if (calendarioService.esSemanaAperturaVip(hoy)) {
            // ✨ BISTURÍ 3: El mes en curso es de libre tránsito. Solo blindamos meses futuros.
            if (fecha.getMonthValue() != hoy.getMonthValue() || fecha.getYear() != hoy.getYear()) {
                java.time.DayOfWeek diaQuintil = null;
                java.util.Optional<BoletoVip> miBoleto = boletoVipRepository.findByNominaEmpleadoAndMesAndAnio(nominaEmpleado, fecha.getMonthValue(), fecha.getYear());

                if (miBoleto.isPresent()) {
                    switch(miBoleto.get().getDiaAsignado()) {
                        case "LUNES": diaQuintil = java.time.DayOfWeek.MONDAY; break;
                        case "MARTES": diaQuintil = java.time.DayOfWeek.TUESDAY; break;
                        case "MIÉRCOLES": diaQuintil = java.time.DayOfWeek.WEDNESDAY; break;
                        case "JUEVES": diaQuintil = java.time.DayOfWeek.THURSDAY; break;
                        case "VIERNES": diaQuintil = java.time.DayOfWeek.FRIDAY; break;
                    }
                }

                if (hoy.getDayOfWeek() != diaQuintil && hoy.getDayOfWeek() != java.time.DayOfWeek.SATURDAY) {
                    respuesta.put("estatusGlobal", "BLOQUEADO_VIP");
                    respuesta.put("mensaje", "Apertura VIP: Aún no es tu turno para apartar días del próximo mes. Regresa el día asignado en tu boleto.");
                    return respuesta;
                }
            }
        }

        // 2. REVISIÓN DE PERIODOS INHÁBILES (BLACKOUT DATES)
        List<PeriodoInhabil> bloqueos = periodoInhabilRepository.findBySupervisorNominaOrderByFechaInicioDesc(supervisorNomina);
        boolean esInhabil = bloqueos.stream().anyMatch(b -> !fecha.isBefore(b.getFechaInicio()) && !fecha.isAfter(b.getFechaFin()));
        if (esInhabil) {
            respuesta.put("estatusGlobal", "INHABIL");
            respuesta.put("mensaje", "La fecha seleccionada corresponde a un periodo inhábil o de inactividad programada para su área.");
            return respuesta;
        }

        // ✨ NUEVO: REVISIÓN DE DÍAS FESTIVOS PARA LA CARTELERA
        java.util.Optional<DiasFestivos> feriadoOpt = diasFestivosRepository.findByActivoTrue().stream()
                .filter(f -> f.getFecha().equals(fecha))
                .findFirst();
        if (feriadoOpt.isPresent()) {
            respuesta.put("estatusGlobal", "FERIADO");
            respuesta.put("mensaje", "Día festivo oficial: " + feriadoOpt.get().getDescripcion() + " (No consume saldo de vacaciones).");
            return respuesta;
        }
        // ✨ 3. REVISIÓN HÍBRIDA DE CAPACIDAD DEL GRUPO DE PROCESO
        GrupoProceso grupoAfectado = null;
        List<GrupoProceso> gruposDelJefe = obtenerGruposProcesoPorJefe(supervisorNomina);

        for (GrupoProceso g : gruposDelJefe) {
            if ("CC".equalsIgnoreCase(g.getTipoAgrupacion()) && empleado.getCentroCosto() != null) {
                if (g.getCentrosCosto().stream().anyMatch(cc -> cc.getId().equals(empleado.getCentroCosto().getId()))) {
                    grupoAfectado = g; break;
                }
            } else if ("WC".equalsIgnoreCase(g.getTipoAgrupacion()) && empleado.getWorkCenter() != null) {
                if (g.getWorkCenters().stream().anyMatch(wc -> wc.getId().equals(empleado.getWorkCenter().getId()))) {
                    grupoAfectado = g; break;
                }
            }
        }

        String nombreGrupo = grupoAfectado != null ? grupoAfectado.getNombre() : "Sin Grupo Asignado";
        int cupoMaximo = grupoAfectado != null ? grupoAfectado.getCupoMaximo() : 999;
        long ocupadosEnElDia = 0;

        if (grupoAfectado != null) {
            if ("WC".equalsIgnoreCase(grupoAfectado.getTipoAgrupacion())) {
                List<Integer> wcIdsDelGrupo = grupoAfectado.getWorkCenters().stream().map(WorkCenter::getId).collect(Collectors.toList());
                ocupadosEnElDia = solicitudVacacionesRepository.findAll().stream()
                        .filter(s -> !List.of("RECHAZADO", "CANCELADO").contains(s.getEstatus().toUpperCase()))
                        .filter(s -> s.getTipoSolicitud() != null && (s.getTipoSolicitud().toUpperCase().contains("VACACION") || s.getTipoSolicitud().trim().equalsIgnoreCase("V")))
                        .filter(s -> s.getEmpleado().getWorkCenter() != null && wcIdsDelGrupo.contains(s.getEmpleado().getWorkCenter().getId()))
                        .filter(s -> !fecha.isBefore(s.getFechaInicio()) && !fecha.isAfter(s.getFechaFin()))
                        .count();
            } else {
                List<Integer> ccIdsDelGrupo = grupoAfectado.getCentrosCosto().stream().map(CentroCosto::getId).collect(Collectors.toList());
                ocupadosEnElDia = solicitudVacacionesRepository.countVacacionesPorGrupoYFecha(ccIdsDelGrupo, fecha);
            }
        }

        respuesta.put("grupoNombre", nombreGrupo);
        respuesta.put("cupoMaximo", cupoMaximo);
        respuesta.put("ocupadosGrupo", ocupadosEnElDia);

        if (ocupadosEnElDia >= cupoMaximo) {
            respuesta.put("estatusGlobal", "GRUPO_LLENO");
            respuesta.put("mensaje", "La capacidad máxima de ausencias simultáneas para su bloque operativo ha sido alcanzada.");
            return respuesta;
        }

        // ✨ 4. REVISIÓN DE CADA TURNO (LOS ASIENTOS HÍBRIDOS)
        respuesta.put("estatusGlobal", "DISPONIBLE");
        respuesta.put("mensaje", "Existe capacidad en su bloque. Revise la disponibilidad de su turno específico:");

        List<Turno> turnosActivos = turnoRepository.findByActivoTrue();
        List<Map<String, Object>> butacas = new ArrayList<>();

        for (Turno t : turnosActivos) {
            boolean ocupado;

            if (grupoAfectado != null && "WC".equalsIgnoreCase(grupoAfectado.getTipoAgrupacion())) {
                List<Integer> wcIdsDelGrupo = grupoAfectado.getWorkCenters().stream().map(WorkCenter::getId).collect(Collectors.toList());
                long ocupadosEnElTurno = solicitudVacacionesRepository.findAll().stream()
                        .filter(s -> !List.of("RECHAZADO", "CANCELADO").contains(s.getEstatus().toUpperCase()))
                        .filter(s -> s.getTipoSolicitud() != null && (s.getTipoSolicitud().toUpperCase().contains("VACACION") || s.getTipoSolicitud().trim().equalsIgnoreCase("V")))
                        .filter(s -> s.getEmpleado().getWorkCenter() != null && wcIdsDelGrupo.contains(s.getEmpleado().getWorkCenter().getId()))
                        .filter(s -> s.getTurno() != null && s.getTurno().getId().equals(t.getId()))
                        .filter(s -> !fecha.isBefore(s.getFechaInicio()) && !fecha.isAfter(s.getFechaFin()))
                        .count();

                int limiteTurno = (grupoAfectado.getCupoMaximoTurno() != null) ? grupoAfectado.getCupoMaximoTurno() : 1;
                ocupado = ocupadosEnElTurno >= limiteTurno;
            } else {
                int cupoWc = (empleado.getWorkCenter().getCupoConcurrenteTurno() != null) ? empleado.getWorkCenter().getCupoConcurrenteTurno() : 1;
                long ocupadosTurno = solicitudVacacionesRepository.contarOcupadosPorLineaYTurno(empleado.getWorkCenter().getId(), t.getId(), fecha);
                ocupado = ocupadosTurno >= cupoWc;
            }

            Map<String, Object> butaca = new HashMap<>();
            butaca.put("turnoId", t.getId());
            butaca.put("turnoNombre", t.getNombreTurno());
            butaca.put("ocupado", ocupado);
            butacas.add(butaca);
        }

        respuesta.put("turnos", butacas);
        return respuesta;
    }

    // =========================================================================
    // 🎟️ MOTOR DE LA CARTELERA: ESCANEO POR RANGO Y TURNO ESPECÍFICO
    // =========================================================================
    public List<Map<String, Object>> obtenerCarteleraPorRango(Integer nominaEmpleado, LocalDate inicio, LocalDate fin, Integer turnoId) {
        List<Map<String, Object>> listaReportes = new ArrayList<>();
        LocalDate cursor = inicio;

        while (!cursor.isAfter(fin)) {
            Map<String, Object> reporteDia = obtenerCarteleraPorFecha(nominaEmpleado, cursor, turnoId); // Pasamos el turnoId
            reporteDia.put("fechaAfectada", cursor.toString());
            listaReportes.add(reporteDia);
            cursor = cursor.plusDays(1);
        }
        return listaReportes;
    }

    public Map<String, Object> obtenerCarteleraPorFecha(Integer nominaEmpleado, LocalDate fecha, Integer turnoId) {
        Map<String, Object> respuesta = new HashMap<>();
        Empleado empleado = empleadoRepository.findById(nominaEmpleado)
                .orElseThrow(() -> new RuntimeException("Empleado no encontrado."));

        if (empleado.getWorkCenter() == null || empleado.getWorkCenter().getSupervisorNomina() == null) {
            respuesta.put("estatusGlobal", "ERROR");
            respuesta.put("mensaje", "No cuenta con una línea de trabajo o supervisor asignado en su perfil.");
            return respuesta;
        }

        Integer supervisorNomina = empleado.getWorkCenter().getSupervisorNomina();
        LocalDate hoy = LocalDate.now();

        // 1. REVISIÓN DE SEMANA VIP
        if (calendarioService.esSemanaAperturaVip(hoy)) {
            // ✨ BISTURÍ 4: El mes en curso es de libre tránsito. Solo blindamos meses futuros.
            if (fecha.getMonthValue() != hoy.getMonthValue() || fecha.getYear() != hoy.getYear()) {
                BigDecimal saldo = empleado.getSaldoVacacionesActual() != null ? empleado.getSaldoVacacionesActual() : BigDecimal.ZERO;
                int posicion = empleadoRepository.obtenerPosicionRankingSupervisorVivo(supervisorNomina, saldo, nominaEmpleado);
                int offsetDia = Math.min(((posicion - 1) / 10), 4);
                java.time.DayOfWeek diaQuintil = java.time.DayOfWeek.MONDAY.plus(offsetDia);

                if (hoy.getDayOfWeek() != diaQuintil && hoy.getDayOfWeek() != java.time.DayOfWeek.SATURDAY) {
                    respuesta.put("estatusGlobal", "BLOQUEADO_VIP");
                    respuesta.put("mensaje", "Apertura VIP: Aún no es tu turno para apartar días del próximo mes. Regresa el día asignado en tu boleto.");
                    return respuesta;
                }
            }
        }

        // 2. REVISIÓN DE PERIODOS INHÁBILES (BLACKOUT DATES)
        List<PeriodoInhabil> bloqueos = periodoInhabilRepository.findBySupervisorNominaOrderByFechaInicioDesc(supervisorNomina);
        boolean esInhabil = bloqueos.stream().anyMatch(b -> !fecha.isBefore(b.getFechaInicio()) && !fecha.isAfter(b.getFechaFin()));
        if (esInhabil) {
            respuesta.put("estatusGlobal", "INHABIL");
            respuesta.put("mensaje", "La fecha seleccionada corresponde a un periodo inhábil o de inactividad programada para su área.");
            return respuesta;
        }

        // ✨ NUEVO: REVISIÓN DE DÍAS FESTIVOS PARA LA CARTELERA
        java.util.Optional<DiasFestivos> feriadoOpt = diasFestivosRepository.findByActivoTrue().stream()
                .filter(f -> f.getFecha().equals(fecha))
                .findFirst();
        if (feriadoOpt.isPresent()) {
            respuesta.put("estatusGlobal", "FERIADO");
            respuesta.put("mensaje", "Día festivo oficial: " + feriadoOpt.get().getDescripcion() + " (No consume saldo de vacaciones).");
            return respuesta;
        }

        // ✨ 3. REVISIÓN HÍBRIDA DE CAPACIDAD DEL GRUPO DE PROCESO
        GrupoProceso grupoAfectado = null;
        List<GrupoProceso> gruposDelJefe = obtenerGruposProcesoPorJefe(supervisorNomina);

        for (GrupoProceso g : gruposDelJefe) {
            if ("CC".equalsIgnoreCase(g.getTipoAgrupacion()) && empleado.getCentroCosto() != null) {
                if (g.getCentrosCosto().stream().anyMatch(cc -> cc.getId().equals(empleado.getCentroCosto().getId()))) {
                    grupoAfectado = g; break;
                }
            } else if ("WC".equalsIgnoreCase(g.getTipoAgrupacion()) && empleado.getWorkCenter() != null) {
                if (g.getWorkCenters().stream().anyMatch(wc -> wc.getId().equals(empleado.getWorkCenter().getId()))) {
                    grupoAfectado = g; break;
                }
            }
        }

        // ✨ BISTURÍ CARTELERA 1: ¿Yo ya aparté este día?
        boolean yaTengoEsteDia = solicitudVacacionesRepository.findAll().stream()
                .filter(s -> !List.of("RECHAZADO", "CANCELADO").contains(s.getEstatus().toUpperCase()))
                .filter(s -> s.getTipoSolicitud() != null && (s.getTipoSolicitud().toUpperCase().contains("VACACION") || s.getTipoSolicitud().trim().equalsIgnoreCase("V")))
                .filter(s -> s.getEmpleado().getNomina().equals(nominaEmpleado))
                .anyMatch(s -> !fecha.isBefore(s.getFechaInicio()) && !fecha.isAfter(s.getFechaFin()));

        if (yaTengoEsteDia) {
            respuesta.put("estatusGlobal", "MI_VACACION");
            respuesta.put("mensaje", "Ya tienes una solicitud de vacaciones (Aprobada o Pendiente) que cubre este día.");
            return respuesta;
        }

        String nombreGrupo = grupoAfectado != null ? grupoAfectado.getNombre() : "Sin Grupo Asignado";
        int cupoMaximo = grupoAfectado != null ? grupoAfectado.getCupoMaximo() : 999;
        long ocupadosEnElDia = 0;

        if (grupoAfectado != null) {
            if ("WC".equalsIgnoreCase(grupoAfectado.getTipoAgrupacion())) {
                List<Integer> wcIdsDelGrupo = grupoAfectado.getWorkCenters().stream().map(WorkCenter::getId).collect(Collectors.toList());
                ocupadosEnElDia = solicitudVacacionesRepository.findAll().stream()
                        .filter(s -> !List.of("RECHAZADO", "CANCELADO").contains(s.getEstatus().toUpperCase()))
                        .filter(s -> s.getTipoSolicitud() != null && (s.getTipoSolicitud().toUpperCase().contains("VACACION") || s.getTipoSolicitud().trim().equalsIgnoreCase("V")))
                        .filter(s -> s.getEmpleado().getWorkCenter() != null && wcIdsDelGrupo.contains(s.getEmpleado().getWorkCenter().getId()))
                        .filter(s -> !fecha.isBefore(s.getFechaInicio()) && !fecha.isAfter(s.getFechaFin()))
                        .count();
            } else {
                List<Integer> ccIdsDelGrupo = grupoAfectado.getCentrosCosto().stream().map(CentroCosto::getId).collect(Collectors.toList());
                ocupadosEnElDia = solicitudVacacionesRepository.countVacacionesPorGrupoYFecha(ccIdsDelGrupo, fecha);
            }
        }

        respuesta.put("grupoNombre", nombreGrupo);
        respuesta.put("cupoMaximo", cupoMaximo);
        respuesta.put("ocupadosGrupo", ocupadosEnElDia);

        if (ocupadosEnElDia >= cupoMaximo) {
            respuesta.put("estatusGlobal", "GRUPO_LLENO");
            respuesta.put("mensaje", "La capacidad máxima de ausencias simultáneas para su bloque operativo ha sido alcanzada.");
            return respuesta;
        }

        // ✨ 4. REVISIÓN ESTRICTA DEL TURNO SELECCIONADO (HÍBRIDO)
        Turno turnoBuscado = turnoRepository.findById(turnoId).orElseThrow(() -> new RuntimeException("El turno seleccionado no es válido."));
        boolean ocupado = false;

        if (grupoAfectado != null && "WC".equalsIgnoreCase(grupoAfectado.getTipoAgrupacion())) {
            List<Integer> wcIdsDelGrupo = grupoAfectado.getWorkCenters().stream().map(WorkCenter::getId).collect(Collectors.toList());
            long ocupadosEnElTurno = solicitudVacacionesRepository.findAll().stream()
                    .filter(s -> !List.of("RECHAZADO", "CANCELADO").contains(s.getEstatus().toUpperCase()))
                    .filter(s -> s.getTipoSolicitud() != null && (s.getTipoSolicitud().toUpperCase().contains("VACACION") || s.getTipoSolicitud().trim().equalsIgnoreCase("V")))
                    .filter(s -> s.getEmpleado().getWorkCenter() != null && wcIdsDelGrupo.contains(s.getEmpleado().getWorkCenter().getId()))
                    .filter(s -> s.getTurno() != null && s.getTurno().getId().equals(turnoId))
                    .filter(s -> !fecha.isBefore(s.getFechaInicio()) && !fecha.isAfter(s.getFechaFin()))
                    .count();

            int limiteTurno = (grupoAfectado.getCupoMaximoTurno() != null) ? grupoAfectado.getCupoMaximoTurno() : 1;
            ocupado = ocupadosEnElTurno >= limiteTurno;
        } else {
            int cupoWc = (empleado.getWorkCenter().getCupoConcurrenteTurno() != null && empleado.getWorkCenter().getCupoConcurrenteTurno() > 0)
                    ? empleado.getWorkCenter().getCupoConcurrenteTurno() : 1;
            long ocupadosTurno = solicitudVacacionesRepository.contarOcupadosPorLineaYTurno(empleado.getWorkCenter().getId(), turnoId, fecha);
            ocupado = ocupadosTurno >= cupoWc;
        }

        Map<String, Object> infoTurno = new HashMap<>();
        infoTurno.put("turnoNombre", turnoBuscado.getNombreTurno());
        infoTurno.put("ocupado", ocupado);
        respuesta.put("turnoData", infoTurno);

        if (ocupado) {
            respuesta.put("estatusGlobal", "TURNO_LLENO");
            respuesta.put("mensaje", "El cupo en su línea de trabajo o grupo para el turno solicitado ya ha sido cubierto.");
            return respuesta;
        }

        respuesta.put("estatusGlobal", "DISPONIBLE");
        respuesta.put("mensaje", "Cumple con las condiciones operativas y el lugar de su turno se encuentra libre.");
        return respuesta;
    }

    @Transactional
    public void cancelarSolicitudPorEmpleado(Integer idSolicitud, Integer nominaEmpleado) {
        SolicitudVacaciones sol = solicitudVacacionesRepository.findById(idSolicitud)
                .orElseThrow(() -> new RuntimeException("Solicitud no encontrada."));

        if (!sol.getEmpleado().getNomina().equals(nominaEmpleado)) {
            throw new SecurityException("Bloqueo de Seguridad: No tienes permiso para cancelar esta solicitud.");
        }

        if (!sol.getEstatus().toUpperCase().startsWith("PENDIENTE")) {
            throw new RuntimeException("Solo puedes cancelar solicitudes que aún están en estatus pendiente.");
        }

        sol.setEstatus("CANCELADO");
        sol.setNotasSistema((sol.getNotasSistema() != null ? sol.getNotasSistema() + "\n" : "") + "[SISTEMA] Cancelada por el propio colaborador.");
        solicitudVacacionesRepository.save(sol);
    }

    public List<Map<String, Object>> escanearRadarPorGrupo(Integer grupoId, LocalDate inicio, LocalDate fin) {
        GrupoProceso grupo = grupoProcesoRepository.findById(grupoId)
                .orElseThrow(() -> new RuntimeException("Grupo de proceso no localizado."));

        List<Integer> idsAgrupados = new ArrayList<>();
        boolean esWC = "WC".equalsIgnoreCase(grupo.getTipoAgrupacion());

        if (esWC) {
            idsAgrupados = grupo.getWorkCenters().stream().map(WorkCenter::getId).collect(Collectors.toList());
        } else {
            idsAgrupados = grupo.getCentrosCosto().stream().map(CentroCosto::getId).collect(Collectors.toList());
        }

        int cupoMaximo = grupo.getCupoMaximo();
        List<Map<String, Object>> radar = new ArrayList<>();
        LocalDate cursor = inicio;

        Integer supervisorNomina = grupo.getSupervisorNomina();
        List<Turno> turnosActivos = turnoRepository.findByActivoTrue();
        Map<String, Boolean> mesesAperturadosCache = new HashMap<>();
        Map<LocalDate, String> feriadosActivos = diasFestivosRepository.findByActivoTrue().stream()
                .collect(Collectors.toMap(DiasFestivos::getFecha, DiasFestivos::getDescripcion));

        while (!cursor.isAfter(fin)) {
            boolean esFeriado = feriadosActivos.containsKey(cursor);
            String descFeriado = esFeriado ? feriadosActivos.get(cursor) : "";
            int mesActual = cursor.getMonthValue();
            int anioActual = cursor.getYear();
            String keyCache = mesActual + "-" + anioActual;

            boolean mesAperturado = mesesAperturadosCache.computeIfAbsent(keyCache, k -> {
                if (supervisorNomina != null && !turnosActivos.isEmpty()) {
                    return capacidadRepo.findBySupervisorNominaAndMesAndAnioAndTurno_Id(
                            supervisorNomina, mesActual, anioActual, turnosActivos.get(0).getId()
                    ).isPresent();
                }
                return false;
            });

            long ocupados = 0;
            if (!esFeriado && mesAperturado && !idsAgrupados.isEmpty()) {
                if (esWC) {
                    final LocalDate diaRadar = cursor;
                    List<Integer> finalIds = idsAgrupados;
                    ocupados = solicitudVacacionesRepository.findAll().stream()
                            .filter(s -> !List.of("RECHAZADO", "CANCELADO").contains(s.getEstatus().toUpperCase()))
                            .filter(s -> s.getTipoSolicitud() != null && (s.getTipoSolicitud().toUpperCase().contains("VACACION") || s.getTipoSolicitud().trim().equalsIgnoreCase("V")))
                            .filter(s -> s.getEmpleado().getWorkCenter() != null && finalIds.contains(s.getEmpleado().getWorkCenter().getId()))
                            .filter(s -> !diaRadar.isBefore(s.getFechaInicio()) && !diaRadar.isAfter(s.getFechaFin()))
                            .count();
                } else {
                    ocupados = solicitudVacacionesRepository.countVacacionesPorGrupoYFecha(idsAgrupados, cursor);
                }
            }

            Map<String, Object> infoDia = new HashMap<>();
            infoDia.put("fecha", cursor.toString());
            infoDia.put("ocupados", ocupados);
            infoDia.put("cupo", (!esFeriado && mesAperturado) ? cupoMaximo : 0);
            infoDia.put("lleno", esFeriado || !mesAperturado || ocupados >= cupoMaximo);
            infoDia.put("esFeriado", esFeriado);
            infoDia.put("descFeriado", descFeriado);

            radar.add(infoDia);
            cursor = cursor.plusDays(1);
        }

        return radar;
    }

    public List<Map<String, Object>> obtenerDetalleRadarPorTurnos(Integer grupoId, LocalDate fecha) {
        GrupoProceso grupo = grupoProcesoRepository.findById(grupoId)
                .orElseThrow(() -> new RuntimeException("Grupo de proceso no localizado."));

        List<Integer> idsAgrupados = new ArrayList<>();
        boolean esWC = "WC".equalsIgnoreCase(grupo.getTipoAgrupacion());

        if (esWC) {
            idsAgrupados = grupo.getWorkCenters().stream().map(WorkCenter::getId).collect(Collectors.toList());
        } else {
            idsAgrupados = grupo.getCentrosCosto().stream().map(CentroCosto::getId).collect(Collectors.toList());
        }

        List<Turno> turnosActivos = turnoRepository.findByActivoTrue();
        List<Map<String, Object>> detalleTurnos = new ArrayList<>();

        Integer supervisorNomina = grupo.getSupervisorNomina();
        boolean mesAperturado = false;

        if (supervisorNomina != null && !turnosActivos.isEmpty()) {
            mesAperturado = capacidadRepo.findBySupervisorNominaAndMesAndAnioAndTurno_Id(
                    supervisorNomina, fecha.getMonthValue(), fecha.getYear(), turnosActivos.get(0).getId()
            ).isPresent();
        }

        boolean esFeriado = diasFestivosRepository.findByActivoTrue().stream()
                .anyMatch(f -> f.getFecha().equals(fecha));

        for (Turno t : turnosActivos) {
            long ocupados = 0;
            if (!esFeriado && mesAperturado && !idsAgrupados.isEmpty()) {
                if (esWC) {
                    List<Integer> finalIds = idsAgrupados;
                    ocupados = solicitudVacacionesRepository.findAll().stream()
                            .filter(s -> !List.of("RECHAZADO", "CANCELADO").contains(s.getEstatus().toUpperCase()))
                            .filter(s -> s.getTipoSolicitud() != null && (s.getTipoSolicitud().toUpperCase().contains("VACACION") || s.getTipoSolicitud().trim().equalsIgnoreCase("V")))
                            .filter(s -> s.getEmpleado().getWorkCenter() != null && finalIds.contains(s.getEmpleado().getWorkCenter().getId()))
                            .filter(s -> s.getTurno() != null && s.getTurno().getId().equals(t.getId()))
                            .filter(s -> !fecha.isBefore(s.getFechaInicio()) && !fecha.isAfter(s.getFechaFin()))
                            .count();
                } else {
                    ocupados = solicitudVacacionesRepository.countVacacionesPorGrupoYTurnoYFecha(idsAgrupados, t.getId(), fecha);
                }
            }
            Map<String, Object> mapTurno = new HashMap<>();
            mapTurno.put("turnoNombre", t.getNombreTurno());
            mapTurno.put("ocupados", ocupados);
            detalleTurnos.add(mapTurno);
        }
        return detalleTurnos;
    }

    @Transactional
    public void actualizarCupoWorkCenter(Integer wcId, Integer nuevoCupo, Integer nominaSupervisor) {
        WorkCenter wc = workCenterRepository.findById(wcId)
                .orElseThrow(() -> new RuntimeException("Work Center no encontrado."));

        List<WorkCenter> misWcs = obtenerWorkCentersPorJefe(nominaSupervisor);
        boolean tieneAcceso = misWcs.stream().anyMatch(w -> w.getId().equals(wcId)) || esAdminORH(nominaSupervisor);

        if (!tieneAcceso) {
            throw new SecurityException("No tienes permisos para modificar la capacidad de este Work Center.");
        }

        if (nuevoCupo == null || nuevoCupo < 1) {
            throw new IllegalArgumentException("El cupo por turno debe ser al menos de 1 operador.");
        }

        wc.setCupoConcurrenteTurno(nuevoCupo);
        workCenterRepository.save(wc);
    }

    public List<SolicitudVacaciones> obtenerVacacionesParaAuditoriaRH(LocalDate fechaInicioRango) {
        return solicitudVacacionesRepository.findAll().stream()
                .filter(v -> v.getEstatus() != null && "Aprobado".equalsIgnoreCase(v.getEstatus()))
                .filter(v -> v.getTipoSolicitud() != null && (v.getTipoSolicitud().toUpperCase().contains("VACACION") || v.getTipoSolicitud().equalsIgnoreCase("V")))
                .filter(v -> v.getFechaInicio() != null && !v.getFechaInicio().isBefore(fechaInicioRango))
                .sorted(java.util.Comparator.comparing(SolicitudVacaciones::getFechaInicio).reversed())
                .collect(Collectors.toList());
    }

    @Transactional
    public void rechazarVacacionAuditoria(Integer idSolicitud, String comentario) {
        SolicitudVacaciones sol = solicitudVacacionesRepository.findById(idSolicitud)
                .orElseThrow(() -> new RuntimeException("Solicitud no encontrada"));

        if ("Aprobado".equalsIgnoreCase(sol.getEstatus())) {
            // ✨ DEVOLUCIÓN DE SALDO: Si la vacación estaba aprobada y Nóminas la bota, se regresa el dinero a la bolsa
            Empleado emp = sol.getEmpleado();
            BigDecimal saldo = emp.getSaldoVacacionesActual() != null ? emp.getSaldoVacacionesActual() : BigDecimal.ZERO;
            int dias = sol.getDiasTotalesCalculados() != null ? sol.getDiasTotalesCalculados() : 0;
            emp.setSaldoVacacionesActual(saldo.add(BigDecimal.valueOf(dias)));
            empleadoRepository.save(emp);
        }

        sol.setEstatus("Rechazado");
        sol.setNotasSistema((sol.getNotasSistema() != null ? sol.getNotasSistema() + "\n" : "") + "[AUDIT_RH / RECHAZO FORZADO]: " + comentario);
        solicitudVacacionesRepository.save(sol);
    }

    public List<Map<String, Object>> obtenerEventosCalendarioPlanta(String esquema) {
        List<Map<String, Object>> eventos = new ArrayList<>();

        List<DiasFestivos> feriados = diasFestivosRepository.findByActivoTrue();
        for (DiasFestivos f : feriados) {
            Map<String, Object> evento = new HashMap<>();
            evento.put("id", "FESTIVO_" + f.getId());
            evento.put("title", "🇲🇽 " + f.getDescripcion());
            evento.put("start", f.getFecha().toString());
            evento.put("allDay", true);
            evento.put("backgroundColor", "#212529");
            evento.put("borderColor", "#212529");
            evento.put("textColor", "#ffffff");

            Map<String, Object> props = new HashMap<>();
            props.put("tipo", "FERIADO");
            evento.put("extendedProps", props);
            eventos.add(evento);
        }

        Map<LocalDate, Map<String, List<Map<String, Object>>>> acumuladorSemantico = new HashMap<>();

        List<SolicitudVacaciones> vacaciones = solicitudVacacionesRepository.findAll().stream()
                .filter(s -> "Aprobado".equalsIgnoreCase(s.getEstatus()) || "Aprobada".equalsIgnoreCase(s.getEstatus()))
                .collect(Collectors.toList());

        for (SolicitudVacaciones sol : vacaciones) {
            String tipoEmp = sol.getEmpleado().getTipoEmpleado() != null ? sol.getEmpleado().getTipoEmpleado().toUpperCase() : "SINDICALIZADO";
            if (!"TODOS".equalsIgnoreCase(esquema) && !tipoEmp.contains(esquema.toUpperCase())) continue;

            LocalDate cursor = sol.getFechaInicio();
            LocalDate fin = sol.getFechaFin();
            String contexto = sol.getTipoSolicitud() != null ? sol.getTipoSolicitud() : "Incidencia";

            while (cursor != null && fin != null && !cursor.isAfter(fin)) {
                if (!esDiaDescanso(cursor, sol)) {
                    agregarAlAcumuladorPlanta(acumuladorSemantico, cursor, tipoEmp, sol.getEmpleado(), contexto);
                }
                cursor = cursor.plusDays(1);
            }
        }

        List<SolicitudPermiso> permisos = solicitudPermisoRepository.findAll().stream()
                .filter(p -> "APROBADO".equalsIgnoreCase(p.getEstatus()))
                .filter(p -> p.getEsPorHoras() == null || !p.getEsPorHoras())
                .collect(Collectors.toList());

        for (SolicitudPermiso perm : permisos) {
            String tipoEmp = perm.getEmpleado().getTipoEmpleado() != null ? perm.getEmpleado().getTipoEmpleado().toUpperCase() : "SINDICALIZADO";
            if (!"TODOS".equalsIgnoreCase(esquema) && !tipoEmp.contains(esquema.toUpperCase())) continue;

            LocalDate dia = perm.getFechaIncidencia();
            String contexto = perm.getTipoPermiso() != null ? perm.getTipoPermiso().getCodigo() : "Permiso";

            if (dia != null) {
                agregarAlAcumuladorPlanta(acumuladorSemantico, dia, tipoEmp, perm.getEmpleado(), contexto);
            }
        }

        acumuladorSemantico.forEach((fecha, mapaTipos) -> {
            mapaTipos.forEach((tipo, listaInvolucrados) -> {
                Map<String, Object> evento = new HashMap<>();
                int totalAusentes = listaInvolucrados.size();

                evento.put("id", "PL_" + fecha + "_" + tipo);
                evento.put("start", fecha.toString());
                evento.put("allDay", true);

                if ("SINDICALIZADO".equals(tipo) || tipo.contains("SIND")) {
                    evento.put("title", "⚙️ " + totalAusentes + " Planta (Sind)");
                    evento.put("backgroundColor", "#198754");
                    evento.put("borderColor", "#198754");
                } else {
                    evento.put("title", "🏢 " + totalAusentes + " Admin");
                    evento.put("backgroundColor", "#0d6efd");
                    evento.put("borderColor", "#0d6efd");
                }
                evento.put("textColor", "#ffffff");

                Map<String, Object> props = new HashMap<>();
                props.put("tipo", tipo);
                props.put("empleados", listaInvolucrados);
                evento.put("extendedProps", props);

                eventos.add(evento);
            });
        });

        return eventos;
    }

    private void agregarAlAcumuladorPlanta(Map<LocalDate, Map<String, List<Map<String, Object>>>> acumulador,
                                           LocalDate fecha, String tipoEmp, Empleado emp, String contexto) {
        acumulador.putIfAbsent(fecha, new HashMap<>());
        acumulador.get(fecha).putIfAbsent(tipoEmp, new ArrayList<>());

        Map<String, Object> datosEmpleado = new HashMap<>();
        datosEmpleado.put("nomina", emp.getNomina());
        datosEmpleado.put("nombre", emp.getNombreCompleto() + " [" + contexto + "]");
        datosEmpleado.put("wc", emp.getWorkCenter() != null ? emp.getWorkCenter().getId().toString() : "N/A");

        acumulador.get(fecha).get(tipoEmp).add(datosEmpleado);
    }
}
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
            GrupoProcesoRepository grupoProcesoRepository) { // <-- Aquí sí va el paréntesis de cierre

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
        return solicitudVacacionesRepository.findByEstatusIn(List.of("Pendiente_Nomina"));
    }

    public List<SolicitudVacaciones> obtenerHistorialRecuperacionesGlobal() {
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

            // ✨ 1. CERO MOCKS - LECTURA DIRECTA DE LA BOLSA GLOBAL DEL SUPERVISOR
            CapacidadVacacionesMes configLinea = capacidadRepo
                    .findBySupervisorNominaAndMesAndAnioAndTurno_Id(supervisorNomina, mesSol, anioSol, turnoAsignado.getId())
                    .orElseThrow(() -> new RuntimeException("El periodo vacacional para tu turno en este mes aún no ha sido configurado ni abierto por tu Supervisor."));

            LocalDate hoy = LocalDate.now();
            boolean esSemanaVip = calendarioService.esSemanaAperturaVip(hoy);

            // 1. Obtenemos el saldo y el ranking en vivo
            BigDecimal saldoDevengadoReal = empleado.getSaldoVacacionesActual() != null ? empleado.getSaldoVacacionesActual() : BigDecimal.ZERO;
            int posicionRanking = empleadoRepository.obtenerPosicionRankingSupervisorVivo(supervisorNomina, saldoDevengadoReal, empleado.getNomina());

            // 2. Matemáticas de Quintil: Agrupamos a la gente según su ranking para repartirlos en los 5 días (1-10=Lunes, 11-20=Martes, etc.)
            // El offset máximo es 4 (Viernes)
            int offsetDia = Math.min(((posicionRanking - 1) / 10), 4);
            java.time.DayOfWeek diaQuintil = java.time.DayOfWeek.MONDAY.plus(offsetDia);

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

            // --- INICIO DE LA NUEVA ADUANA: DOBLE CANDADO SIMPLIFICADO ---
            LocalDate diaCursor = request.fechaInicio();
            while (!diaCursor.isAfter(request.fechaFin())) {

                // 🔒 CANDADO 1: La Regla de Oro (No empalmar el mismo WC y Turno)
                // CERO MOCKS: Vamos a la base de datos a contar cuántos hay exactos en ese WC, turno y día.
                long ocupadosMismoWcTurno = solicitudVacacionesRepository.contarOcupadosPorLineaYTurno(
                        empleado.getWorkCenter().getId(), turnoAsignado.getId(), diaCursor
                );

                if (ocupadosMismoWcTurno >= 1) {
                    throw new RuntimeException("🚨 Choque de Turno: Ya hay una persona autorizada de tu misma línea (WC "
                            + empleado.getWorkCenter().getId() + ") en el turno '"
                            + turnoAsignado.getNombreTurno() + "' para el día " + diaCursor
                            + ". El sistema solo permite 1 operador por turno.");
                }

                // 🔒 CANDADO 2: La Bolsa del Jefe (Cupo del Grupo de Procesos)
                // Si pasó la regla del turno, verificamos que su grupo tenga lugares libres en la bolsa.
                if (empleado.getCentroCosto() != null) {
                    // 💡 Variable efectivamente final para el Lambda
                    final LocalDate fechaEvaluada = diaCursor;

                    grupoProcesoRepository.findByCentrosCosto_Id(empleado.getCentroCosto().getId())
                            .ifPresent(grupo -> {
                                // Extraemos todos los IDs de los Centros de Costo de esta bolsa
                                List<Integer> ccIdsDelGrupo = grupo.getCentrosCosto().stream()
                                        .map(CentroCosto::getId)
                                        .collect(Collectors.toList());

                                // Contamos cuántos se van de vacaciones de todo este bloque
                                long ocupadosEnElMacroGrupo = solicitudVacacionesRepository.countVacacionesPorGrupoYFecha(ccIdsDelGrupo, fechaEvaluada);

                                if (ocupadosEnElMacroGrupo >= grupo.getCupoMaximo()) {
                                    throw new RuntimeException("🛑 Bolsa Agotada: El límite máximo de " + grupo.getCupoMaximo()
                                            + " lugares para tu grupo ('" + grupo.getNombre()
                                            + "') ha sido alcanzado para el día " + fechaEvaluada + ". Intenta con otra fecha.");
                                }
                            });
                }

                diaCursor = diaCursor.plusDays(1);
            }
            // --- FIN DE LA NUEVA ADUANA ---
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
                            s.getGrupoFolio()
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
    public void guardarConfiguracionArea(Integer nominaJefe, List<Integer> workCenterIds, Integer mes, Integer anio,
                                         Integer maxPersonalGlobal, String fechaLimiteStr,
                                         Integer diasMinimosRezago, List<Integer> turnoIds, List<Integer> cuposPorTurno) {

        LocalDate hoy = LocalDate.now();
        LocalDate inicioMesConfig = LocalDate.of(anio, mes, 1);
        LocalDate finMesConfig = inicioMesConfig.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());

        if (hoy.isAfter(finMesConfig)) {
            throw new IllegalArgumentException("El periodo seleccionado corresponde a un mes ya cerrado en el sistema contable.");
        }

        if (turnoIds == null || cuposPorTurno == null || turnoIds.size() != cuposPorTurno.size()) {
            throw new IllegalArgumentException("Error en la matriz de turnos: Los datos de distribución llegaron corruptos.");
        }

        LocalDate fechaLimiteRegistro = LocalDate.parse(fechaLimiteStr);
        LocalDate fechaAperturaRezago = inicioMesConfig.minusMonths(1).withDayOfMonth(23);
        LocalDate fechaAperturaGeneral = inicioMesConfig;

        // 🌟 Iteramos por Turno para grabar la Bitácora LIMPIA y la Bolsa Global
        for (int i = 0; i < turnoIds.size(); i++) {
            Integer turnoId = turnoIds.get(i);
            Integer cupoEspecificoTurno = cuposPorTurno.get(i);
            Turno turno = turnoRepository.findById(turnoId)
                    .orElseThrow(() -> new IllegalArgumentException("El turno especificado no existe."));

            // ✨ 1. EL ANCLA GLOBAL: Creamos 1 SOLO registro por Turno para representar la "Bolsa del Supervisor"
            CapacidadVacacionesMes config = capacidadRepo
                    .findBySupervisorNominaAndMesAndAnioAndTurno_Id(nominaJefe, mes, anio, turnoId)
                    .orElse(new CapacidadVacacionesMes());

            config.setSupervisorNomina(nominaJefe);
            config.setTurno(turno);
            config.setMes(mes);
            config.setAnio(anio);
            config.setMaxEmpleadosPorDia(cupoEspecificoTurno);
            config.setFechaLimiteRegistro(fechaLimiteRegistro);
            config.setFechaAperturaRezago(fechaAperturaRezago);
            config.setFechaAperturaGeneral(fechaAperturaGeneral);
            config.setDiasMinimosRezago(diasMinimosRezago);
            config.setUltimaModificacionPor(nominaJefe);
            config.setFechaUltimaModificacion(LocalDateTime.now());

            capacidadRepo.save(config);

            // 2. EL TRUCO PARA LA BITÁCORA: 1 Solo registro por Turno
            String accionTurno = "CONF_" + turno.getNombreTurno().substring(0, Math.min(turno.getNombreTurno().length(), 10));
            HistoricoConfiguracionArea log = HistoricoConfiguracionArea.builder()
                    .workCenterId(0) // 0 significa "Aplica para toda mi área"
                    .mes(mes)
                    .anio(anio)
                    .maxEmpleadosPorDia(cupoEspecificoTurno)
                    .fechaLimiteRegistro(fechaLimiteRegistro)
                    .fechaAperturaRezago(fechaAperturaRezago)
                    .fechaAperturaGeneral(fechaAperturaGeneral)
                    .diasMinimosRezago(diasMinimosRezago)
                    .accion(accionTurno)
                    .realizadoPorNomina(nominaJefe)
                    .fechaRegistro(LocalDateTime.now())
                    .build();

            historicoRepo.save(log);
        }
    }

    public List<HistoricoConfiguracionArea> obtenerHistoricoConfiguracion(List<Integer> workCenterIds) {
        // ✨ 1. Clonamos la lista de líneas del jefe para no alterar la original en memoria
        List<Integer> idsABuscar = new java.util.ArrayList<>(workCenterIds);

        // ✨ 2. EL TRUCO: Le inyectamos el "0" para que también pesque la nueva Bolsa Global
        if (!idsABuscar.contains(0)) {
            idsABuscar.add(0);
        }

        // ✨ 3. Ejecutamos la búsqueda. ¡Ahora traerá lo viejo (por línea) y lo nuevo (global)!
        return historicoRepo.findByWorkCenterIdInOrderByFechaRegistroDesc(idsABuscar);
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
                        .filter(s -> s.getEmpleado().getNomina().equals(finalNomina) && !"Rechazado".equalsIgnoreCase(s.getEstatus()))
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

        if (inicio == null || fin == null) return;

        LocalDate diaCursor = inicio;
        while (!diaCursor.isAfter(fin)) {
            final LocalDate diaEvaluado = diaCursor;

            // 🧹 BARRIDO 1: Choque de Turno y WC (Regla 1x1)
            long ocupadosMismoWcTurno = solicitudVacacionesRepository.contarOcupadosPorLineaYTurno(wcId, turnoId, diaEvaluado);

            if (ocupadosMismoWcTurno >= 1) {
                // Rechazamos a todos los que estaban formados esperando irse en ese mismo WC, Turno y Día
                List<SolicitudVacaciones> pendientesWcTurno = solicitudVacacionesRepository.findAll().stream()
                        .filter(s -> s.getEstatus() != null && s.getEstatus().toUpperCase().startsWith("PENDIENTE"))
                        .filter(s -> s.getEmpleado() != null && s.getEmpleado().getWorkCenter() != null && s.getEmpleado().getWorkCenter().getId().equals(wcId))
                        .filter(s -> s.getTurno() != null && s.getTurno().getId().equals(turnoId))
                        .filter(s -> !s.getId().equals(solicitudAprobada.getId()))
                        .filter(s -> !diaEvaluado.isBefore(s.getFechaInicio()) && !diaEvaluado.isAfter(s.getFechaFin()))
                        .collect(Collectors.toList());

                for (SolicitudVacaciones solRechazada : pendientesWcTurno) {
                    String timestamp = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(LocalDateTime.now());
                    solRechazada.setEstatus("Rechazado");
                    solRechazada.setComentarioSupervisor("[SISTEMA]: Solicitud RECHAZADA AUTOMÁTICAMENTE. Razón: El lugar para tu turno en la línea (WC " + wcId + ") ya fue ocupado. Autorización previa asentada el " + timestamp + ".");
                    solRechazada.setNotasSistema((solRechazada.getNotasSistema() != null ? solRechazada.getNotasSistema() + "\n" : "") + "[SISTEMA - EFECTO DOMINÓ] Choque de turno el " + diaEvaluado);
                    solicitudVacacionesRepository.save(solRechazada);
                }
            }

            // 🧹 BARRIDO 2: Saturación de la Bolsa (Grupo de Proceso)
            if (solicitudAprobada.getEmpleado().getCentroCosto() != null) {
                grupoProcesoRepository.findByCentrosCosto_Id(solicitudAprobada.getEmpleado().getCentroCosto().getId())
                        .ifPresent(grupo -> {
                            List<Integer> ccIdsDelGrupo = grupo.getCentrosCosto().stream()
                                    .map(CentroCosto::getId)
                                    .collect(Collectors.toList());

                            long ocupadosEnElMacroGrupo = solicitudVacacionesRepository.countVacacionesPorGrupoYFecha(ccIdsDelGrupo, diaEvaluado);

                            if (ocupadosEnElMacroGrupo >= grupo.getCupoMaximo()) {
                                // Si se llenó la bolsa, rechazamos a todos los pendientes de CUALQUIER CC de este grupo para ese día
                                List<SolicitudVacaciones> pendientesGrupo = solicitudVacacionesRepository.findAll().stream()
                                        .filter(s -> s.getEstatus() != null && s.getEstatus().toUpperCase().startsWith("PENDIENTE"))
                                        .filter(s -> s.getEmpleado() != null && s.getEmpleado().getCentroCosto() != null && ccIdsDelGrupo.contains(s.getEmpleado().getCentroCosto().getId()))
                                        .filter(s -> !s.getId().equals(solicitudAprobada.getId()))
                                        .filter(s -> !diaEvaluado.isBefore(s.getFechaInicio()) && !diaEvaluado.isAfter(s.getFechaFin()))
                                        .collect(Collectors.toList());

                                for (SolicitudVacaciones solRechazada : pendientesGrupo) {
                                    String timestamp = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(LocalDateTime.now());
                                    solRechazada.setEstatus("Rechazado");
                                    solRechazada.setComentarioSupervisor("[SISTEMA]: Solicitud RECHAZADA AUTOMÁTICAMENTE. Razón: La capacidad máxima del grupo ('" + grupo.getNombre() + "') se ha agotado para este día. Autorización previa asentada el " + timestamp + ".");
                                    solRechazada.setNotasSistema((solRechazada.getNotasSistema() != null ? solRechazada.getNotasSistema() + "\n" : "") + "[SISTEMA - EFECTO DOMINÓ] Saturación de Grupo el " + diaEvaluado);
                                    solicitudVacacionesRepository.save(solRechazada);
                                }
                            }
                        });
            }

            diaCursor = diaCursor.plusDays(1);
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
                    s.getGrupoFolio()
            ));
        });

        List<com.hrms.vacaciones.model.SolicitudPermiso> solicitudesPerm = solicitudPermisoRepository.findAll().stream()
                .filter(p -> p.getEmpleado() != null && p.getEmpleado().getNomina().equals(nomina))
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
                    null // ✨ CORRECCIÓN: Los permisos no se fraccionan, pasamos null
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
                .filter(s -> s.tipoSolicitud() != null &&
                        (s.tipoSolicitud().toUpperCase().contains("VACACION") || s.tipoSolicitud().trim().equalsIgnoreCase("V")))
                .collect(Collectors.toList());

        List<com.hrms.vacaciones.dto.SolicitudDTO> listaPermisos = listaFiltrada.stream()
                .filter(s -> s.tipoSolicitud() == null ||
                        (!s.tipoSolicitud().toUpperCase().contains("VACACION") && !s.tipoSolicitud().trim().equalsIgnoreCase("V")))
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

        if (hoy.getDayOfMonth() >= 23) {
            LocalDate proximoMes = hoy.plusMonths(1);
            mesConfig = proximoMes.getMonthValue();
            anioConfig = proximoMes.getYear();
        }

        Integer turnoId = empleado.getTurno() != null ? empleado.getTurno().getId() : 1;

        // ✨ AHORA BUSCAMOS LA BOLSA GLOBAL DEL SUPERVISOR
        java.util.Optional<CapacidadVacacionesMes> configOpt = java.util.Optional.empty();
        if (supervisorNomina != null) {
            configOpt = capacidadRepo.findBySupervisorNominaAndMesAndAnioAndTurno_Id(
                    supervisorNomina, mesConfig, anioConfig, turnoId);
        }

        if (configOpt.isPresent()) {
            CapacidadVacacionesMes config = configOpt.get();
            int topLimite = config.getDiasMinimosRezago() != null ? config.getDiasMinimosRezago() : 10;
            boolean perteneceAlTop = posicionRanking > 0 && posicionRanking <= topLimite;
            boolean enVentanaVIP = !hoy.isBefore(config.getFechaAperturaRezago()) && hoy.isBefore(config.getFechaAperturaGeneral());

            datos.put("topLimite", topLimite);
            datos.put("perteneceAlTop", perteneceAlTop);
            datos.put("fechaAperturaRezago", config.getFechaAperturaRezago());
            datos.put("fechaAperturaGeneral", config.getFechaAperturaGeneral());
            datos.put("mostrarModalVIPAnuncio", enVentanaVIP && perteneceAlTop);
        } else {
            datos.put("topLimite", 10);
            datos.put("perteneceAlTop", posicionRanking <= 10);
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
}
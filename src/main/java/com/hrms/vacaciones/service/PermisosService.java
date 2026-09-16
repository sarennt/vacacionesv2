package com.hrms.vacaciones.service;

import com.hrms.vacaciones.dto.FilaPagoTxtDTO;
import com.hrms.vacaciones.dto.SolicitudPermisoRequestDTO;
import com.hrms.vacaciones.dto.SolicitudPermisoRangoRequestDTO;
import com.hrms.vacaciones.model.*;
import com.hrms.vacaciones.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PermisosService {

    @Autowired
    private SolicitudPermisoRepository permisoRepo;

    @Autowired
    private EmpleadoRepository empleadoRepo;

    @Autowired
    private TipoPermisoRepository tipoPermisoRepo;

    @Autowired
    private TurnoRepository turnoRepo;

    @Autowired
    private DiasFestivosRepository diasFestivosRepo;

    @Autowired
    private SolicitudVacacionesRepository vacacionesRepo;

    @Autowired
    private ConfiguracionCorteNominaRepository configuracionCorteNominaRepository;

    /**
     * Traduce el DayOfWeek nativo de Java a un String en español estandarizado, en mayúsculas y SIN acentos.
     */
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

    /**
     * Sanitizador de Piso: Convierte a mayúsculas, elimina acentos y borra cualquier espacio en blanco intermedio o huérfano.
     */
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

    // ✨ NUEVO: Motor de Guillotina con Traductor de Mundos (WC/BCI -> ADMINISTRATIVO)
    public LocalDate obtenerFechaMinimaPermitida(Empleado empleado) {
        String tipoBruto = empleado.getTipoEmpleado() != null ? empleado.getTipoEmpleado().toUpperCase() : "SINDICALIZADO";

        // 🛡️ TRADUCTOR CONTABLE: Agrupamos las nuevas jerarquías en las 2 reglas de corte existentes
        String tipoGuillotina = "SINDICALIZADO";
        if (tipoBruto.contains("ADMIN") || tipoBruto.contains("WC") || tipoBruto.contains("BCI")) {
            tipoGuillotina = "ADMINISTRATIVO";
        }

        ConfiguracionCorteNomina configCorte = configuracionCorteNominaRepository.findByTipoEmpleado(tipoGuillotina).orElse(null);

        LocalDateTime ahora = LocalDateTime.now();
        LocalDate fechaMinima = ahora.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toLocalDate();

        if (configCorte != null) {
            DayOfWeek diaCorte = DayOfWeek.of(configCorte.getDiaCorte() != null ? configCorte.getDiaCorte() : 2);
            java.time.LocalTime horaCorte = configCorte.getHoraCorte() != null ? configCorte.getHoraCorte() : java.time.LocalTime.of(16, 0);

            LocalDateTime limiteGuillotina = ahora.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .plusDays(diaCorte.getValue() - 1)
                    .toLocalDate()
                    .atTime(horaCorte);

            if (ahora.isBefore(limiteGuillotina)) {
                fechaMinima = ahora.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1).toLocalDate();
            }
        }
        return fechaMinima;
    }

    public LocalDate obtenerFechaMinimaPorNomina(Integer nomina) {
        Empleado empleado = empleadoRepo.findById(nomina)
                .orElseThrow(() -> new RuntimeException("Error: No se encontró empleado"));
        return obtenerFechaMinimaPermitida(empleado);
    }

    /**
     * Trae la lista de permisos activos con Soporte para Combinaciones (Ej. "WC,BCI")
     */
    public List<TipoPermiso> obtenerPermisosParaEmpleado(Integer empleadoNomina) {
        Empleado empleado = empleadoRepo.findById(empleadoNomina)
                .orElseThrow(() -> new RuntimeException("Error: No existe el empleado con nómina: " + empleadoNomina));

        String tipoPuesto = empleado.getTipoEmpleado() != null ? empleado.getTipoEmpleado().toUpperCase() : "SIND";

        // 🎯 Identificamos exactamente qué es el operador (Mapeo de nuevos conceptos)
        String miPerfil = "SIND";
        if (tipoPuesto.contains("BCI")) {
            miPerfil = "BCI";
        } else if (tipoPuesto.contains("WC") || tipoPuesto.contains("EXTRANJERO")) {
            miPerfil = "WC";
        } else if (tipoPuesto.contains("ADMIN")) {
            miPerfil = "ADMIN";
        }

        // 🎯 Traemos todos los activos y los filtramos en memoria para soportar el "LIKE / CONTAINS"
        final String perfilFinal = miPerfil;
        return tipoPermisoRepo.findAll().stream()
                .filter(TipoPermiso::getActivo)
                .filter(p -> {
                    if (p.getAplicaA() == null) return false;
                    String aplicaA = p.getAplicaA().toUpperCase();
                    // Si el permiso dice "TODOS" o contiene mi perfil (Ej. "WC,BCI"), me lo regresa
                    return aplicaA.contains("TODOS") || aplicaA.contains(perfilFinal);
                })
                .collect(Collectors.toList());
    }

    /**
     * Procesa, valida bajo auditoría estricta y guarda la solicitud de incidencia individual (Soporta Completo y Horas Parciales).
     */
    @Transactional
    public void registrarSolicitudPermiso(SolicitudPermisoRequestDTO request) {
        Empleado empleado = empleadoRepo.findById(request.getEmpleadoNomina())
                .orElseThrow(() -> new RuntimeException("Error: No se encontró el empleado con nómina: " + request.getEmpleadoNomina()));

        TipoPermiso tipoPermiso = tipoPermisoRepo.findByCodigo(request.getCodigoPermiso())
                .orElseThrow(() -> new RuntimeException("Error: El tipo de permiso no existe."));

        if (!tipoPermiso.getActivo()) {
            throw new RuntimeException("Operación Denegada: El permiso de tipo " + tipoPermiso.getDescripcion() + " se encuentra desactivado temporalmente por RH.");
        }

        List<LocalDate> fechasIncidencia = (request.getFechasIncidencia() != null && !request.getFechasIncidencia().isEmpty())
                ? request.getFechasIncidencia()
                : Collections.singletonList(request.getFechaIncidencia());

        LocalDate fechaMinima = obtenerFechaMinimaPermitida(empleado);

        for (LocalDate fecha : fechasIncidencia) {
            if (fecha.isBefore(fechaMinima)) {
                throw new RuntimeException("Bloqueo de Nómina: La fecha de la falta (" + fecha + ") pertenece a una semana nominal cerrada contablemente.");
            }

            boolean chocaConVacaciones = vacacionesRepo.verificarInterferenciaVacaciones(
                    empleado.getNomina(), fecha, Arrays.asList("APROBADO", "PENDIENTE_JEFE", "PENDIENTE_SUPERVISOR"));

            if (chocaConVacaciones) {
                throw new RuntimeException("Error de Agenda: La fecha de incidencia (" + fecha + ") choca con un periodo de vacaciones ya registrado.");
            }
        }

        Turno turnoSeleccionado = null;
        if (request.getNombreTurno() != null && !request.getNombreTurno().isEmpty()) {
            turnoSeleccionado = turnoRepo.findByNombreTurno(request.getNombreTurno()).orElse(null);
        }

        for (int i = 0; i < fechasIncidencia.size(); i++) {
            LocalDate fechaActual = fechasIncidencia.get(i);

            SolicitudPermiso nuevaSolicitud = SolicitudPermiso.builder()
                    .empleado(empleado)
                    .tipoPermiso(tipoPermiso)
                    .turno(turnoSeleccionado)
                    .fechaIncidencia(fechaActual)
                    .fechaSolicitud(LocalDateTime.now())
                    .justificacionSupervisor(request.getJustificacionSupervisor())
                    .horasPermiso(request.getHorasPermiso() != null ? request.getHorasPermiso() : BigDecimal.ZERO)
                    .esPorHoras(request.getEsPorHoras() != null ? request.getEsPorHoras() : false)
                    .desglosesPago(new ArrayList<>())
                    .estatus("PENDIENTE")
                    .build();

            if ("TXT".equalsIgnoreCase(tipoPermiso.getCodigo())) {
                if (i == 0) {
                    procesarValidacionesTXT(nuevaSolicitud, request, empleado, fechasIncidencia.size());
                } else {
                    nuevaSolicitud.setJustificacionSupervisor(request.getJustificacionSupervisor() + " (Día " + (i + 1) + " del bloque TXT)");
                }
            }

            permisoRepo.save(nuevaSolicitud);
        }
    }

    /**
     * Registra y aprueba en automático las incidencias del lote TXT Colectivo masivo de planta.
     */
    @Transactional
    public void registrarSolicitudPermisoDirectoAprobado(SolicitudPermisoRequestDTO request) {
        Empleado empleado = empleadoRepo.findById(request.getEmpleadoNomina())
                .orElseThrow(() -> new RuntimeException("Error: No se encontró el empleado con nómina: " + request.getEmpleadoNomina()));

        TipoPermiso tipoPermiso = tipoPermisoRepo.findByCodigo(request.getCodigoPermiso())
                .orElseThrow(() -> new RuntimeException("Error: El tipo de permiso no existe."));

        if (!tipoPermiso.getActivo()) {
            throw new RuntimeException("Operación Denegada: El permiso de tipo " + tipoPermiso.getDescripcion() + " se encuentra desactivado temporalmente por RH.");
        }

        List<LocalDate> fechasIncidencia = (request.getFechasIncidencia() != null && !request.getFechasIncidencia().isEmpty())
                ? request.getFechasIncidencia()
                : Collections.singletonList(request.getFechaIncidencia());

        LocalDate fechaMinima = obtenerFechaMinimaPermitida(empleado);

        // ✨ NUEVO: Detectamos si la petición viene del modal de Recuperación
        boolean esRecupExtraordinaria = request.getJustificacionSupervisor() != null
                && request.getJustificacionSupervisor().contains("[RECUPERACIÓN EXTRAORDINARIA]");

        for (LocalDate fecha : fechasIncidencia) {
            if (esRecupExtraordinaria) {
                // Si es recuperación, le damos holgura de 1 mes hacia atrás para la FALTA
                LocalDate unMesAtras = LocalDate.now().minusMonths(1);
                if (fecha.isBefore(unMesAtras)) {
                    throw new RuntimeException("Límite de Retroactividad: La fecha del paro (" + fecha + ") excede el mes de antigüedad permitido para justificaciones.");
                }
            } else if (fecha.isBefore(fechaMinima)) {
                // Si es TXT Colectivo normal, aplicamos guillotina estricta
                throw new RuntimeException("Bloqueo de Lote: La fecha del paro (" + fecha + ") pertenece a una semana nominal cerrada contablemente.");
            }

            boolean chocaConVacaciones = vacacionesRepo.verificarInterferenciaVacaciones(
                    empleado.getNomina(), fecha, Arrays.asList("APROBADO", "PENDIENTE_JEFE", "PENDIENTE_SUPERVISOR"));

            if (chocaConVacaciones) {
                throw new RuntimeException("Error de Agenda: La fecha de incidencia (" + fecha + ") choca con un periodo de vacaciones ya registrado para " + empleado.getNombreCompleto() + ".");
            }
        }

        Turno turnoSeleccionado = null;
        if (request.getNombreTurno() != null && !request.getNombreTurno().isEmpty()) {
            turnoSeleccionado = turnoRepo.findByNombreTurno(request.getNombreTurno()).orElse(null);
        }

        for (int i = 0; i < fechasIncidencia.size(); i++) {
            LocalDate fechaActual = fechasIncidencia.get(i);

            SolicitudPermiso nuevaSolicitud = SolicitudPermiso.builder()
                    .empleado(empleado)
                    .tipoPermiso(tipoPermiso)
                    .turno(turnoSeleccionado)
                    .fechaIncidencia(fechaActual)
                    .fechaSolicitud(LocalDateTime.now())
                    .justificacionSupervisor(request.getJustificacionSupervisor())
                    .horasPermiso(request.getHorasPermiso() != null ? request.getHorasPermiso() : BigDecimal.ZERO)
                    .esPorHoras(request.getEsPorHoras() != null ? request.getEsPorHoras() : false)
                    .desglosesPago(new ArrayList<>())
                    .estatus("APROBADO")
                    .build();

            if ("TXT".equalsIgnoreCase(tipoPermiso.getCodigo())) {
                if (i == 0) {
                    procesarValidacionesTXT(nuevaSolicitud, request, empleado, fechasIncidencia.size());
                } else {
                    nuevaSolicitud.setJustificacionSupervisor(request.getJustificacionSupervisor() + " (Día " + (i + 1) + " del bloque colectivo)");
                }
            }

            permisoRepo.save(nuevaSolicitud);
        }
    }

    /**
     * Validador Contable de Piso: Compara las exhibiciones futuras/pasadas contra la deuda total del bloque de días.
     */
    private void procesarValidacionesTXT(SolicitudPermiso solicitud, SolicitudPermisoRequestDTO request, Empleado empleado, int diasParo) {
        List<FilaPagoTxtDTO> filasPago = request.getDesglosesPago();

        if (filasPago == null || filasPago.isEmpty()) {
            throw new RuntimeException("Error de Captura: Debes especificar las fechas y horas en las que repondrás el tiempo.");
        }

        int limitePagos = diasParo * 2;
        if (filasPago.size() > limitePagos) {
            throw new RuntimeException("Regla de Nómina: No puedes fraccionar el pago de horas en más de " + limitePagos + " exhibiciones.");
        }

        LocalDate fechaMinima = obtenerFechaMinimaPermitida(empleado);
        String nombreTurnoEmpleado = request.getNombreTurno() != null ? request.getNombreTurno() : "1ro";

        Turno turnoOficial = turnoRepo.findByNombreTurno(nombreTurnoEmpleado)
                .orElseThrow(() -> new RuntimeException("Error Técnico: No se encontró la configuración del turno '" + nombreTurnoEmpleado + "' en la Torre de Control."));
        BigDecimal javaHorasActuales = turnoOficial.getHorasJornada();

        BigDecimal horasBasePorDia = Boolean.TRUE.equals(request.getEsPorHoras()) && request.getHorasPermiso() != null
                ? request.getHorasPermiso()
                : javaHorasActuales;

        BigDecimal horasObjetivoTotal = horasBasePorDia.multiply(new BigDecimal(diasParo));
        BigDecimal javaSumaHorasCapturadas = BigDecimal.ZERO;

        for (FilaPagoTxtDTO pago : filasPago) {
            if (pago.getFechaPago().isBefore(fechaMinima)) {
                throw new RuntimeException("Bloqueo de Nómina: No puedes registrar la fecha de pago " + pago.getFechaPago() +
                        " porque pertenece a una semana nominal que ya fue procesada y cerrada contablemente.");
            }

            boolean pagoChocaVacaciones = vacacionesRepo.verificarInterferenciaVacaciones(
                    empleado.getNomina(), pago.getFechaPago(), Arrays.asList("APROBADO", "PENDIENTE_JEFE", "PENDIENTE_SUPERVISOR"));
            if (pagoChocaVacaciones) {
                throw new RuntimeException("Conflicto de Horas: No puedes venir a reponer tiempo el día " + pago.getFechaPago() + " porque " + empleado.getNombreCompleto() + " tiene vacaciones registradas.");
            }

            javaSumaHorasCapturadas = javaSumaHorasCapturadas.add(pago.getHorasPago());

            SolicitudTxtPago renglonPago = SolicitudTxtPago.builder()
                    .solicitudPermiso(solicitud)
                    .fechaPago(pago.getFechaPago())
                    .horasPago(pago.getHorasPago())
                    .build();
            solicitud.getDesglosesPago().add(renglonPago);
        }

        if (javaSumaHorasCapturadas.compareTo(horasObjetivoTotal) != 0) {
            throw new RuntimeException("Descuadre Operativo: La suma de las horas de pago capturadas (" + javaSumaHorasCapturadas +
                    " hrs) no coincide exactamente con la deuda del bloque de TXT (" + horasObjetivoTotal + " hrs para " + diasParo + " día(s)).");
        }
    }

    @Transactional
    public void registrarSolicitudPermisoRango(SolicitudPermisoRangoRequestDTO request) throws Exception {
        Empleado empleado = empleadoRepo.findById(request.getEmpleadoNomina())
                .orElseThrow(() -> new RuntimeException("Error Operativo: No existe el colaborador con nómina #" + request.getEmpleadoNomina()));

        TipoPermiso tipoPermiso = tipoPermisoRepo.findByCodigo(request.getCodigoPermiso())
                .orElseThrow(() -> new RuntimeException("Error Operativo: El concepto de incidencia seleccionado no es válido."));

        if (!tipoPermiso.getActivo()) {
            throw new RuntimeException("Operación Denegada: El permiso " + tipoPermiso.getDescripcion() + " ha sido inhabilitado desde la Torre de Control.");
        }

        Turno turnoConfig = turnoRepo.findById(request.getRolDescansoId())
                .orElseThrow(() -> new IllegalArgumentException("Error de Configuración: El turno seleccionado no existe en el sistema."));

        int diasHabilesSolicitados = calcularDiasHabilesRango(request.getFechaInicio(), request.getFechaFin(), turnoConfig);

        if (diasHabilesSolicitados == 0) {
            throw new RuntimeException("Solicitud Vacía: El rango seleccionado comprende exclusivamente días de descanso o festivos oficiales.");
        }

        LocalDate cursorVacaciones = request.getFechaInicio();
        while (!cursorVacaciones.isAfter(request.getFechaFin())) {
            boolean diaChocaVacaciones = vacacionesRepo.verificarInterferenciaVacaciones(
                    empleado.getNomina(), cursorVacaciones, Arrays.asList("APROBADO", "PENDIENTE_JEFE", "PENDIENTE_SUPERVISOR"));

            if (diaChocaVacaciones) {
                throw new RuntimeException("Conflicto de Agenda: El día " + cursorVacaciones + " interfiere con un periodo de vacaciones activo o en proceso de revisión.");
            }
            cursorVacaciones = cursorVacaciones.plusDays(1);
        }

        String rutaEvidenciaGuardada = null;
        if (request.getComprobante() != null && !request.getComprobante().isEmpty()) {
            if (request.getComprobante().getSize() > 2.5 * 1024 * 1024) {
                throw new RuntimeException("Fallo de Carga: La evidencia física supera el límite permitido de 2.5MB.");
            }

            java.nio.file.Path directorioSubidas = java.nio.file.Paths.get("archivos_evidencia_permisos");
            if (!java.nio.file.Files.exists(directorioSubidas)) {
                java.nio.file.Files.createDirectories(directorioSubidas);
            }

            String nombreOriginal = request.getComprobante().getOriginalFilename();
            String extension = nombreOriginal != null && nombreOriginal.contains(".") ? nombreOriginal.substring(nombreOriginal.lastIndexOf(".")) : ".jpg";
            String nombreUnico = "evidencia_" + empleado.getNomina() + "_" + System.currentTimeMillis() + extension;

            java.nio.file.Path rutaDestinoFinal = directorioSubidas.resolve(nombreUnico);
            java.nio.file.Files.copy(request.getComprobante().getInputStream(), rutaDestinoFinal, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            rutaEvidenciaGuardada = rutaDestinoFinal.toString();
        }

        SolicitudPermiso solicitudRango = SolicitudPermiso.builder()
                .empleado(empleado)
                .tipoPermiso(tipoPermiso)
                .fechaIncidencia(request.getFechaInicio())
                .justificacionSupervisor(request.getComentarios())
                .comprobantePath(rutaEvidenciaGuardada)
                .fechaSolicitud(LocalDateTime.now())
                .estatus("PENDIENTE")
                .horasPermiso(BigDecimal.ZERO)
                .esPorHoras(false)
                .desglosesPago(new ArrayList<>())
                .build();

        permisoRepo.save(solicitudRango);
    }

    private int calcularDiasHabilesRango(LocalDate fechaInicio, LocalDate fechaFin, Turno turno) {
        if (fechaInicio.isAfter(fechaFin)) {
            throw new RuntimeException("Error Cronológico: La fecha de inicio no puede ser posterior a la fecha de fin.");
        }

        List<DiasFestivos> feriados = diasFestivosRepo.findByActivoTrue();
        List<LocalDate> fechasFestivas = feriados.stream().map(DiasFestivos::getFecha).toList();

        int contadorHabiles = 0;
        LocalDate cursor = fechaInicio;

        String descansosSaneados = sanitizarDiasDescanso(turno.getDiasDescanso());

        while (!cursor.isAfter(fechaFin)) {
            String diaSemanaActualStr = obtenerDiaSemanaEspNormalizado(cursor);

            boolean esDiaDescanso = descansosSaneados.contains(diaSemanaActualStr);
            boolean esDiaFestivo = fechasFestivas.contains(cursor);

            if (!esDiaDescanso && !esDiaFestivo) {
                contadorHabiles++;
            }
            cursor = cursor.plusDays(1);
        }
        return contadorHabiles;
    }

    public List<SolicitudPermiso> obtenerPermisosPendientesPorJefe(Integer nominaJefe, List<Integer> wcIds) {
        Empleado jefe = empleadoRepo.findById(nominaJefe).orElse(null);
        String rol = jefe != null && jefe.getRolJerarquico() != null ? jefe.getRolJerarquico().trim().toUpperCase() : "";

        return permisoRepo.findAll().stream()
                .filter(s -> s.getEstatus() != null && s.getEstatus().toUpperCase().startsWith("PENDIENTE"))
                .filter(s -> s.getTipoPermiso() != null
                        && s.getTipoPermiso().getCodigo() != null
                        && !"V".equalsIgnoreCase(s.getTipoPermiso().getCodigo().trim())
                        && !"VACACIONES".equalsIgnoreCase(s.getTipoPermiso().getCodigo().trim()))
                .filter(p -> {
                    String estatus = p.getEstatus() != null ? p.getEstatus().trim().toUpperCase() : "";
                    String tipoEmp = p.getEmpleado() != null && p.getEmpleado().getTipoEmpleado() != null ? p.getEmpleado().getTipoEmpleado().trim().toUpperCase() : "SINDICALIZADO";
                    boolean coincidenciaDirecta = p.getEmpleado() != null && p.getEmpleado().getJefeDirectoNomina() != null && p.getEmpleado().getJefeDirectoNomina().equals(nominaJefe);
                    boolean coincidenciaLinea = p.getEmpleado() != null && p.getEmpleado().getWorkCenter() != null && wcIds.contains(p.getEmpleado().getWorkCenter().getId());

                    // Si no es su empleado directo ni pertenece a sus WCs, lo descartamos de inmediato
                    if (!(coincidenciaDirecta || coincidenciaLinea)) {
                        return false;
                    }

                    if ("SUPERVISOR".equals(rol)) {
                        if ("SINDICALIZADO".equals(tipoEmp)) {
                            // El supervisor solo lo ve en su campanita si ya escaló o si es jefe directo
                            if ("PENDIENTE_SUPERVISOR".equals(estatus)) {
                                return true;
                            }
                            return coincidenciaDirecta && ("PENDIENTE".equals(estatus) || "PENDIENTE_JEFE".equals(estatus));
                        } else {
                            return "PENDIENTE_JEFE".equals(estatus) || "PENDIENTE".equals(estatus);
                        }
                    } else {
                        // Shift Leaders y Administrativos
                        return "PENDIENTE_JEFE".equals(estatus) || "PENDIENTE".equals(estatus);
                    }
                })
                .collect(Collectors.toList());
    }

    public List<SolicitudPermiso> obtenerHistorialTxtColectivoPorJefe(List<Integer> wcIds) {
        return permisoRepo.findAll().stream()
                .filter(s -> "APROBADO".equalsIgnoreCase(s.getEstatus()))
                .filter(s -> s.getTipoPermiso() != null && "TXT".equalsIgnoreCase(s.getTipoPermiso().getCodigo()))
                .filter(s -> s.getEmpleado().getWorkCenter() != null && wcIds.contains(s.getEmpleado().getWorkCenter().getId()))
                .sorted((a, b) -> b.getFechaSolicitud().compareTo(a.getFechaSolicitud()))
                .collect(Collectors.toList());
    }

    public List<Empleado> obtenerPlantillaPorJefe(Integer nominaJefe) {
        return empleadoRepo.findAll().stream()
                .filter(e -> e.getJefeDirectoNomina() != null && e.getJefeDirectoNomina().equals(nominaJefe))
                .collect(Collectors.toList());
    }

    @Transactional
    public void resolverSolicitudPermiso(Long idSolicitud, boolean aprobado, String comentarioRechazo, Integer nominaJefe) {
        SolicitudPermiso solicitud = permisoRepo.findById(idSolicitud.intValue())
                .orElseThrow(() -> new RuntimeException("Error: No se encontró la solicitud de incidencia #" + idSolicitud));

        Empleado jefeQueResuelve = empleadoRepo.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Error: Jefe autorizador no encontrado en el sistema."));

        if (!"PENDIENTE".equalsIgnoreCase(solicitud.getEstatus())) {
            throw new RuntimeException("Operación Bloqueada: Esta solicitud ya fue procesada previamente.");
        }

        if (solicitud.getTipoPermiso() != null
                && solicitud.getTipoPermiso().getCodigo() != null
                && ("V".equalsIgnoreCase(solicitud.getTipoPermiso().getCodigo().trim())
                || "VACACIONES".equalsIgnoreCase(solicitud.getTipoPermiso().getCodigo().trim()))) {
            throw new RuntimeException("Violación Contable: No está permitido procesar vacaciones desde el panel de permisos operativos.");
        }

        if (aprobado) {
            solicitud.setEstatus("APROBADO");
        } else {
            solicitud.setEstatus("RECHAZADO");
            solicitud.setJustificacionSupervisor("[RECHAZADO POR LÍNEA]: " + comentarioRechazo);
        }

        solicitud.setResueltoPor(jefeQueResuelve);

        permisoRepo.save(solicitud);
    }

    @Transactional
    public void registrarTxtColectivoMasivo(Integer nominaJefe, LocalDate fechaIncidencia, List<Integer> empleadoIds, String justificacion) {
        if (empleadoIds == null || empleadoIds.isEmpty()) {
            throw new RuntimeException("Error Operativo: No se seleccionó ningún operador para la inyección del lote.");
        }

        Empleado empleadoGuia = empleadoRepo.findById(empleadoIds.get(0)).orElseThrow(() -> new RuntimeException("Error en empleado guía"));
        LocalDate fechaMinima = obtenerFechaMinimaPermitida(empleadoGuia);

        if (fechaIncidencia.isBefore(fechaMinima)) {
            throw new RuntimeException("Bloqueo de Lote: La fecha del paro o afectación masiva pertenece a un periodo contable cerrado.");
        }

        TipoPermiso tipoTxt = tipoPermisoRepo.findByCodigo("TXT")
                .orElseThrow(() -> new RuntimeException("Error Técnico: El tipo de permiso 'TXT' no está configurado en los catálogos maestros."));

        Empleado jefeQueResuelve = empleadoRepo.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Error: Jefe autorizador del lote no encontrado."));

        for (Integer nominaEmp : empleadoIds) {
            Empleado empleado = empleadoRepo.findById(nominaEmp)
                    .orElseThrow(() -> new RuntimeException("No existe el colaborador con nómina #" + nominaEmp));

            List<SolicitudPermiso> permisosAnteriores = permisoRepo.findAll().stream()
                    .filter(p -> p.getEmpleado().getNomina().equals(nominaEmp))
                    .filter(p -> p.getTipoPermiso() != null && "TXT".equalsIgnoreCase(p.getTipoPermiso().getCodigo()))
                    .filter(p -> p.getFechaIncidencia().equals(fechaIncidencia))
                    .toList();

            if (!permisosAnteriores.isEmpty()) {
                permisoRepo.deleteAll(permisosAnteriores);
            }

            SolicitudPermiso solicitudColectiva = SolicitudPermiso.builder()
                    .empleado(empleado)
                    .tipoPermiso(tipoTxt)
                    .fechaIncidencia(fechaIncidencia)
                    .fechaSolicitud(LocalDateTime.now())
                    .justificacionSupervisor("[LOTE COLECTIVO / CORRECCIÓN] " + justificacion)
                    .horasPermiso(BigDecimal.ZERO)
                    .esPorHoras(false)
                    .estatus("APROBADO")
                    .resueltoPor(jefeQueResuelve)
                    .desglosesPago(new java.util.ArrayList<>())
                    .build();

            permisoRepo.save(solicitudColectiva);
        }
    }

    public List<Map<String, Object>> obtenerEventosCalendarioPermisos(Integer nominaJefe, List<Integer> wcIds) {
        List<Map<String, Object>> eventos = new ArrayList<>();

        List<Empleado> equipoUnificado = empleadoRepo.findAll().stream()
                .filter(e -> (e.getJefeDirectoNomina() != null && e.getJefeDirectoNomina().equals(nominaJefe)) ||
                        (e.getWorkCenter() != null && wcIds.contains(e.getWorkCenter().getId())))
                .toList();

        if (equipoUnificado.isEmpty()) return eventos;

        List<Integer> nominasEquipo = equipoUnificado.stream().map(Empleado::getNomina).toList();
        Map<LocalDate, Map<String, List<Empleado>>> acumuladorDia = new HashMap<>();

        List<SolicitudPermiso> individualesAprobados = permisoRepo.findAll().stream()
                .filter(p -> "APROBADO".equalsIgnoreCase(p.getEstatus()))
                .filter(p -> nominasEquipo.contains(p.getEmpleado().getNomina()))
                .toList();

        for (SolicitudPermiso perm : individualesAprobados) {
            LocalDate dia = perm.getFechaIncidencia();
            String codigo = (perm.getTipoPermiso() != null) ? perm.getTipoPermiso().getCodigo() : "INCIDENCIA";

            acumuladorDia.computeIfAbsent(dia, d -> new HashMap<>())
                    .computeIfAbsent(codigo, c -> new ArrayList<>())
                    .add(perm.getEmpleado());
        }

        List<SolicitudVacaciones> rangosAprobados = vacacionesRepo.findAll().stream()
                .filter(v -> "APROBADO".equalsIgnoreCase(v.getEstatus()))
                .filter(v -> nominasEquipo.contains(v.getEmpleado().getNomina()))
                .filter(v -> v.getTipoSolicitud() != null &&
                        ("PT".equalsIgnoreCase(v.getTipoSolicitud()) ||
                                "Paro Técnico".equalsIgnoreCase(v.getTipoSolicitud()) ||
                                "HO".equalsIgnoreCase(v.getTipoSolicitud()) ||
                                "Home Office".equalsIgnoreCase(v.getTipoSolicitud())))
                .toList();

        for (SolicitudVacaciones rango : rangosAprobados) {
            LocalDate cursor = rango.getFechaInicio();
            LocalDate fin = rango.getFechaFin();
            String codigo = rango.getTipoSolicitud();

            if (cursor != null && fin != null) {
                while (!cursor.isAfter(fin)) {
                    acumuladorDia.computeIfAbsent(cursor, d -> new HashMap<>())
                            .computeIfAbsent(codigo, c -> new ArrayList<>())
                            .add(rango.getEmpleado());
                    cursor = cursor.plusDays(1);
                }
            }
        }

        for (Map.Entry<LocalDate, Map<String, List<Empleado>>> entradaDia : acumuladorDia.entrySet()) {
            LocalDate fecha = entradaDia.getKey();

            for (Map.Entry<String, List<Empleado>> entradaTipo : entradaDia.getValue().entrySet()) {
                String tipoPermiso = entradaTipo.getKey();
                List<Empleado> afectados = entradaTipo.getValue();

                Map<String, Object> evento = new HashMap<>();
                evento.put("start", fecha.toString());
                evento.put("title", afectados.size() + " Fuera por " + tipoPermiso);

                if ("PT".equalsIgnoreCase(tipoPermiso) || "Paro Técnico".equalsIgnoreCase(tipoPermiso)) {
                    evento.put("backgroundColor", "#dc2626");
                    evento.put("borderColor", "#dc2626");
                } else if ("HO".equalsIgnoreCase(tipoPermiso) || "Home Office".equalsIgnoreCase(tipoPermiso)) {
                    evento.put("backgroundColor", "#0dc6fd");
                    evento.put("borderColor", "#0dc6fd");
                } else {
                    evento.put("backgroundColor", "#f59e0b");
                    evento.put("borderColor", "#f59e0b");
                }

                Map<String, Object> extendedProps = new HashMap<>();
                extendedProps.put("tipo", "PERMISO");

                List<Map<String, String>> listaEmpleadosJson = new ArrayList<>();
                for (Empleado e : afectados) {
                    Map<String, String> data = new HashMap<>();
                    data.put("nomina", String.valueOf(e.getNomina()));
                    data.put("nombre", e.getNombreCompleto());
                    data.put("wc", e.getWorkCenter() != null ? String.valueOf(e.getWorkCenter().getId()) : "N/A");
                    listaEmpleadosJson.add(data);
                }
                extendedProps.put("empleados", listaEmpleadosJson);
                evento.put("extendedProps", extendedProps);

                eventos.add(evento);
            }
        }

        return eventos;
    }

    public Map<String, Long> obtenerMetricasAreaPermisos(Integer nominaJefe, List<Integer> wcIds) {
        Map<String, Long> metricas = new HashMap<>();

        List<Empleado> equipoUnificado = empleadoRepo.findAll().stream()
                .filter(e -> (e.getJefeDirectoNomina() != null && e.getJefeDirectoNomina().equals(nominaJefe)) ||
                        (e.getWorkCenter() != null && wcIds.contains(e.getWorkCenter().getId())))
                .toList();

        if (equipoUnificado.isEmpty()) {
            metricas.put("permisosAprobadosMes", 0L);
            metricas.put("ausenciasPermisosManana", 0L);
            return metricas;
        }

        List<Integer> nominasEquipo = equipoUnificado.stream().map(Empleado::getNomina).toList();

        LocalDate hoy = LocalDate.now();
        LocalDate inicioMes = hoy.with(TemporalAdjusters.firstDayOfMonth());
        LocalDate finMes = hoy.with(TemporalAdjusters.lastDayOfMonth());

        long individualesMes =  permisoRepo.findAll().stream()
                .filter(p -> "APROBADO".equalsIgnoreCase(p.getEstatus()))
                .filter(p -> nominasEquipo.contains(p.getEmpleado().getNomina()))
                .filter(p -> p.getFechaIncidencia() != null && !p.getFechaIncidencia().isBefore(inicioMes) && !p.getFechaIncidencia().isAfter(finMes))
                .count();

        long rangosMes = vacacionesRepo.findAll().stream()
                .filter(v -> "APROBADO".equalsIgnoreCase(v.getEstatus()))
                .filter(v -> nominasEquipo.contains(v.getEmpleado().getNomina()))
                .filter(v -> v.getTipoSolicitud() != null &&
                        ("PT".equalsIgnoreCase(v.getTipoSolicitud()) ||
                                "Paro Técnico".equalsIgnoreCase(v.getTipoSolicitud()) ||
                                "HO".equalsIgnoreCase(v.getTipoSolicitud()) ||
                                "Home Office".equalsIgnoreCase(v.getTipoSolicitud())))
                .filter(v -> v.getFechaInicio() != null && v.getFechaFin() != null &&
                        !(v.getFechaFin().isBefore(inicioMes) || v.getFechaInicio().isAfter(finMes)))
                .count();

        metricas.put("permisosAprobadosMes", individualesMes + rangosMes);

        Set<Integer> ausentesVentanaSet = new HashSet<>();
        LocalDate limiteVentana = hoy.plusDays(7);

        permisoRepo.findAll().stream()
                .filter(p -> "APROBADO".equalsIgnoreCase(p.getEstatus()))
                .filter(p -> nominasEquipo.contains(p.getEmpleado().getNomina()))
                .filter(p -> p.getFechaIncidencia() != null && !p.getFechaIncidencia().isBefore(hoy) && !p.getFechaIncidencia().isAfter(limiteVentana))
                .forEach(p -> ausentesVentanaSet.add(p.getEmpleado().getNomina()));

        vacacionesRepo.findAll().stream()
                .filter(v -> "APROBADO".equalsIgnoreCase(v.getEstatus()))
                .filter(v -> nominasEquipo.contains(v.getEmpleado().getNomina()))
                .filter(v -> v.getTipoSolicitud() != null &&
                        ("PT".equalsIgnoreCase(v.getTipoSolicitud()) ||
                                "Paro Técnico".equalsIgnoreCase(v.getTipoSolicitud()) ||
                                "HO".equalsIgnoreCase(v.getTipoSolicitud()) ||
                                "Home Office".equalsIgnoreCase(v.getTipoSolicitud())))
                .filter(v -> v.getFechaInicio() != null && v.getFechaFin() != null &&
                        !(v.getFechaFin().isBefore(hoy) || v.getFechaInicio().isAfter(limiteVentana)))
                .forEach(v -> ausentesVentanaSet.add(v.getEmpleado().getNomina()));

        metricas.put("ausenciasPermisosManana", (long) ausentesVentanaSet.size());

        return metricas;
    }

    @Transactional
    public void cancelarPermisoPorEmpleado(Long idSolicitud, Integer nominaEmpleado) {
        SolicitudPermiso sol = permisoRepo.findById(idSolicitud.intValue())
                .orElseThrow(() -> new RuntimeException("Permiso o Incidencia no encontrada."));

        if (!sol.getEmpleado().getNomina().equals(nominaEmpleado)) {
            throw new SecurityException("Bloqueo de Seguridad: No tienes permiso para cancelar este permiso.");
        }

        if (!"PENDIENTE".equalsIgnoreCase(sol.getEstatus())) {
            throw new RuntimeException("Solo puedes cancelar incidencias que aún están pendientes de revisión.");
        }

        sol.setEstatus("CANCELADO");
        sol.setJustificacionSupervisor(sol.getJustificacionSupervisor() + " | [Cancelado por el Colaborador]");
        permisoRepo.save(sol);
    }

    @Transactional
    public void actualizarSolicitudPermiso(Long idSolicitud, SolicitudPermisoRequestDTO request, Integer nominaEmpleado) {
        SolicitudPermiso permiso = permisoRepo.findById(idSolicitud.intValue())
                .orElseThrow(() -> new RuntimeException("Incidencia no encontrada en la base de datos."));

        if (!permiso.getEmpleado().getNomina().equals(nominaEmpleado)) {
            throw new SecurityException("Bloqueo de Seguridad: No tienes permiso para editar esta solicitud.");
        }

        if (!"PENDIENTE".equalsIgnoreCase(permiso.getEstatus())) {
            throw new RuntimeException("Auditoría: Solo puedes editar incidencias que aún están en estatus PENDIENTE.");
        }

        TipoPermiso tipoPermiso = tipoPermisoRepo.findByCodigo(request.getCodigoPermiso())
                .orElseThrow(() -> new RuntimeException("Error Operativo: El tipo de permiso seleccionado no existe."));

        Turno turnoSeleccionado = null;
        if (request.getNombreTurno() != null && !request.getNombreTurno().isEmpty()) {
            turnoSeleccionado = turnoRepo.findByNombreTurno(request.getNombreTurno()).orElse(null);
        }

        LocalDate fechaMinima = obtenerFechaMinimaPermitida(permiso.getEmpleado());
        List<LocalDate> fechasIncidencia = (request.getFechasIncidencia() != null && !request.getFechasIncidencia().isEmpty())
                ? request.getFechasIncidencia()
                : Collections.singletonList(request.getFechaIncidencia());

        for (LocalDate fecha : fechasIncidencia) {
            if (fecha.isBefore(fechaMinima)) {
                throw new RuntimeException("Bloqueo de Nómina: La fecha " + fecha + " pertenece a una semana contable cerrada.");
            }
            boolean chocaConVacaciones = vacacionesRepo.verificarInterferenciaVacaciones(
                    permiso.getEmpleado().getNomina(), fecha, Arrays.asList("APROBADO", "PENDIENTE_JEFE", "PENDIENTE_SUPERVISOR"));
            if (chocaConVacaciones) {
                throw new RuntimeException("Error de Agenda: La fecha " + fecha + " choca con un periodo de vacaciones de este colaborador.");
            }
        }

        permiso.setTipoPermiso(tipoPermiso);
        permiso.setTurno(turnoSeleccionado);
        permiso.setFechaIncidencia(fechasIncidencia.get(0));
        permiso.setJustificacionSupervisor(request.getJustificacionSupervisor());
        permiso.setHorasPermiso(request.getHorasPermiso() != null ? request.getHorasPermiso() : BigDecimal.ZERO);
        permiso.setEsPorHoras(request.getEsPorHoras() != null ? request.getEsPorHoras() : false);

        if ("TXT".equalsIgnoreCase(tipoPermiso.getCodigo())) {
            permiso.getDesglosesPago().clear();
            permisoRepo.flush();
            procesarValidacionesTXT(permiso, request, permiso.getEmpleado(), fechasIncidencia.size());
        } else {
            permiso.getDesglosesPago().clear();
        }

        permisoRepo.save(permiso);
    }

    public List<SolicitudPermiso> obtenerPermisosParaAuditoriaRH(LocalDate fechaInicioRango) {
        return permisoRepo.findAll().stream()
                .filter(p -> p.getEstatus() != null)
                .filter(p -> List.of("APROBADO", "RETENIDO").contains(p.getEstatus().toUpperCase()))
                .filter(p -> p.getTipoPermiso() != null && !"V".equalsIgnoreCase(p.getTipoPermiso().getCodigo()) && !"VACACIONES".equalsIgnoreCase(p.getTipoPermiso().getCodigo()))
                .filter(p -> p.getFechaIncidencia() != null && !p.getFechaIncidencia().isBefore(fechaInicioRango)) // ✨ FILTRO DE SEMANA
                .sorted(java.util.Comparator.comparing(SolicitudPermiso::getFechaIncidencia).reversed())
                .collect(Collectors.toList());
    }

    @Transactional
    public void rechazarPermisoAuditoria(Long idSolicitud, String comentarioRechazo) {
        SolicitudPermiso sol = permisoRepo.findById(idSolicitud.intValue())
                .orElseThrow(() -> new RuntimeException("Error: No se encontró la incidencia."));

        sol.setEstatus("RECHAZADO");
        if (comentarioRechazo != null && !comentarioRechazo.trim().isEmpty()) {
            sol.setJustificacionSupervisor((sol.getJustificacionSupervisor() != null ? sol.getJustificacionSupervisor() + " | " : "") + "[AUDIT_RH / RECHAZO FORZADO]: " + comentarioRechazo);
        }
        permisoRepo.save(sol);
    }

    @Transactional
    public void retenerIncidenciaTxt(Long solicitudId) {
        SolicitudPermiso permiso = permisoRepo.findById(solicitudId.intValue())
                .orElseThrow(() -> new RuntimeException("Incidencia no encontrada"));

        permiso.setEstatus("RETENIDO");
        if (permiso.getJustificacionSupervisor() != null && !permiso.getJustificacionSupervisor().contains("[RETENIDO]")) {
            permiso.setJustificacionSupervisor("[RETENIDO] " + permiso.getJustificacionSupervisor());
        }
        permisoRepo.save(permiso);
    }

    @Transactional
    public void auditarPermisoBolsa(Long idSolicitud, boolean aprobado, String comentarioRechazo) {
        SolicitudPermiso sol = permisoRepo.findById(idSolicitud.intValue())
                .orElseThrow(() -> new RuntimeException("Error: No se encontró la incidencia."));

        sol.setEstatus(aprobado ? "APLICADO_BOLSA" : "RECHAZADO_BOLSA");
        if (comentarioRechazo != null && !comentarioRechazo.trim().isEmpty()) {
            sol.setJustificacionSupervisor((sol.getJustificacionSupervisor() != null ? sol.getJustificacionSupervisor() + " | " : "") + "[AUDIT_RH]: " + comentarioRechazo);
        }
        permisoRepo.save(sol);
    }

    @Transactional
    public int cerrarNominaLimpiarBandeja() {
        // Barremos la bolsa
        List<SolicitudPermiso> bolsa = permisoRepo.findAll().stream()
                .filter(p -> p.getEstatus() != null && List.of("APLICADO_BOLSA", "RECHAZADO_BOLSA").contains(p.getEstatus().toUpperCase()))
                .collect(Collectors.toList());

        int count = 0;
        for (SolicitudPermiso p : bolsa) {
            p.setEstatus(p.getEstatus() + "_HISTORIAL");
            permisoRepo.save(p);
            count++;
        }
        return count;
    }

    /**
     * Trae el historial reciente de permisos (Aprobados/Rechazados) para la tabla de Revocación.
     */
    public List<SolicitudPermiso> obtenerHistorialDecisionesPermisosLinea(Integer nominaJefe, List<Integer> wcIds) {
        return permisoRepo.findAll().stream()
                .filter(p -> p.getEstatus() != null &&
                        (p.getEstatus().equalsIgnoreCase("APROBADO") || p.getEstatus().equalsIgnoreCase("RECHAZADO")))
                .filter(p -> p.getEmpleado() != null &&
                        p.getEmpleado().getWorkCenter() != null &&
                        wcIds.contains(p.getEmpleado().getWorkCenter().getId()))
                .filter(p -> p.getTipoPermiso() != null &&
                        !"V".equalsIgnoreCase(p.getTipoPermiso().getCodigo())) // Excluir vacaciones puras
                .sorted(java.util.Comparator.comparing(SolicitudPermiso::getFechaSolicitud).reversed())
                .limit(50) // Limitamos a los últimos 50 para no saturar la vista
                .collect(Collectors.toList());
    }

    /**
     * Ejecuta el Override del Supervisor sobre un permiso ya procesado.
     */
    @Transactional
    public void aplicarOverrideSupervisorPermiso(Long idSolicitud, Integer nominaSupervisor, String nuevoEstatus, String comentario) {
        SolicitudPermiso sol = permisoRepo.findById(idSolicitud.intValue())
                .orElseThrow(() -> new RuntimeException("Error: No se encontró la incidencia #" + idSolicitud));

        Empleado supervisor = empleadoRepo.findById(nominaSupervisor)
                .orElseThrow(() -> new RuntimeException("Supervisor no encontrado en el sistema."));

        sol.setEstatus(nuevoEstatus);
        sol.setJustificacionSupervisor((sol.getJustificacionSupervisor() != null ? sol.getJustificacionSupervisor() + " | " : "")
                + "[REVOCACIÓN SUPERVISOR]: " + comentario);
        sol.setResueltoPor(supervisor);

        permisoRepo.save(sol);
    }
}
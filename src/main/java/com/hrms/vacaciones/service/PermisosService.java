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

    /**
     * Trae la lista de permisos activos filtrando dinámicamente si el empleado es Sindicalizado o Administrativo.
     */
    public List<TipoPermiso> obtenerPermisosParaEmpleado(Integer empleadoNomina) {
        Empleado empleado = empleadoRepo.findById(empleadoNomina)
                .orElseThrow(() -> new RuntimeException("Error: No existe el empleado con nómina: " + empleadoNomina));

        List<String> mundos = new ArrayList<>();
        mundos.add("TODOS");

        if ("Sindicalizado".equalsIgnoreCase(empleado.getTipoEmpleado()) || "SIND".equalsIgnoreCase(empleado.getTipoEmpleado())) {
            mundos.add("SIND");
        } else {
            mundos.add("ADMIN");
        }

        return tipoPermisoRepo.findByActivoTrueAndAplicaAIn(mundos);
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

        // 🎯 SOPORTE MULTI-DÍA: Extraemos la lista, si viene vacía usamos la fecha singular.
        List<LocalDate> fechasIncidencia = (request.getFechasIncidencia() != null && !request.getFechasIncidencia().isEmpty())
                ? request.getFechasIncidencia()
                : Collections.singletonList(request.getFechaIncidencia());

        LocalDate lunesSemanaActual = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        // 1. Validar TODAS las fechas antes de intentar guardar
        for (LocalDate fecha : fechasIncidencia) {
            if (fecha.isBefore(lunesSemanaActual)) {
                throw new RuntimeException("Bloqueo de Nómina: La fecha de la falta (" + fecha + ") pertenece a una semana nominal cerrada contablemente.");
            }

            boolean chocaConVacaciones = vacacionesRepo.verificarInterferenciaVacaciones(
                    empleado.getNomina(), fecha, Arrays.asList("APROBADO", "PENDIENTE_JEFE", "PENDIENTE_SUPERVISOR"));

            if (chocaConVacaciones) {
                throw new RuntimeException("Error de Agenda: La fecha de incidencia (" + fecha + ") choca con un periodo de vacaciones ya registrado.");
            }
        }

        // 2. Crear las filas contables
        // 🎯 EL PUENTE: Buscamos el turno en BD usando el nombre que mandó el HTML
        Turno turnoSeleccionado = null;
        if (request.getNombreTurno() != null && !request.getNombreTurno().isEmpty()) {
            turnoSeleccionado = turnoRepo.findByNombreTurno(request.getNombreTurno()).orElse(null);
        }

        for (int i = 0; i < fechasIncidencia.size(); i++) {
            LocalDate fechaActual = fechasIncidencia.get(i);

            SolicitudPermiso nuevaSolicitud = SolicitudPermiso.builder()
                    .empleado(empleado)
                    .tipoPermiso(tipoPermiso)
                    .turno(turnoSeleccionado) // 🎯 INYECTAMOS EL TURNO AQUÍ
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
                    // Amarra toda la deuda y desglose al DÍA 1
                    procesarValidacionesTXT(nuevaSolicitud, request, empleado, fechasIncidencia.size());
                } else {
                    // Los demás días son extensiones sin deuda adicional
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

        // 🎯 SOPORTE MULTI-DÍA: Extraemos la lista
        List<LocalDate> fechasIncidencia = (request.getFechasIncidencia() != null && !request.getFechasIncidencia().isEmpty())
                ? request.getFechasIncidencia()
                : Collections.singletonList(request.getFechaIncidencia());

        LocalDate lunesSemanaActual = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        // 1. Validar el bloque completo
        for (LocalDate fecha : fechasIncidencia) {
            if (fecha.isBefore(lunesSemanaActual)) {
                throw new RuntimeException("Bloqueo de Lote: La fecha del paro (" + fecha + ") pertenece a una semana nominal cerrada contablemente.");
            }

            boolean chocaConVacaciones = vacacionesRepo.verificarInterferenciaVacaciones(
                    empleado.getNomina(), fecha, Arrays.asList("APROBADO", "PENDIENTE_JEFE", "PENDIENTE_SUPERVISOR"));

            if (chocaConVacaciones) {
                throw new RuntimeException("Error de Agenda: La fecha de incidencia (" + fecha + ") choca con un periodo de vacaciones ya registrado para " + empleado.getNombreCompleto() + ".");
            }
        }

        // 2. Inyección Masiva en Caliente
        // 🎯 EL PUENTE: Buscamos el turno asignado masivamente en el bloque
        Turno turnoSeleccionado = null;
        if (request.getNombreTurno() != null && !request.getNombreTurno().isEmpty()) {
            turnoSeleccionado = turnoRepo.findByNombreTurno(request.getNombreTurno()).orElse(null);
        }

        for (int i = 0; i < fechasIncidencia.size(); i++) {
            LocalDate fechaActual = fechasIncidencia.get(i);

            SolicitudPermiso nuevaSolicitud = SolicitudPermiso.builder()
                    .empleado(empleado)
                    .tipoPermiso(tipoPermiso)
                    .turno(turnoSeleccionado) // 🎯 INYECTAMOS EL TURNO AQUÍ TAMBIÉN
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
                    // Amarra toda la deuda y desglose al DÍA 1
                    procesarValidacionesTXT(nuevaSolicitud, request, empleado, fechasIncidencia.size());
                } else {
                    // Complementos sin duplicar los pagos
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

        // 🎯 REGLA DINÁMICA: Máximo 2 exhibiciones de pago por cada DÍA de ausencia.
        int limitePagos = diasParo * 2;
        if (filasPago.size() > limitePagos) {
            throw new RuntimeException("Regla de Nómina: No puedes fraccionar el pago de horas en más de " + limitePagos + " exhibiciones.");
        }

        LocalDate lunesSemanaActual = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String nombreTurnoEmpleado = request.getNombreTurno() != null ? request.getNombreTurno() : "1ro";

        Turno turnoOficial = turnoRepo.findByNombreTurno(nombreTurnoEmpleado)
                .orElseThrow(() -> new RuntimeException("Error Técnico: No se encontró la configuración del turno '" + nombreTurnoEmpleado + "' en la Torre de Control."));
        BigDecimal javaHorasActuales = turnoOficial.getHorasJornada();

        BigDecimal horasBasePorDia = Boolean.TRUE.equals(request.getEsPorHoras()) && request.getHorasPermiso() != null
                ? request.getHorasPermiso()
                : javaHorasActuales;

        // 🎯 LA ECUACIÓN MAESTRA: Multiplicamos la jornada por la cantidad de días del lote
        BigDecimal horasObjetivoTotal = horasBasePorDia.multiply(new BigDecimal(diasParo));

        BigDecimal javaSumaHorasCapturadas = BigDecimal.ZERO;

        for (FilaPagoTxtDTO pago : filasPago) {
            if (pago.getFechaPago().isBefore(lunesSemanaActual)) {
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
        return permisoRepo.findAll().stream()
                .filter(s -> "PENDIENTE".equalsIgnoreCase(s.getEstatus()))
                .filter(s -> s.getTipoPermiso() != null
                        && s.getTipoPermiso().getCodigo() != null
                        && !"V".equalsIgnoreCase(s.getTipoPermiso().getCodigo().trim())
                        && !"VACACIONES".equalsIgnoreCase(s.getTipoPermiso().getCodigo().trim()))
                .filter(s -> (s.getEmpleado().getJefeDirectoNomina() != null && s.getEmpleado().getJefeDirectoNomina().equals(nominaJefe)) ||
                        (s.getEmpleado().getWorkCenter() != null && wcIds.contains(s.getEmpleado().getWorkCenter().getId())))
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

        LocalDate lunesSemanaActual = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (fechaIncidencia.isBefore(lunesSemanaActual)) {
            throw new RuntimeException("Bloqueo de Lote: La fecha del paro o afectación masiva pertenece a un periodo contable cerrado.");
        }

        TipoPermiso tipoTxt = tipoPermisoRepo.findByCodigo("TXT")
                .orElseThrow(() -> new RuntimeException("Error Técnico: El tipo de permiso 'TXT' no está configurado en los catálogos maestros."));

        Empleado jefeQueResuelve = empleadoRepo.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Error: Jefe autorizador del lote no encontrado."));

        for (Integer nominaEmp : empleadoIds) {
            Empleado empleado = empleadoRepo.findById(nominaEmp)
                    .orElseThrow(() -> new RuntimeException("No existe el colaborador con nómina #" + nominaEmp));

            // 🏁 CANDADO DE TRAZABILIDAD: La "barredora" en caliente para reescribir planes de pago TXT
            // Si el operador ya tiene un TXT registrado EXACTAMENTE para esta misma fecha de paro,
            // lo exterminamos de la base de datos (con todo y sus pagos viejos) para meter el plan nuevo.
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
}
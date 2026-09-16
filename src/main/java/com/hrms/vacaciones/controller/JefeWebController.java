package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.dto.RespuestaJefeRequest;
import com.hrms.vacaciones.model.*;
import com.hrms.vacaciones.repository.EmpleadoRepository;
import com.hrms.vacaciones.repository.MotivoRechazoRepository;
import com.hrms.vacaciones.repository.SolicitudVacacionesRepository;
import com.hrms.vacaciones.repository.TurnoRepository;
import com.hrms.vacaciones.service.PermisosService;
import com.hrms.vacaciones.service.VacacionesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.hrms.vacaciones.repository.ConfiguracionSistemaRepository;
import com.hrms.vacaciones.repository.PeriodoInhabilRepository;
import com.hrms.vacaciones.repository.DiasFestivosRepository;

@Controller
public class JefeWebController {

    private final VacacionesService vacacionesService;
    private final PermisosService permisosService;
    private final EmpleadoRepository empleadoRepository;
    private final SolicitudVacacionesRepository solicitudVacacionesRepository;
    private final TurnoRepository turnoRepository;
    private final MotivoRechazoRepository motivoRechazoRepository;
    private final ConfiguracionSistemaRepository configuracionSistemaRepository;
    private final PeriodoInhabilRepository periodoInhabilRepository;
    private final DiasFestivosRepository diasFestivosRepository;

    @Autowired
    public JefeWebController(VacacionesService vacacionesService,
                             PermisosService permisosService,
                             EmpleadoRepository empleadoRepository,
                             SolicitudVacacionesRepository solicitudVacacionesRepository,
                             TurnoRepository turnoRepository,
                             MotivoRechazoRepository motivoRechazoRepository,
                             PeriodoInhabilRepository periodoInhabilRepository,
                             ConfiguracionSistemaRepository configuracionSistemaRepository,
                             DiasFestivosRepository diasFestivosRepository) {
        this.vacacionesService = vacacionesService;
        this.permisosService = permisosService;
        this.empleadoRepository = empleadoRepository;
        this.solicitudVacacionesRepository = solicitudVacacionesRepository;
        this.turnoRepository = turnoRepository;
        this.motivoRechazoRepository = motivoRechazoRepository;
        this.configuracionSistemaRepository = configuracionSistemaRepository;
        this.periodoInhabilRepository = periodoInhabilRepository;
        this.diasFestivosRepository = diasFestivosRepository;
    }

    @GetMapping("/pantallas/aprobaciones-jefe")
    public String mostrarPortalAprobaciones(HttpSession session, Model model) {
        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) {
            return "redirect:/login";
        }
        Integer nominaJefe = Integer.parseInt(logueadoObj.toString());

        Empleado jefe = empleadoRepository.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Jefe no encontrado en la base de datos"));
        model.addAttribute("jefe", jefe);

        List<WorkCenter> misLineas = vacacionesService.obtenerWorkCentersPorJefe(nominaJefe);
        model.addAttribute("workCentersACargo", misLineas);

        List<Integer> wcIds = (misLineas != null) ? misLineas.stream().map(WorkCenter::getId).toList() : new ArrayList<>();

        boolean alertaAutopilot = false;
        if (misLineas != null && !misLineas.isEmpty()) {
            List<HistoricoConfiguracionArea> historico = vacacionesService.obtenerHistoricoConfiguracion(nominaJefe);
            model.addAttribute("historicoConfig", historico);

            // ✨ DETECTOR DEL ROBOT: ¿El último movimiento fue del sistema en los últimos 3 días?
            if (!historico.isEmpty()) {
                HistoricoConfiguracionArea ultimoLog = historico.get(0);
                if ("AUTOGENERADO_SISTEMA".equals(ultimoLog.getAccion()) &&
                        ultimoLog.getFechaRegistro().isAfter(LocalDateTime.now().minusDays(3))) {
                    alertaAutopilot = true;
                }
            }
        } else {
            model.addAttribute("historicoConfig", List.of());
        }
        model.addAttribute("alertaAutopilot", alertaAutopilot);

        // 🔒 MUNDO 1: Cómputo de Vacaciones Ordinarias Pendientes
        List<SolicitudVacaciones> pendientes = vacacionesService.obtenerPendientesPorJefe(nominaJefe);
        model.addAttribute("pendientes", pendientes);
        int totalVacaciones = pendientes != null ? pendientes.size() : 0;
        model.addAttribute("totalPendientes", totalVacaciones);

        // 🔒 MUNDO 2: Cómputo de Permisos e Incidencias (Individuales + Rangos PT/HO)
        List<SolicitudPermiso> permisosPendientes = permisosService.obtenerPermisosPendientesPorJefe(nominaJefe, wcIds);
        int totalPermisosIndividuales = permisosPendientes != null ? permisosPendientes.size() : 0;

        List<SolicitudVacaciones> rangosDinamicosPendientes = vacacionesService.obtenerRangosPermisosPendientesPorJefe(nominaJefe);
        int totalRangosDinamicos = rangosDinamicosPendientes != null ? rangosDinamicosPendientes.size() : 0;

        int totalPermisosGral = totalPermisosIndividuales + totalRangosDinamicos;

        // Inyecciones contables para las alertas globales del Layout
        model.addAttribute("alertaVacacionesCount", totalVacaciones);
        model.addAttribute("alertaPermisosCount", totalPermisosGral);

        // 🛡️ SHIELD ANTI-F5 / REDIRECCIONES: Solo se activa el modal al primer landing de sesión
        if (session.getAttribute("alertaLoginMostrada") == null) {
            model.addAttribute("mostrarAlertaModal", (totalVacaciones + totalPermisosGral) > 0);
            session.setAttribute("alertaLoginMostrada", true);
        } else {
            model.addAttribute("mostrarAlertaModal", false);
        }

        // Bloque de historial de decisiones para el Supervisor
        if (jefe.getRolJerarquico() != null && "SUPERVISOR".equalsIgnoreCase(jefe.getRolJerarquico().trim())) {
            model.addAttribute("historialDecisiones", vacacionesService.obtenerHistorialDecisionesLinea(nominaJefe));

            // 🚀 INYECCIÓN PARA LA VISTA DE GRUPOS DE PROCESO (CADENERO V2)
            List<CentroCosto> todosMisCcs = vacacionesService.obtenerCentrosCostoPorJefe(nominaJefe);
            List<GrupoProceso> misGrupos = vacacionesService.obtenerGruposProcesoPorJefe(nominaJefe);

            // Filtramos CCs ocupados
            List<Integer> ccsOcupados = misGrupos.stream()
                    .filter(g -> "CC".equals(g.getTipoAgrupacion()) && g.getCentrosCosto() != null)
                    .flatMap(g -> g.getCentrosCosto().stream())
                    .map(CentroCosto::getId)
                    .toList();

            List<CentroCosto> ccsLibres = todosMisCcs.stream()
                    .filter(cc -> !ccsOcupados.contains(cc.getId()))
                    .toList();

            // Filtramos WCs ocupados
            List<Integer> wcsOcupados = misGrupos.stream()
                    .filter(g -> "WC".equals(g.getTipoAgrupacion()) && g.getWorkCenters() != null)
                    .flatMap(g -> g.getWorkCenters().stream())
                    .map(WorkCenter::getId)
                    .toList();

            List<WorkCenter> wcsLibres = misLineas.stream()
                    .filter(wc -> !wcsOcupados.contains(wc.getId()))
                    .toList();

            model.addAttribute("misCentrosCostoLibres", ccsLibres);
            model.addAttribute("misWorkCentersLibres", wcsLibres);
            model.addAttribute("misGruposProceso", misGrupos);

        } else {
            // ✨ EL BLOQUE DE LOS SHIFT LEADERS ✨
            model.addAttribute("historialDecisiones", List.of());

            // Rescatamos los grupos a los que pertenecen sus líneas para que el Radar cobre vida
            List<GrupoProceso> misGrupos = vacacionesService.obtenerGruposProcesoPorJefe(nominaJefe);
            model.addAttribute("misGruposProceso", misGrupos);
        }

        Map<String, Long> stats = vacacionesService.obtenerEstadisticasJefe(nominaJefe);
        model.addAttribute("aprobadasMes", stats.get("aprobadasMes"));
        model.addAttribute("proximasAusencias", stats.get("proximasAusencias"));

        List<Empleado> plantilla = vacacionesService.obtenerPlantillaDelJefe(nominaJefe);
        model.addAttribute("plantilla", plantilla);

        // ✨ PIPELINE JERÁRQUICO: Carga de Shift Leaders exclusiva para Supervisores
        List<Empleado> misShiftLeaders = new ArrayList<>();
        if (jefe.getRolJerarquico() != null && "SUPERVISOR".equalsIgnoreCase(jefe.getRolJerarquico().trim())) {
            misShiftLeaders = vacacionesService.obtenerShiftLeadersDeSupervisor(nominaJefe);
        }
        model.addAttribute("misShiftLeaders", misShiftLeaders);

        // ✨ DENSIDAD DE PISO: Conteo contable en caliente por Work Center
        java.util.Map<Integer, Long> headcountMap = vacacionesService.obtenerHeadcountPorWorkCenter(plantilla);
        model.addAttribute("headcountMap", headcountMap);

        String mesActualRaw = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM yyyy", new java.util.Locale("es", "ES")));
        model.addAttribute("mesActual", mesActualRaw.substring(0, 1).toUpperCase() + mesActualRaw.substring(1));

        model.addAttribute("resumenLotesColectivos", vacacionesService.obtenerResumenLotesColectivos(nominaJefe));
        model.addAttribute("todasColectivasDetalle", vacacionesService.obtenerTodasColectivasPorJefeProyectado(nominaJefe));
        model.addAttribute("listaTurnosMaster", turnoRepository.findByActivoTrue());

        // 🛡️ Tubería de Motivos de Rechazo oficiales para Vacaciones
        model.addAttribute("motivosRechazo", motivoRechazoRepository.findByModuloAndActivoTrue("VACACIONES"));

        // ✨ NUEVAS LÍNEAS: Rescatar el SLA de los Jefes desde Torre de Control
        String slaJefeStr = configuracionSistemaRepository.findById("SLA_RESPUESTA_JEFE")
                .map(com.hrms.vacaciones.model.ConfiguracionSistema::getValor)
                .orElse("48");
        model.addAttribute("horasSlaJefe", Integer.parseInt(slaJefeStr));

        // 👇 NUEVA LÍNEA: Inyectamos los Quintiles calculados (Solo 1 vez)
        model.addAttribute("distribucionQuintiles", vacacionesService.obtenerDistribucionQuintiles(nominaJefe));

        // 👇 NUEVA LÍNEA: Inyectamos los Blackout Dates (Periodos Inhábiles)
        model.addAttribute("periodosInhabiles", periodoInhabilRepository.findBySupervisorNominaOrderByFechaInicioDesc(nominaJefe));

        // 👇 NUEVAS LÍNEAS: Inyectamos los feriados para que el Radar de Cinépolis los bloquee
        List<String> fechasFestivas = diasFestivosRepository.findByActivoTrue().stream()
                .map(festivo -> festivo.getFecha().toString())
                .collect(Collectors.toList());
        model.addAttribute("festivos", fechasFestivas);

        return "aprobaciones-jefe";
    }

    @PostMapping("/jefe/responder")
    public String responderSolicitud(
            @RequestParam("idSolicitud") Integer idSolicitud,
            @RequestParam(value = "aprobado", defaultValue = "true") boolean aprobado,
            HttpSession session) {

        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaJefe = Integer.parseInt(logueadoObj.toString());

        if (aprobado) {
            vacacionesService.aprobarPorJefe(idSolicitud, nominaJefe);
        } else {
            RespuestaJefeRequest request = new RespuestaJefeRequest(idSolicitud, false, "Rechazado desde acción rápida");
            vacacionesService.procesarRespuestaJefe(request);
        }
        return "redirect:/pantallas/aprobaciones-jefe";
    }

    @PostMapping("/jefe/rechazar")
    public String rechazarSolicitud(
            @RequestParam("idSolicitud") Integer idSolicitud,
            @RequestParam("comentario") String comentario) {

        RespuestaJefeRequest request = new RespuestaJefeRequest(idSolicitud, false, comentario);
        vacacionesService.procesarRespuestaJefe(request);
        return "redirect:/pantallas/aprobaciones-jefe";
    }

    @PostMapping("/jefe/responder-masivo")
    public String responderMasivo(
            @RequestParam(value = "ids", required = false) List<Integer> ids,
            @RequestParam("status") String status,
            @RequestParam("comentario") String comentario,
            HttpSession session) {

        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaJefe = Integer.parseInt(logueadoObj.toString());

        if (ids != null && !ids.isEmpty()) {
            boolean esAprobado = "APROBADO".equalsIgnoreCase(status);
            for (Integer id : ids) {
                if (esAprobado) {
                    vacacionesService.aprobarPorJefe(id, nominaJefe);
                } else {
                    RespuestaJefeRequest request = new RespuestaJefeRequest(id, false, comentario);
                    vacacionesService.procesarRespuestaJefe(request);
                }
            }
        }
        return "redirect:/pantallas/aprobaciones-jefe";
    }

    @PostMapping("/jefe/override")
    public String overrideSolicitud(
            @RequestParam("idSolicitud") Integer idSolicitud,
            @RequestParam("nuevoEstatus") String nuevoEstatus,
            @RequestParam("comentario") String comentario,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaSupervisor = Integer.parseInt(logueadoObj.toString());

        if (comentario == null || comentario.trim().isEmpty() || comentario.trim().length() < 8) {
            redirectAttributes.addFlashAttribute("mensajeError", "El comentario de auditoría es estrictamente obligatorio para realizar un Override (Mínimo 8 letras).");
            return "redirect:/pantallas/aprobaciones-jefe";
        }

        try {
            vacacionesService.aplicarOverrideSupervisor(idSolicitud, nominaSupervisor, nuevoEstatus, comentario);
            redirectAttributes.addFlashAttribute("mensajeExito", "¡Revocación Exitosa! La decisión ha sido modificada y los saldos fueron recalculados en caliente.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "No se pudo aplicar el Override: " + e.getMessage());
        }

        return "redirect:/pantallas/aprobaciones-jefe";
    }

    @PostMapping("/jefe/responder-parcial")
    public String responderParcial(
            @RequestParam("idSolicitud") Integer idSolicitud,
            @RequestParam(value = "fechasRechazadas", required = false) String fechasRechazadasStr,
            @RequestParam(value = "motivosRechazo", required = false) String motivosRechazoStr,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaJefe = Integer.parseInt(logueadoObj.toString());

        List<String> fechas = (fechasRechazadasStr != null && !fechasRechazadasStr.isEmpty())
                ? List.of(fechasRechazadasStr.split(",")) : new ArrayList<>();
        List<String> motivos = (motivosRechazoStr != null && !motivosRechazoStr.isEmpty())
                ? List.of(motivosRechazoStr.split("\\|")) : new ArrayList<>();

        try {
            vacacionesService.procesarAprobacionParcial(idSolicitud, nominaJefe, fechas, motivos);
            redirectAttributes.addFlashAttribute("mensajeExito", "¡Cirugía Contable Aplicada! La solicitud fue dividida y los saldos han sido recalculados.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al procesar la aprobación parcial: " + e.getMessage());
        }
        return "redirect:/pantallas/aprobaciones-jefe";
    }

    @PostMapping("/jefe/configurar-lineas")
    public String guardarConfiguracionArea(
            @RequestParam("mes") Integer mes,
            @RequestParam("anio") Integer anio,
            @RequestParam(value = "fechaLimiteStr", required = false) String fechaLimiteStr,
            @RequestParam(value = "fechaLimiteRegistro", required = false) String fechaLimiteRegistro,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaJefe = Integer.parseInt(logueadoObj.toString());

        String fechaLimiteReal = (fechaLimiteStr != null && !fechaLimiteStr.isEmpty()) ? fechaLimiteStr : fechaLimiteRegistro;

        try {
            if (fechaLimiteReal == null) {
                throw new IllegalArgumentException("Faltan fechas obligatorias en el formulario.");
            }

            // ✨ Mandamos llamar al servicio solo con los datos de tiempo (Ya no hay turnos ni cupos aquí)
            vacacionesService.guardarConfiguracionArea(nominaJefe, mes, anio, fechaLimiteReal);

            redirectAttributes.addFlashAttribute("mensajeExito", "El periodo de registro se ha abierto correctamente con las fechas calculadas.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "No se pudo guardar la configuración: " + e.getMessage());
        }

        return "redirect:/pantallas/aprobaciones-jefe?tab=reglas";
    }

    @PostMapping("/jefe/grupos/crear")
    public String crearGrupoProceso(
            @RequestParam("nombreGrupo") String nombreGrupo,
            @RequestParam("cupoMaximo") Integer cupoMaximo,
            @RequestParam(value = "tipoAgrupacion", defaultValue = "CC") String tipoAgrupacion,
            @RequestParam(value = "cupoMaximoTurno", required = false) Integer cupoMaximoTurno,
            @RequestParam(value = "centrosCostoIds", required = false) List<Integer> centrosCostoIds,
            @RequestParam(value = "workCenterIds", required = false) List<Integer> workCenterIds,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaJefe = Integer.parseInt(logueadoObj.toString());

        try {
            vacacionesService.crearGrupoProceso(nombreGrupo, cupoMaximo, tipoAgrupacion, cupoMaximoTurno, centrosCostoIds, workCenterIds, nominaJefe);
            redirectAttributes.addFlashAttribute("mensajeExito", "¡Grupo '" + nombreGrupo.toUpperCase() + "' creado exitosamente bajo el esquema de " + tipoAgrupacion + "!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al crear el grupo: " + e.getMessage());
        }

        return "redirect:/pantallas/aprobaciones-jefe?tab=reglas";
    }

    @PostMapping("/jefe/grupos/eliminar")
    public String eliminarGrupoProceso(
            @RequestParam("idGrupo") Integer idGrupo,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";

        try {
            // Llamamos al motor de borrado
            vacacionesService.eliminarGrupoProceso(idGrupo);
            redirectAttributes.addFlashAttribute("mensajeExito", "Grupo eliminado con éxito. Sus Centros de Costo han quedado libres nuevamente.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al eliminar el grupo: " + e.getMessage());
        }

        return "redirect:/pantallas/aprobaciones-jefe?tab=reglas";
    }

    @PostMapping("/jefe/solicitar-recuperacion")
    public String solicitarRecuperacionFalta(
            @RequestParam("nominaEmpleado") Integer nominaEmpleado,
            @RequestParam("fechaFalta") String fechaFaltaStr,
            @RequestParam("justificacion") String justificacion,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaJefe = Integer.parseInt(logueadoObj.toString());

        try {
            LocalDate fechaFalta = LocalDate.parse(fechaFaltaStr);
            vacacionesService.crearSolicitudRecuperacion(nominaJefe, nominaEmpleado, fechaFalta, justificacion);
            redirectAttributes.addFlashAttribute("mensajeExito", "La solicitud de recuperación de falta ha sido registrada.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "No se pudo procesar la recuperación: " + e.getMessage());
        }
        return "redirect:/pantallas/aprobaciones-jefe?tab=plantilla";
    }

    @GetMapping("/jefe/configuracion/exportar")
    public void exportarHistoricoReglas(HttpSession session, HttpServletResponse response) throws IOException {
        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return;
        Integer nominaJefe = Integer.parseInt(logueadoObj.toString());

        // ✨ Inyectamos la nueva consulta V2
        List<HistoricoConfiguracionArea> historico = vacacionesService.obtenerHistoricoConfiguracion(nominaJefe);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"auditoria_reglas_jefe_" + nominaJefe + ".csv\"");
        response.getOutputStream().write(0xEF);
        response.getOutputStream().write(0xBB);
        response.getOutputStream().write(0xBF);

        PrintWriter writer = new PrintWriter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8));

        // ✨ NUEVAS CABECERAS
        writer.println("\"Fecha Operación\",\"Periodo Configurado\",\"Fecha Límite Cierre\",\"Grupos Creados (Cupos)\",\"Bloqueos en el Mes\",\"Realizado Por\"");

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        for (HistoricoConfiguracionArea log : historico) {
            writer.println(String.format("\"%s\",\"%02d/%d\",\"%s\",\"%s\",\"%s\",\"%d\"",
                    log.getFechaRegistro() != null ? log.getFechaRegistro().format(dtf) : "N/A",
                    log.getMes(),
                    log.getAnio(),
                    log.getFechaLimiteRegistro() != null ? log.getFechaLimiteRegistro().toString() : "N/A",
                    log.getResumenGrupos() != null ? log.getResumenGrupos().replace("\"", "\"\"") : "N/A",
                    log.getResumenBloqueos() != null ? log.getResumenBloqueos().replace("\"", "\"\"") : "N/A",
                    log.getRealizadoPorNomina()
            ));
        }
        writer.flush();
        writer.close();
    }

    @PostMapping("/jefe/vacaciones-colectivas")
    public String procesarVacacionesColectivas(
            @RequestParam("fechaInicio") String inicioStr,
            @RequestParam("fechaFin") String finStr,
            @RequestParam("empleadoIds") List<Integer> empleadoIds,
            @RequestParam("rolDescansoIds") List<Integer> rolDescansoIds,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaJefe = Integer.parseInt(logueadoObj.toString());

        try {
            LocalDate inicio = LocalDate.parse(inicioStr);
            LocalDate fin = LocalDate.parse(finStr);

            List<Map<String, Object>> reporte = vacacionesService.aplicarVacacionesColectivas(empleadoIds, rolDescansoIds, inicio, fin, nominaJefe);

            redirectAttributes.addFlashAttribute("mensajeExito", "El cierre colectivo fue procesado con éxito.");
            redirectAttributes.addFlashAttribute("reporteColectivo", reporte);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al procesar el cierre masivo: " + e.getMessage());
        }
        return "redirect:/pantallas/aprobaciones-jefe?tab=colectivas";
    }

    @GetMapping("/jefe/api/calendario-eventos")
    @ResponseBody
    public List<Map<String, Object>> obtenerEventosCalendarioParaJefe(HttpSession session) {
        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return List.of();
        Integer nominaJefe = Integer.parseInt(logueadoObj.toString());
        return vacacionesService.obtenerEventosCalendarioJefe(nominaJefe);
    }

    @GetMapping("/jefe/descargar-colectivo")
    public void descargarColectivoExcel(@RequestParam("lote") String lote, HttpServletResponse response) throws IOException {
        // 1. Configuramos el tipo de archivo y el nombre exacto que pidió RH
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=Reporte_Dias_Reales_Aplicados_Cierre_Planta.csv");

        PrintWriter writer = response.getWriter();
        // 2. Firmamos el archivo con BOM UTF-8 para que Excel respete acentos y eñes
        writer.write("\uFEFF");

        // 3. Pintamos las cabeceras exactas del reporte gerencial, encerradas en comillas
        writer.println("\"ID Nómina\",\"Colaborador\",\"Turno Aplicado\",\"Días Cierre Planta\",\"Días Reales Descontados\",\"Estatus Contable\"");

        // 4. Filtramos la base de datos en caliente buscando a todos los de este lote
        List<SolicitudVacaciones> solicitudesLote = solicitudVacacionesRepository.findAll().stream()
                .filter(s -> lote.equals(s.getLoteAutorizacionMasiva()))
                .collect(Collectors.toList());

        if (solicitudesLote.isEmpty()) {
            writer.flush();
            writer.close();
            return;
        }

        // 5. Calculamos dinámicamente la fecha de inicio y fin del "Paro Técnico" general
        LocalDate batchInicio = solicitudesLote.stream()
                .map(SolicitudVacaciones::getFechaInicio)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.now());

        LocalDate batchFin = solicitudesLote.stream()
                .map(SolicitudVacaciones::getFechaFin)
                .filter(java.util.Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(LocalDate.now());

        // 6. Procesamos el desglose fila por fila
        for (SolicitudVacaciones sol : solicitudesLote) {
            String idNomina = sol.getEmpleado() != null ? String.valueOf(sol.getEmpleado().getNomina()) : "N/A";
            String nom = sol.getEmpleado() != null ? sol.getEmpleado().getNombreCompleto() : "N/A";
            String turnoInfo = sol.getTurno() != null ? sol.getTurno().getNombreTurno() : "N/A";

            int diasReales = sol.getDiasTotalesCalculados() != null ? sol.getDiasTotalesCalculados() : 0;

            // Recalculamos cuántos días le correspondían al cierre según sus días de descanso
            int diasCierrePlanta = 0;
            if (sol.getTurno() != null && sol.getTurno().getDiasDescanso() != null) {
                String descansos = sol.getTurno().getDiasDescanso().toUpperCase()
                        .replace("Á", "A").replace("É", "E").replace("Í", "I").replace("Ó", "O").replace("Ú", "U").replaceAll("\\s+", "");

                LocalDate cursor = batchInicio;
                while (!cursor.isAfter(batchFin)) {
                    String diaSemana = "";
                    switch (cursor.getDayOfWeek()) {
                        case MONDAY: diaSemana = "LUNES"; break;
                        case TUESDAY: diaSemana = "MARTES"; break;
                        case WEDNESDAY: diaSemana = "MIERCOLES"; break;
                        case THURSDAY: diaSemana = "JUEVES"; break;
                        case FRIDAY: diaSemana = "VIERNES"; break;
                        case SATURDAY: diaSemana = "SABADO"; break;
                        case SUNDAY: diaSemana = "DOMINGO"; break;
                    }
                    if (!descansos.contains(diaSemana)) {
                        diasCierrePlanta++;
                    }
                    cursor = cursor.plusDays(1);
                }
            } else {
                diasCierrePlanta = diasReales;
            }

            // Diagnosticamos la razón de la diferencia (si la hay)
            String estatusContable = "Aplicado Completo";
            if (diasReales < diasCierrePlanta) {
                if (sol.getComentarioJefe() != null && sol.getComentarioJefe().contains("respetaron")) {
                    estatusContable = "Aplicado Ajustado (Cruce de Vacaciones)";
                } else if (diasReales == 0) {
                    estatusContable = "No Aplicado (Saldo 0 o Cruce Total)";
                } else {
                    estatusContable = "Capiado por Saldo Insuficiente";
                }
            }

            // Inyectamos fila protegida con comillas para evitar rupturas
            writer.println(
                    "\"" + idNomina + "\",\"" +
                            nom + "\",\"" +
                            turnoInfo + "\",\"" +
                            diasCierrePlanta + "\",\"" +
                            diasReales + "\",\"" +
                            estatusContable + "\""
            );
        }

        // 7. Liberamos memoria del servidor
        writer.flush();
        writer.close();
    }

    @GetMapping("/jefe/exportar-quintiles")
    public void exportarQuintilesAExcel(@RequestParam("nomina") Integer nominaJefe, HttpServletResponse response) throws IOException {
        // 1. Configuramos el tipo de archivo
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=Distribucion_VIP_Grupos_Proceso.csv");

        PrintWriter writer = response.getWriter();
        // 2. Firma BOM UTF-8 para Excel
        writer.write("\uFEFF");
        // 3. NUEVAS CABECERAS (Más detalladas)
        writer.println("\"Grupo de Proceso\",\"CCs del Grupo\",\"Día Asignado VIP\",\"Nómina\",\"Colaborador\",\"CC Empleado\",\"WC Empleado\",\"Saldo Devengado\",\"Antigüedad\"");

        // 4. Obtenemos el mapa complejo
        java.util.Map<String, java.util.Map<String, List<Empleado>>> quintiles = vacacionesService.obtenerDistribucionQuintiles(nominaJefe);

        // ✨ TRUCO: Volvemos a traer los grupos para mapear sus CCs en un String (Ej: 333517/33458/45646)
        List<GrupoProceso> misGrupos = vacacionesService.obtenerGruposProcesoPorJefe(nominaJefe);
        java.util.Map<String, String> mapaCcsPorGrupo = new java.util.HashMap<>();
        for (GrupoProceso g : misGrupos) {
            String ccsString = g.getCentrosCosto().stream()
                    .map(cc -> String.valueOf(cc.getId()))
                    .collect(Collectors.joining("/"));
            mapaCcsPorGrupo.put(g.getNombre(), ccsString);
        }

        // 5. Desglosamos e iteramos para imprimir
        for (java.util.Map.Entry<String, java.util.Map<String, List<Empleado>>> grupoEntry : quintiles.entrySet()) {
            String nombreGrupo = grupoEntry.getKey();
            String ccsDelGrupo = mapaCcsPorGrupo.getOrDefault(nombreGrupo, "N/A");

            for (java.util.Map.Entry<String, List<Empleado>> diaEntry : grupoEntry.getValue().entrySet()) {
                String dia = diaEntry.getKey();

                for (Empleado emp : diaEntry.getValue()) {
                    String antiguedad = emp.getFechaIngreso() != null ?
                            emp.getFechaIngreso().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "N/A";

                    // Sacamos los datos individuales de la persona
                    String ccEmp = emp.getCentroCosto() != null ? String.valueOf(emp.getCentroCosto().getId()) : "N/A";
                    String wcEmp = emp.getWorkCenter() != null ? String.valueOf(emp.getWorkCenter().getId()) : "N/A";

                    writer.println(
                            "\"" + nombreGrupo + "\",\"" +
                                    ccsDelGrupo + "\",\"" +
                                    dia + "\",\"" +
                                    emp.getNomina() + "\",\"" +
                                    emp.getNombreCompleto().replace("\"", "\"\"") + "\",\"" +
                                    ccEmp + "\",\"" +
                                    wcEmp + "\",\"" +
                                    emp.getSaldoVacacionesActual() + "\",\"" +
                                    antiguedad + "\""
                    );
                }
            }
        }
        writer.flush();
        writer.close();
    }

    @PostMapping("/jefe/bloqueos/crear")
    public String crearPeriodoInhabil(
            @RequestParam("fechaInicio") String fechaInicioStr,
            @RequestParam("fechaFin") String fechaFinStr,
            @RequestParam("motivo") String motivo,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaJefe = Integer.parseInt(logueadoObj.toString());

        try {
            LocalDate inicio = LocalDate.parse(fechaInicioStr);
            LocalDate fin = LocalDate.parse(fechaFinStr);

            if (inicio.isAfter(fin)) {
                throw new IllegalArgumentException("La fecha de inicio no puede ser mayor a la fecha final.");
            }

            PeriodoInhabil bloqueo = PeriodoInhabil.builder()
                    .supervisorNomina(nominaJefe)
                    .fechaInicio(inicio)
                    .fechaFin(fin)
                    .motivo(motivo.trim().toUpperCase())
                    .fechaRegistro(LocalDateTime.now())
                    .build();

            periodoInhabilRepository.save(bloqueo);
            redirectAttributes.addFlashAttribute("mensajeExito", "Periodo Inhábil guardado. Nadie de tus líneas podrá pedir vacaciones en esas fechas.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al guardar el bloqueo: " + e.getMessage());
        }

        return "redirect:/pantallas/aprobaciones-jefe?tab=reglas";
    }

    @PostMapping("/jefe/bloqueos/eliminar")
    public String eliminarPeriodoInhabil(
            @RequestParam("idBloqueo") Integer idBloqueo,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";

        try {
            periodoInhabilRepository.deleteById(idBloqueo);
            redirectAttributes.addFlashAttribute("mensajeExito", "Bloqueo eliminado exitosamente. Las fechas vuelven a estar libres.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al eliminar el bloqueo: " + e.getMessage());
        }

        return "redirect:/pantallas/aprobaciones-jefe?tab=reglas";
    }

    // =========================================================================
    // 🧮 API: DETERMINADOR DE SEMANA VIP EN CALIENTE
    // =========================================================================
    @GetMapping("/jefe/api/semana-vip")
    @ResponseBody
    public java.util.Map<String, String> obtenerSemanaVipApi(
            @RequestParam("mes") Integer mes,
            @RequestParam("anio") Integer anio) {

        java.util.Map<String, LocalDate> semanaVip = vacacionesService.calcularSemanaVIP(mes, anio);

        java.util.Map<String, String> response = new java.util.HashMap<>();
        response.put("inicio", semanaVip.get("inicio").toString());
        response.put("fin", semanaVip.get("fin").toString());

        return response;
    }

    @PostMapping("/jefe/workcenter/actualizar-cupo")
    public String actualizarCupoWorkCenter(
            @RequestParam("wcId") Integer wcId,
            @RequestParam("cupoConcurrente") Integer cupoConcurrente,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaJefe = Integer.parseInt(logueadoObj.toString());

        try {
            vacacionesService.actualizarCupoWorkCenter(wcId, cupoConcurrente, nominaJefe);
            redirectAttributes.addFlashAttribute("mensajeExito", "Cupo del WC " + wcId + " actualizado a " + cupoConcurrente + " operador(es) concurrentes por turno.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al actualizar cupo: " + e.getMessage());
        }

        return "redirect:/pantallas/aprobaciones-jefe?tab=reglas";
    }
}
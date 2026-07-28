package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.dto.RespuestaJefeRequest;
import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.model.HistoricoConfiguracionArea;
import com.hrms.vacaciones.model.SolicitudPermiso;
import com.hrms.vacaciones.model.SolicitudVacaciones;
import com.hrms.vacaciones.model.WorkCenter;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.hrms.vacaciones.repository.ConfiguracionSistemaRepository;

@Controller
public class JefeWebController {

    private final VacacionesService vacacionesService;
    private final PermisosService permisosService;
    private final EmpleadoRepository empleadoRepository;
    private final SolicitudVacacionesRepository solicitudVacacionesRepository;
    private final TurnoRepository turnoRepository;
    private final MotivoRechazoRepository motivoRechazoRepository;
    private final ConfiguracionSistemaRepository configuracionSistemaRepository;

    @Autowired
    public JefeWebController(VacacionesService vacacionesService,
                             PermisosService permisosService,
                             EmpleadoRepository empleadoRepository,
                             SolicitudVacacionesRepository solicitudVacacionesRepository,
                             TurnoRepository turnoRepository,
                             MotivoRechazoRepository motivoRechazoRepository,
                             ConfiguracionSistemaRepository configuracionSistemaRepository) { // ✨ ESTE PARÁMETRO FALTABA AQUÍ
        this.vacacionesService = vacacionesService;
        this.permisosService = permisosService;
        this.empleadoRepository = empleadoRepository;
        this.solicitudVacacionesRepository = solicitudVacacionesRepository;
        this.turnoRepository = turnoRepository;
        this.motivoRechazoRepository = motivoRechazoRepository;
        this.configuracionSistemaRepository = configuracionSistemaRepository;
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

        if (misLineas != null && !misLineas.isEmpty()) {
            model.addAttribute("historicoConfig", vacacionesService.obtenerHistoricoConfiguracion(wcIds));
        } else {
            model.addAttribute("historicoConfig", List.of());
        }

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
        } else {
            model.addAttribute("historialDecisiones", List.of());
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
                .orElse("48"); // 48 horas como chaleco salvavidas
        model.addAttribute("horasSlaJefe", Integer.parseInt(slaJefeStr));

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
            @RequestParam(value = "workCenterIds", required = false) List<Integer> workCenterIds,
            @RequestParam("mes") Integer mes,
            @RequestParam("anio") Integer anio,
            @RequestParam("maxPersonal") Integer maxPersonal,
            @RequestParam(value = "fechaLimiteStr", required = false) String fechaLimiteStr,
            @RequestParam(value = "fechaLimiteRegistro", required = false) String fechaLimiteRegistro,
            @RequestParam(value = "fechaRezagoStr", required = false) String fechaRezagoStr,
            @RequestParam(value = "fechaRezago", required = false) String fechaRezago,
            @RequestParam("diasMinimosRezago") Integer diasMinimosRezago,
            @RequestParam(value = "turnoIds", required = false) List<Integer> turnoIds, // ✨ NUEVO: Malla de Turnos
            @RequestParam(value = "cuposPorTurno", required = false) List<Integer> cuposPorTurno, // ✨ NUEVO: Malla de Cupos
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

            // ✨ Disparamos el cerebro matemático mandándole los arrays completos
            vacacionesService.guardarConfiguracionArea(nominaJefe, workCenterIds, mes, anio, maxPersonal,
                    fechaLimiteReal, diasMinimosRezago, turnoIds, cuposPorTurno);

            redirectAttributes.addFlashAttribute("mensajeExito", "Las reglas operativas del periodo se guardaron con éxito para toda la matriz de turnos.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "No se pudo guardar la configuración: " + e.getMessage());
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

        List<WorkCenter> misLineas = vacacionesService.obtenerWorkCentersPorJefe(nominaJefe);
        List<HistoricoConfiguracionArea> historico = List.of();

        if (misLineas != null && !misLineas.isEmpty()) {
            List<Integer> wcIds = misLineas.stream().map(WorkCenter::getId).toList();
            historico = vacacionesService.obtenerHistoricoConfiguracion(wcIds);
        }

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"historico_reglas_jefe_" + nominaJefe + ".csv\"");
        response.getOutputStream().write(0xEF);
        response.getOutputStream().write(0xBB);
        response.getOutputStream().write(0xBF);

        PrintWriter writer = new PrintWriter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8));
        // ✨ CORRECCIÓN 1: Se eliminó "WC / Linea" de los encabezados
        writer.println("Fecha Modificacion,Periodo Configurado,Tope Diario,Fecha Limite Registro,VIP Minimo Requerido,Accion Sistema,Realizado Por Nomina");

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        for (HistoricoConfiguracionArea log : historico) {
            // ✨ CORRECCIÓN 2: Se quitó el %d de WC, el log.getWorkCenterId() y la palabra " personas"
            writer.println(String.format("\"%s\",\"%02d/%d\",\"%d\",\"%s\",\"%d dias\",\"%s\",\"%d\"",
                    log.getFechaRegistro() != null ? log.getFechaRegistro().format(dtf) : "N/A",
                    log.getMes(),
                    log.getAnio(),
                    log.getMaxEmpleadosPorDia(), // Ahora escupe el puro número pelón
                    log.getFechaLimiteRegistro() != null ? log.getFechaLimiteRegistro().toString() : "N/A",
                    log.getDiasMinimosRezago(),
                    log.getAccion(),
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
}
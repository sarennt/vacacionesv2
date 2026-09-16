package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.dto.SolicitudDTO;
import com.hrms.vacaciones.repository.*;
import com.hrms.vacaciones.service.EmpleadoService;
import com.hrms.vacaciones.service.PermisosService;
import com.hrms.vacaciones.service.VacacionesService;
import com.hrms.vacaciones.dto.SolicitudVacacionesRequest;
import com.hrms.vacaciones.model.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.WeekFields;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.Comparator;

@Controller
public class VacacionesWebController {

    private final EmpleadoService empleadoService;
    private final VacacionesService vacationsService;
    private final PermisosService permisosService;
    private final EmpleadoRepository empleadoRepository;
    private final SolicitudVacacionesRepository solicitudVacacionesRepository;
    private final SolicitudPermisoRepository solicitudPermisoRepository;
    private final DiasFestivosRepository diasFestivosRepository;
    private final ConfiguracionSistemaRepository configSistemaRepo;
    private final TurnoRepository turnoRepo;
    private final TabuladorVacacionesRepository tabuladorRepo;
    private final ConfiguracionRolesNominaRepository rolesNominaRepo;


    // ✨ 1. DECLARAMOS EL REPOSITORIO DE CORTES
    private final ConfiguracionCorteNominaRepository configuracionCorteNominaRepository;

    @Autowired
    public VacacionesWebController(EmpleadoService empleadoService,
                                   VacacionesService vacationsService,
                                   PermisosService permisosService,
                                   EmpleadoRepository empleadoRepository,
                                   SolicitudVacacionesRepository solicitudVacacionesRepository,
                                   SolicitudPermisoRepository solicitudPermisoRepository,
                                   DiasFestivosRepository diasFestivosRepository,
                                   ConfiguracionSistemaRepository configSistemaRepo,
                                   TurnoRepository turnoRepo,
                                   ConfiguracionCorteNominaRepository configuracionCorteNominaRepository,
                                   TabuladorVacacionesRepository tabuladorRepo,
                                   ConfiguracionRolesNominaRepository rolesNominaRepo) { // ✨ NUEVO
        this.empleadoService = empleadoService;
        this.vacationsService = vacationsService;
        this.permisosService = permisosService;
        this.empleadoRepository = empleadoRepository;
        this.solicitudVacacionesRepository = solicitudVacacionesRepository;
        this.solicitudPermisoRepository = solicitudPermisoRepository;
        this.diasFestivosRepository = diasFestivosRepository;
        this.configSistemaRepo = configSistemaRepo;
        this.turnoRepo = turnoRepo;
        this.configuracionCorteNominaRepository = configuracionCorteNominaRepository;
        this.tabuladorRepo = tabuladorRepo;
        this.rolesNominaRepo = rolesNominaRepo; // ✨ NUEVO
    }

    @GetMapping("/pantallas/dashboard")
    public String mostrarDashboard(HttpSession session,
                                   @RequestParam(value = "fechaInicio", required = false) String fechaInicioStr,
                                   @RequestParam(value = "fechaFin", required = false) String fechaFinStr,
                                   @RequestParam(value = "nominaConsulta", required = false) Integer nominaConsulta,
                                   @RequestParam(value = "modoVisor", required = false) Boolean modoVisor,
                                   Model model) {

        Object nominaObj = session.getAttribute("usuarioLogueado");
        if (nominaObj == null) {
            return "redirect:/login";
        }

        // ✨ CIRUGÍA: Interceptar si es el Jefe espiando (Visor) o el flujo normal
        Integer nominaActiva;
        if (nominaConsulta != null && Boolean.TRUE.equals(modoVisor)) {
            nominaActiva = nominaConsulta;
            model.addAttribute("modoVisor", true); // Bandera para apagar cosas en el HTML
        } else {
            nominaActiva = Integer.parseInt(nominaObj.toString());
        }

        Empleado empleado = empleadoRepository.findById(nominaActiva)
                .orElseThrow(() -> new RuntimeException("Empleado no encontrado"));

        model.addAttribute("empleado", empleado);
        model.addAttribute("saldo", empleado.getSaldoVacacionesActual());

        // ✨ ADUANA DE LOGICA VIP: Abastecemos al dashboard con las métricas posicionales
        java.util.Map<String, Object> datosRanking = vacationsService.obtenerDatosRankingDashboard(empleado);

        model.addAllAttributes(datosRanking);

        LocalDate inicio = (fechaInicioStr != null && !fechaInicioStr.trim().isEmpty()) ? LocalDate.parse(fechaInicioStr) : null;
        LocalDate fin = (fechaFinStr != null && !fechaFinStr.trim().isEmpty()) ? LocalDate.parse(fechaFinStr) : null;

        Map<String, List<SolicitudDTO>> dominiosHistorial =
                vacationsService.obtenerHistorialSeparadoYFiltrado(nominaActiva, inicio, fin);

        // ✨ SEPARACIÓN DE VIVAS Y CANCELADAS
        List<SolicitudDTO> todasVacaciones = dominiosHistorial.get("vacaciones");
        List<SolicitudDTO> todosPermisos = dominiosHistorial.get("permisos");

        // 1. Filtramos y ordenamos Vacaciones (Vivas) por fecha de creación descendiente (lo más nuevo primero)
        List<SolicitudDTO> vacacionesVivas = todasVacaciones.stream()
                .filter(s -> !s.estatus().equalsIgnoreCase("CANCELADO"))
                .sorted((a, b) -> b.fechaCreacion().compareTo(a.fechaCreacion()))
                .collect(Collectors.toList());
        model.addAttribute("historialVacaciones", vacacionesVivas);

        // 2. Filtramos y ordenamos Permisos (Vivos) por fecha de creación descendiente
        List<SolicitudDTO> permisosVivos = todosPermisos.stream()
                .filter(s -> !s.estatus().equalsIgnoreCase("CANCELADO"))
                .sorted((a, b) -> b.fechaCreacion().compareTo(a.fechaCreacion()))
                .collect(Collectors.toList());
        model.addAttribute("historialPermisos", permisosVivos);

        // 3. Agrupamos y ordenamos Canceladas por fecha de creación descendiente
        List<SolicitudDTO> listaCanceladas = new ArrayList<>();
        listaCanceladas.addAll(todasVacaciones.stream().filter(s -> s.estatus().equalsIgnoreCase("CANCELADO")).toList());
        listaCanceladas.addAll(todosPermisos.stream().filter(s -> s.estatus().equalsIgnoreCase("CANCELADO")).toList());

        listaCanceladas.sort((a, b) -> b.fechaCreacion().compareTo(a.fechaCreacion()));
        model.addAttribute("historialCanceladas", listaCanceladas);

        model.addAttribute("fechaInicioFilter", fechaInicioStr);
        model.addAttribute("fechaFinFilter", fechaFinStr);

        String rol = empleado.getRolJerarquico() != null ? empleado.getRolJerarquico().trim().toUpperCase() : "";
        boolean esJefe = List.of("SHIFT LEADER", "SUPERVISOR", "GERENTE", "ADMIN", "RH", "NOMINAS").contains(rol);

        if (esJefe) {
            List<WorkCenter> misLineas = vacationsService.obtenerWorkCentersPorJefe(nominaActiva);
            List<Integer> wcIds = (misLineas != null) ? misLineas.stream().map(WorkCenter::getId).toList() : new ArrayList<>();

            List<SolicitudVacaciones> vacsPendientes = vacationsService.obtenerPendientesPorJefe(nominaActiva);
            int totalVacaciones = vacsPendientes != null ? vacsPendientes.size() : 0;

            List<SolicitudPermiso> permsPendientes = permisosService.obtenerPermisosPendientesPorJefe(nominaActiva, wcIds);
            int totalPermisosIndividuales = permsPendientes != null ? permsPendientes.size() : 0;

            List<SolicitudVacaciones> rangosDinamicosPendientes = vacationsService.obtenerRangosPermisosPendientesPorJefe(nominaActiva);
            int totalRangosDinamicos = rangosDinamicosPendientes != null ? rangosDinamicosPendientes.size() : 0;

            int totalPermisosGral = totalPermisosIndividuales + totalRangosDinamicos;

            model.addAttribute("alertaVacacionesCount", totalVacaciones);
            model.addAttribute("alertaPermisosCount", totalPermisosGral);

            if (session.getAttribute("alertaLoginMostrada") == null) {
                model.addAttribute("mostrarAlertaModal", (totalVacaciones + totalPermisosGral) > 0);
                session.setAttribute("alertaLoginMostrada", true);
            } else {
                model.addAttribute("mostrarAlertaModal", false);
            }
        } else {
            model.addAttribute("alertaVacacionesCount", 0);
            model.addAttribute("alertaPermisosCount", 0);
            model.addAttribute("mostrarAlertaModal", false);
        }

        // ✨ INYECCIÓN RELOJ TORRE DE CONTROL PARA EL FRONTEND
        ConfiguracionCorteNomina cSind = configuracionCorteNominaRepository.findByTipoEmpleado("SINDICALIZADO").orElse(new ConfiguracionCorteNomina());
        model.addAttribute("diaCorteSind", cSind.getDiaCorte() != null ? cSind.getDiaCorte() : 2);
        model.addAttribute("horaCorteSind", cSind.getHoraCorte() != null ? cSind.getHoraCorte().toString() : "12:00");

        ConfiguracionCorteNomina cAdmin = configuracionCorteNominaRepository.findByTipoEmpleado("ADMINISTRATIVO").orElse(new ConfiguracionCorteNomina());
        model.addAttribute("diaCorteAdmin", cAdmin.getDiaCorte() != null ? cAdmin.getDiaCorte() : 3);
        model.addAttribute("horaCorteAdmin", cAdmin.getHoraCorte() != null ? cAdmin.getHoraCorte().toString() : "12:00");
        model.addAttribute("listaTurnosMaster", turnoRepo.findByActivoTrue());

        return "dashboard";
    }

    @GetMapping("/pantallas/solicitar-vacaciones")
    public String mostrarFormulario(HttpSession session, Model model) {
        Object nominaObj = session.getAttribute("usuarioLogueado");
        if (nominaObj == null) {
            return "redirect:/login";
        }
        Integer nominaEmpleado = Integer.parseInt(nominaObj.toString());

        Empleado empleado = empleadoRepository.findById(nominaEmpleado)
                .orElseThrow(() -> new RuntimeException("Empleado no encontrado"));
        model.addAttribute("empleado", empleado);

        model.addAttribute("jefes", empleadoService.obtenerJefesPorAsignacion(nominaEmpleado));

        // ✨ Redirección de compatibilidad: alimentamos el viejo combo "roles" con el catálogo unificado
        model.addAttribute("roles", turnoRepo.findByActivoTrue());
        model.addAttribute("listaTurnosMaster", turnoRepo.findByActivoTrue());

        // ✨ NUEVO: Ahora mandamos un diccionario con Fecha + Descripción para pintar el calendario
        List<Map<String, String>> fechasFestivas = diasFestivosRepository.findByActivoTrue().stream()
                .map(festivo -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("fecha", festivo.getFecha().toString());
                    map.put("descripcion", festivo.getDescripcion());
                    return map;
                })
                .collect(Collectors.toList());
        model.addAttribute("festivos", fechasFestivas);

        String tipoCorto = (empleado.getTipoEmpleado() != null && empleado.getTipoEmpleado().equals("SINDICALIZADO"))
                ? "SIND" : "ADMIN";

        String horasAnticipacion = configSistemaRepo.findById("HORAS_ANTICIPACION_" + tipoCorto)
                .map(ConfiguracionSistema::getValor).orElse("48");
        boolean bloquearExt = configSistemaRepo.findById("BLOQ_EXTEMPORANEAS_" + tipoCorto)
                .map(c -> Boolean.parseBoolean(c.getValor())).orElse(true);

        model.addAttribute("horasAnticipacion", horasAnticipacion);
        model.addAttribute("bloquearExtemporaneas", bloquearExt);

        // --- INICIO CÁLCULO DE GUÍLLOTINA PARA EL CALENDARIO ---
        ConfiguracionCorteNomina configCorte = configuracionCorteNominaRepository.findByTipoEmpleado(tipoCorto.equals("SIND") ? "SINDICALIZADO" : "ADMINISTRATIVO").orElse(null);
        LocalDate fechaMinimaPermitida = LocalDate.now().with(java.time.DayOfWeek.MONDAY); // Por defecto, el lunes de esta semana

        if (configCorte != null) {
            LocalDateTime ahora = LocalDateTime.now();
            java.time.DayOfWeek diaCorte = java.time.DayOfWeek.of(configCorte.getDiaCorte() != null ? configCorte.getDiaCorte() : 2);
            java.time.LocalTime horaCorte = configCorte.getHoraCorte() != null ? configCorte.getHoraCorte() : java.time.LocalTime.of(16, 0);

            LocalDateTime limiteGuillotina = ahora.with(java.time.DayOfWeek.MONDAY)
                    .plusDays(diaCorte.getValue() - 1)
                    .toLocalDate()
                    .atTime(horaCorte);

            if (ahora.isBefore(limiteGuillotina)) {
                // La nómina sigue viva, el calendario se abre hasta el lunes de la semana pasada
                fechaMinimaPermitida = ahora.with(java.time.DayOfWeek.MONDAY).minusWeeks(1).toLocalDate();
            }
        }
        model.addAttribute("fechaMinimaCaptura", fechaMinimaPermitida.toString());
// --- FIN CÁLCULO ---

        return "solicitar";
    }

    @PostMapping("/pantallas/solicitar-vacaciones")
    public String procesarSolicitud(@ModelAttribute SolicitudVacacionesRequest request,
                                    @RequestParam(value = "nomina", required = false) Integer nomina, Model model) {
        Integer nominaEmpleado = (request.nominaEmpleado() != null) ? request.nominaEmpleado() : ((nomina != null) ? nomina : 10040);

        try {
            vacationsService.crearSolicitud(request);
            model.addAttribute("mensajeExito", "Tu solicitud fue processed correctamente.");
        } catch (RuntimeException e) {
            model.addAttribute("mensajeError", "Error: " + e.getMessage());
        }

        Empleado empleado = empleadoRepository.findById(nominaEmpleado)
                .orElseThrow(() -> new RuntimeException("Empleado no encontrado"));
        model.addAttribute("empleado", empleado);

        model.addAttribute("jefes", empleadoService.obtenerJefesPorAsignacion(nominaEmpleado));

        // ✨ Mantenemos la inyección de compatibilidad para evitar pantallas blancas tras el posteo
        model.addAttribute("roles", turnoRepo.findByActivoTrue());
        model.addAttribute("listaTurnosMaster", turnoRepo.findByActivoTrue());

        // ✨ NUEVO: Ahora mandamos un diccionario con Fecha + Descripción para pintar el calendario
        List<Map<String, String>> fechasFestivas = diasFestivosRepository.findByActivoTrue().stream()
                .map(festivo -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("fecha", festivo.getFecha().toString());
                    map.put("descripcion", festivo.getDescripcion());
                    return map;
                })
                .collect(Collectors.toList());
        model.addAttribute("festivos", fechasFestivas);

        String tipoCorto = (empleado.getTipoEmpleado() != null && empleado.getTipoEmpleado().equals("SINDICALIZADO"))
                ? "SIND" : "ADMIN";

        String horasAnticipacion = configSistemaRepo.findById("HORAS_ANTICIPACION_" + tipoCorto)
                .map(ConfiguracionSistema::getValor).orElse("48");
        boolean bloquearExt = configSistemaRepo.findById("BLOQ_EXTEMPORANEAS_" + tipoCorto)
                .map(c -> Boolean.parseBoolean(c.getValor())).orElse(true);

        model.addAttribute("horasAnticipacion", horasAnticipacion);
        model.addAttribute("bloquearExtemporaneas", bloquearExt);

        // --- INICIO CÁLCULO DE GUÍLLOTINA PARA EL CALENDARIO ---
        ConfiguracionCorteNomina configCorte = configuracionCorteNominaRepository.findByTipoEmpleado(tipoCorto.equals("SIND") ? "SINDICALIZADO" : "ADMINISTRATIVO").orElse(null);
        LocalDate fechaMinimaPermitida = LocalDate.now().with(java.time.DayOfWeek.MONDAY);

        if (configCorte != null) {
            LocalDateTime ahora = LocalDateTime.now();
            java.time.DayOfWeek diaCorte = java.time.DayOfWeek.of(configCorte.getDiaCorte() != null ? configCorte.getDiaCorte() : 2);
            java.time.LocalTime horaCorte = configCorte.getHoraCorte() != null ? configCorte.getHoraCorte() : java.time.LocalTime.of(16, 0);

            LocalDateTime limiteGuillotina = ahora.with(java.time.DayOfWeek.MONDAY)
                    .plusDays(diaCorte.getValue() - 1)
                    .toLocalDate()
                    .atTime(horaCorte);

            if (ahora.isBefore(limiteGuillotina)) {
                fechaMinimaPermitida = ahora.with(java.time.DayOfWeek.MONDAY).minusWeeks(1).toLocalDate();
            }
        }
        model.addAttribute("fechaMinimaCaptura", fechaMinimaPermitida.toString());
        // --- FIN CÁLCULO ---

        return "solicitar";
    }

    @GetMapping("/pantallas/gestion-saldos")
    public String mostrarGestionSaldos(@RequestParam(value = "buscarNomina", required = false) Integer buscarNomina,
                                       HttpSession session,
                                       Model model) {
        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) {
            return "redirect:/login";
        }
        Integer nominaLogueada = Integer.parseInt(logueadoObj.toString());

        if (!vacationsService.esAdminORH(nominaLogueada)) {
            return "redirect:/pantallas/dashboard?error=No+Autorizado";
        }

        model.addAttribute("esDesarrollador", vacationsService.esDesarrolladorDios(nominaLogueada));
        model.addAttribute("nominaLogueada", nominaLogueada);
        model.addAttribute("recuperacionesPendientes", vacationsService.obtenerPendientesDeNomina());

        // ✨ FILTRO ESTRICTO: Solo jala incidencias desde el lunes de la semana pasada (Semana Viva de Nómina)
        LocalDate inicioSemanaViva = LocalDate.now().with(java.time.DayOfWeek.MONDAY).minusWeeks(1);
        model.addAttribute("auditoriaPermisos", permisosService.obtenerPermisosParaAuditoriaRH(inicioSemanaViva));
        model.addAttribute("auditoriaVacaciones", vacationsService.obtenerVacacionesParaAuditoriaRH(inicioSemanaViva));

        model.addAttribute("historialRecuperacionesGlobal", vacationsService.obtenerHistorialRecuperacionesGlobal());
        model.addAttribute("plantillaCompleta", empleadoRepository.findAll());

        if (buscarNomina != null) {
            empleadoRepository.findById(buscarNomina).ifPresent(emp -> {
                model.addAttribute("empleadoEncontrado", emp);
                model.addAttribute("saldoProporcional", emp.getSaldoProporcional());
                model.addAttribute("historialAuditoria", vacationsService.obtenerHistorialAuditoriaSaldos(buscarNomina));

                // ✨ CIRUGÍA: Reemplazamos el historial unificado por la consulta separada
                Map<String, List<SolicitudDTO>> dominiosHistorial =
                        vacationsService.obtenerHistorialSeparadoYFiltrado(buscarNomina, null, null);

                // Mandamos las dos listas independientes a la vista
                model.addAttribute("historialVacacionesIndiv", dominiosHistorial.get("vacaciones"));
                model.addAttribute("historialPermisosIndiv", dominiosHistorial.get("permisos"));

                List<SolicitudVacaciones> aprobadas = solicitudVacacionesRepository
                        // ...
                        .findByEmpleado_NominaOrderByFechaInicioDesc(buscarNomina).stream()
                        .filter(s -> "Aprobado".equalsIgnoreCase(s.getEstatus()))
                        .collect(Collectors.toList());
                model.addAttribute("solicitudesEmpleado", aprobadas);
            });
        }
        // ✨ Pasamos el tabulador de ley de la BD hacia JavaScript
        model.addAttribute("tabuladorLey", tabuladorRepo.findAll(org.springframework.data.domain.Sort.by("anioAntiguedad")));
        // ✨ Inyectamos el bono a la pantalla de Auditoría
        model.addAttribute("bonoAdmin", com.hrms.vacaciones.service.TabuladorCache.getBonoAdministrativo());
        return "gestion-saldos";
    }

    @PostMapping("/pantallas/gestion-saldos/guardar")
    public String actualizarSaldoManual(@RequestParam("empleadoId") Integer empleadoId,
                                        @RequestParam("accionTipo") String accionTipo,
                                        @RequestParam("diasAjuste") BigDecimal diasAjuste,
                                        @RequestParam("justificacion") String justificacion,
                                        @RequestParam(value = "solicitudId", required = false) Integer solicitudId,
                                        @RequestParam(value = "fechaDiscount", required = false) String fechasDescuentoStr,
                                        HttpSession session,
                                        RedirectAttributes redirectAttributes) {
        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaLogueada = Integer.parseInt(logueadoObj.toString());

        try {
            vacationsService.modificarSaldoManual(nominaLogueada, empleadoId, accionTipo, diasAjuste, justificacion, solicitudId, fechasDescuentoStr);
            redirectAttributes.addFlashAttribute("mensajeExito", "El movimiento se procesó correctamente y quedó registrado en Auditoría.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", e.getMessage());
        }

        return "redirect:/pantallas/gestion-saldos?buscarNomina=" + empleadoId;
    }

    @PostMapping("/pantallas/gestion-saldos/resolver-recuperacion")
    public String resolverRecuperacion(@RequestParam("solicitudId") Integer solicitudId,
                                       @RequestParam("aprobado") boolean aprobado,
                                       @RequestParam(value = "comentario", required = false) String comentario, // 🌟 Clavamos el parámetro web
                                       HttpSession session,
                                       RedirectAttributes redirectAttributes) {
        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaLogueada = Integer.parseInt(logueadoObj.toString());

        try {
            // 🚀 Se envía el comentario de rechazo/aprobación a la base de datos
            vacationsService.resolverRecuperacionNomina(solicitudId, aprobado, nominaLogueada, comentario);
            redirectAttributes.addFlashAttribute("mensajeExito", "La solicitud de recuperación #" + solicitudId + " fue " + (aprobado ? "autorizada y aplicada al saldo." : "rechazada correctamente."));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al procesar la solicitud: " + e.getMessage());
        }
        return "redirect:/pantallas/gestion-saldos?tab=recuperaciones";
    }

    @PostMapping("/pantallas/supervisor/solicitar-recuperacion")
    public String supervisorSolicitaRecuperacion(@RequestParam("nominaEmpleado") Integer nominaEmpleado,
                                                 @RequestParam("fechaFalta") String fechaFaltaStr,
                                                 @RequestParam("justificacion") String justificacion,
                                                 HttpSession session,
                                                 Model model) {
        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaSupervisor = Integer.parseInt(logueadoObj.toString());

        try {
            LocalDate fechaFalta = LocalDate.parse(fechaFaltaStr);
            vacationsService.crearSolicitudRecuperacion(nominaSupervisor, nominaEmpleado, fechaFalta, justificacion);
            model.addAttribute("mensajeExito", "La solicitud de recuperación fue enviada a Nóminas para su validación.");
        } catch (Exception e) {
            model.addAttribute("mensajeError", e.getMessage());
        }
        return "redirect:/pantallas/dashboard";
    }

    @GetMapping("/pantallas/admin/forzar-aniversarios")
    public String forzarSincronizacionAniversarios(
            @RequestParam("fechaInicio") String fechaInicioStr,
            @RequestParam("fechaFin") String fechaFinStr,
            RedirectAttributes redirectAttributes) {
        try {
            LocalDate inicio = LocalDate.parse(fechaInicioStr);
            LocalDate fin = LocalDate.parse(fechaFinStr);

            vacationsService.sincronizarAniversariosMasivos(inicio, fin);
            redirectAttributes.addFlashAttribute("mensajeExito", "¡Súper Barredora ejecutada! Se escanearon y abonaron todos los aniversarios en el rango: " + inicio + " al " + fin);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al procesar la Súper Barredora: " + e.getMessage());
        }
        return "redirect:/pantallas/gestion-saldos";
    }

    @PostMapping("/pantallas/gestion-saldos/resolver-recuperacion-masiva")
    public String resolverRecuperacionMasiva(
            @RequestParam(value = "ids", required = false) List<Integer> ids,
            @RequestParam("status") String status,
            @RequestParam(value = "comentario", required = false) String comentario,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaLogueada = Integer.parseInt(logueadoObj.toString());

        if (!vacationsService.esAdminORH(nominaLogueada)) {
            return "redirect:/pantallas/dashboard?error=No+Autorizado";
        }

        if (ids != null && !ids.isEmpty()) {
            boolean esAprobado = "APROBADO".equalsIgnoreCase(status);
            int procesadosExitosamente = 0;

            for (Integer id : ids) {
                try {
                    // 🌟 Se corrige la llamada inyectando la variable "comentario"
                    vacationsService.resolverRecuperacionNomina(id, esAprobado, nominaLogueada, comentario);
                    procesadosExitosamente++;
                } catch (Exception e) {
                }
            }
            redirectAttributes.addFlashAttribute("mensajeExito", "Procesamiento en lote terminado. Se aplicaron " + procesadosExitosamente + " solicitudes de recuperación.");
        } else {
            redirectAttributes.addFlashAttribute("mensajeError", "No se seleccionó ninguna tarjeta de incidencia.");
        }

        return "redirect:/pantallas/gestion-saldos?tab=recuperaciones";
    }

    @PostMapping("/pantallas/gestion-saldos/retener-txt")
    public String retenerTxtNomina(@RequestParam("solicitudId") Long solicitudId, HttpSession session, RedirectAttributes redirectAttributes) {
        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        try {
            permisosService.retenerIncidenciaTxt(solicitudId); // ✨ Ahora apunta a Permisos
            redirectAttributes.addFlashAttribute("mensajeExito", "El formato fue retenido para el próximo corte.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al retener: " + e.getMessage());
        }
        return "redirect:/pantallas/gestion-saldos?tab=auditoria";
    }

    @PostMapping("/pantallas/gestion-saldos/cerrar-nomina")
    public String cerrarNomina(HttpSession session, RedirectAttributes redirectAttributes) {
        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        try {
            int movidos = permisosService.cerrarNominaLimpiarBandeja(); // ✨ Ahora apunta a Permisos
            redirectAttributes.addFlashAttribute("mensajeExito", "Corte de nómina exitoso. " + movidos + " incidencias procesadas enviadas al historial.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al cerrar nómina: " + e.getMessage());
        }
        return "redirect:/pantallas/gestion-saldos?tab=auditoria";
    }

    @PostMapping("/pantallas/gestion-saldos/resolver-auditoria-masiva")
    public String resolverAuditoriaMasiva(
            @RequestParam(value = "ids", required = false) List<String> ids, // Cambia a String para recibir el Prefijo
            @RequestParam("status") String status,
            @RequestParam(value = "comentario", required = false) String comentario,
            HttpSession session, RedirectAttributes redirectAttributes) {

        if (ids != null && !ids.isEmpty()) {
            int procesados = 0;
            for (String token : ids) {
                try {
                    // El front ahora manda 'PERM-5' o 'VAC-10'. Así sabemos a qué tabla pegarle y si debemos regresar saldo.
                    if (token.startsWith("PERM-")) {
                        Long id = Long.parseLong(token.substring(5));
                        permisosService.rechazarPermisoAuditoria(id, comentario);
                    } else if (token.startsWith("VAC-")) {
                        Integer id = Integer.parseInt(token.substring(4));
                        vacationsService.rechazarVacacionAuditoria(id, comentario);
                    }
                    procesados++;
                } catch (Exception e) {
                    System.out.println("Error procesando: " + token);
                }
            }
            redirectAttributes.addFlashAttribute("mensajeExito", procesados + " incidencias fueron rechazadas (Las vacaciones canceladas reintegraron su saldo).");
        }
        return "redirect:/pantallas/gestion-saldos?tab=auditoria";
    }

    @PostMapping("/pantallas/gestion-saldos/actualizar-perfil")
    public String actualizarPerfilManual(@RequestParam("empleadoId") Integer empleadoId,
                                         @RequestParam(value = "jefeNomina", required = false) String jefeNomina,
                                         @RequestParam("tipoEmpleado") String tipoEmpleado,
                                         @RequestParam(value = "diasBaseManual", required = false) String diasBaseManual,
                                         @RequestParam(value = "esSaldoCongelado", required = false) Boolean esSaldoCongelado,
                                         RedirectAttributes redirectAttributes) {
        try {
            Empleado emp = empleadoRepository.findById(empleadoId)
                    .orElseThrow(() -> new RuntimeException("Empleado no encontrado"));

            if (jefeNomina != null && !jefeNomina.trim().isEmpty()) {
                emp.setJefeDirectoNomina(Integer.parseInt(jefeNomina.trim()));
            } else {
                emp.setJefeDirectoNomina(null);
            }

            emp.setTipoEmpleado(tipoEmpleado);

            if ("EXTRANJERO".equalsIgnoreCase(tipoEmpleado)) {
                if (diasBaseManual != null && !diasBaseManual.trim().isEmpty()) {
                    emp.setDiasBaseManual(Integer.parseInt(diasBaseManual.trim()));
                } else {
                    emp.setDiasBaseManual(12);
                }
                emp.setEsSaldoCongelado(esSaldoCongelado != null && esSaldoCongelado);
            } else {
                emp.setDiasBaseManual(null);
                emp.setEsSaldoCongelado(false);
            }

            empleadoRepository.save(emp);
            redirectAttributes.addFlashAttribute("mensajeExito", "Ficha del colaborador #" + empleadoId + " actualizada correctamente.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al actualizar la ficha: " + e.getMessage());
        }

        return "redirect:/pantallas/gestion-saldos?buscarNomina=" + empleadoId;
    }

    @GetMapping("/pantallas/gestion-saldos/exportar-prenomina")
    public void exportarPrenominaCsv(
            @RequestParam(value = "semana", required = false) Integer semana,
            @RequestParam(value = "anio", required = false) Integer anio,
            @RequestParam(value = "fechaInicio", required = false) String fechaInicioStr,
            @RequestParam(value = "fechaFin", required = false) String fechaFinStr,
            HttpServletResponse response) throws IOException {

        response.setContentType("text/csv;charset=UTF-8");
        LocalDate dateStart = null;
        LocalDate dateEnd = null;
        String semanaLabel = "N/A";

        WeekFields weekFields = WeekFields.ISO;

        if (semana != null) {
            int targetAnio = (anio != null) ? anio : LocalDate.now().getYear();
            semanaLabel = String.valueOf(semana);
            dateStart = LocalDate.of(targetAnio, 1, 4)
                    .with(weekFields.weekOfWeekBasedYear(), semana)
                    .with(java.time.DayOfWeek.MONDAY);
            dateEnd = dateStart.plusDays(6);
        } else if (fechaInicioStr != null && !fechaInicioStr.trim().isEmpty() && fechaFinStr != null && !fechaFinStr.trim().isEmpty()) {
            dateStart = LocalDate.parse(fechaInicioStr);
            dateEnd = LocalDate.parse(fechaFinStr);
            semanaLabel = String.valueOf(dateStart.get(weekFields.weekOfWeekBasedYear()));
        } else {
            LocalDate hoy = LocalDate.now();
            semanaLabel = String.valueOf(hoy.get(weekFields.weekOfWeekBasedYear()));
            dateStart = hoy.with(java.time.DayOfWeek.MONDAY);
            dateEnd = dateStart.plusDays(6);
        }

        response.setHeader("Content-Disposition", "attachment; filename=Prenomina_Semana_" + semanaLabel + ".csv");

        List<LocalDate> listaDias = new ArrayList<>();
        LocalDate cursor = dateStart;
        while (!cursor.isAfter(dateEnd)) {
            listaDias.add(cursor);
            cursor = cursor.plusDays(1);
        }

        List<Empleado> todosEmpleados = empleadoRepository.findAll();
        // ✨ INYECCIÓN: Ordenar la plantilla completa por Número de Nómina de Menor a Mayor
        todosEmpleados.sort(java.util.Comparator.comparing(Empleado::getNomina));

        final LocalDate dStart = dateStart;
        final LocalDate dEnd = dateEnd;

        List<SolicitudVacaciones> vacacionesRange = solicitudVacacionesRepository.findAll().stream()
                .filter(s -> "Aprobado".equalsIgnoreCase(s.getEstatus()))
                .filter(s -> s.getFechaInicio() != null && s.getFechaFin() != null)
                .filter(s -> !(s.getFechaFin().isBefore(dStart) || s.getFechaInicio().isAfter(dEnd)))
                .collect(Collectors.toList());

        List<SolicitudPermiso> permisosRange = solicitudPermisoRepository.findAll().stream()
                .filter(p -> "APROBADO".equalsIgnoreCase(p.getEstatus()))
                .filter(p -> p.getFechaIncidencia() != null)
                .filter(p -> !p.getFechaIncidencia().isBefore(dStart) && !p.getFechaIncidencia().isAfter(dEnd))
                .collect(Collectors.toList());

        PrintWriter writer = response.getWriter();
        writer.write("\uFEFF");

        // ✨ INICIO DEL REEMPLAZO

        // Se agregan las comas necesarias para cubrir las nuevas columnas (Turno y Fechas TXT)
        writer.println("Semana," + semanaLabel + ",,,,,,,,,,,");

        StringBuilder headerBuilder = new StringBuilder("Nomina,Nombre completo,Turno");
        DateTimeFormatter headerFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        for (LocalDate d : listaDias) {
            headerBuilder.append(",").append(d.getDayOfMonth());
        }
        headerBuilder.append(",Comentarios,Fechas TXT,Quien Autoriza");
        writer.println(headerBuilder.toString());

        for (Empleado emp : todosEmpleados) {
            List<SolicitudVacaciones> vacsEmp = vacacionesRange.stream()
                    .filter(v -> v.getEmpleado().getNomina().equals(emp.getNomina())).collect(Collectors.toList());
            List<SolicitudPermiso> permsEmp = permisosRange.stream()
                    .filter(p -> p.getEmpleado().getNomina().equals(emp.getNomina())).collect(Collectors.toList());

            if (vacsEmp.isEmpty() && permsEmp.isEmpty()) {
                continue;
            }

            // 1️⃣ Extracción del Turno y Días de Descanso oficiales desde la SOLICITUD (Histórico)
            String turnoNombre = "General";
            String diasDescansoStr = "DOMINGO";

            if (!vacsEmp.isEmpty() && vacsEmp.get(0).getTurno() != null) {
                turnoNombre = vacsEmp.get(0).getTurno().getNombreTurno();
                diasDescansoStr = vacsEmp.get(0).getTurno().getDiasDescanso() != null ? vacsEmp.get(0).getTurno().getDiasDescanso().toUpperCase() : "DOMINGO";
            } else if (!permsEmp.isEmpty() && permsEmp.get(0).getTurno() != null) {
                turnoNombre = permsEmp.get(0).getTurno().getNombreTurno();
                diasDescansoStr = permsEmp.get(0).getTurno().getDiasDescanso() != null ? permsEmp.get(0).getTurno().getDiasDescanso().toUpperCase() : "DOMINGO";
            } else if (emp.getTurno() != null) {
                // Fallback por si la solicitud es muy vieja y no tiene turno mapeado
                turnoNombre = emp.getTurno().getNombreTurno();
                diasDescansoStr = emp.getTurno().getDiasDescanso() != null ? emp.getTurno().getDiasDescanso().toUpperCase() : "DOMINGO";
            }

            StringBuilder fila = new StringBuilder();
            fila.append(emp.getNomina()).append(",\"").append(emp.getNombreCompleto()).append("\",\"").append(turnoNombre).append("\"");

            List<String> comentariosLista = new ArrayList<>();
            List<String> autorizadoresLista = new ArrayList<>();
            List<String> fechasTxtLista = new ArrayList<>();

            for (LocalDate d : listaDias) {
                String diaSemanaEs = "";
                switch (d.getDayOfWeek()) {
                    case MONDAY: diaSemanaEs = "LUNES"; break;
                    case TUESDAY: diaSemanaEs = "MARTES"; break;
                    case WEDNESDAY: diaSemanaEs = "MIERCOLES"; break;
                    case THURSDAY: diaSemanaEs = "JUEVES"; break;
                    case FRIDAY: diaSemanaEs = "VIERNES"; break;
                    case SATURDAY: diaSemanaEs = "SABADO"; break;
                    case SUNDAY: diaSemanaEs = "DOMINGO"; break;
                }

                boolean esDescanso = diasDescansoStr.contains(diaSemanaEs);
                String tokenDia = esDescanso ? "D" : "";

                for (SolicitudVacaciones v : vacsEmp) {
                    if (!d.isBefore(v.getFechaInicio()) && !d.isAfter(v.getFechaFin())) {
                        String type = v.getTipoSolicitud() != null ? v.getTipoSolicitud() : "";
                        String comment = v.getComentarioJefe() != null ? v.getComentarioJefe().trim() : "";

                        String incidencia = "V";
                        if (comment.contains("[RETENIDO]")) incidencia = "F"; // ✨ REGLA DE ORO: Si está retenido, imprime F directo.
                        else if (comment.contains("[TXT]") || type.toUpperCase().contains("TXT")) incidencia = "TXT";
                        else if (comment.contains("[HO]") || type.toUpperCase().contains("HO")) incidencia = "HO";
                        else if (comment.contains("[C]") || type.toUpperCase().contains("CLIENTE")) incidencia = "C";
                        else if (comment.contains("[P]") || type.toUpperCase().contains("PROVEEDOR")) incidencia = "P";
                        else if (comment.contains("[TET]") || type.toUpperCase().contains("TETLA")) incidencia = "TET";

                        // 2️⃣ Bloqueo Fatal de Nómina
                        if (esDescanso && "V".equals(incidencia)) {
                            tokenDia = "D";
                        } else {
                            tokenDia = incidencia;
                        }

                        // 3️⃣ Limpieza de "Quien Autoriza" (Adiós al falso RH)
                        String jefeNom = v.getJefeAutorizadorNomina() != null ? String.valueOf(v.getJefeAutorizadorNomina()) + " " : "";
                        String fullName = v.getAprobadoPor() != null ? v.getAprobadoPor().getNombreCompleto() : "Sistema";
                        String primerNombre = fullName.split(" ")[0];
                        if (primerNombre.length() > 1) {
                            primerNombre = primerNombre.substring(0, 1).toUpperCase() + primerNombre.substring(1).toLowerCase();
                        }

                        String labelAut = "V".equals(incidencia) ? "V" : incidencia;
                        String authStr = labelAut + ": " + jefeNom + primerNombre;
                        if (!autorizadoresLista.contains(authStr)) autorizadoresLista.add(authStr);

                        // 4️⃣ Auditoría Forense y Limpieza
                        boolean esAutoAprobado = (v.getAprobadoPor() == null) || (comment.toUpperCase().contains("SISTEMA"));

                        if (esAutoAprobado) {
                            String shiftStr = "N/A";
                            String superStr = "N/A";
                            if (emp.getJefeDirecto() != null) {
                                shiftStr = String.valueOf(emp.getJefeDirecto().getNomina());
                                if (emp.getJefeDirecto().getJefeDirecto() != null) {
                                    superStr = String.valueOf(emp.getJefeDirecto().getJefeDirecto().getNomina());
                                }
                            } else if (emp.getJefeDirectoNomina() != null) {
                                shiftStr = String.valueOf(emp.getJefeDirectoNomina());
                            }
                            String msgSys = "Autorizado por sistema, sin atención por " + shiftStr + " y " + superStr;
                            if (!comentariosLista.contains(msgSys)) comentariosLista.add(msgSys);
                        } else if (!comment.isEmpty() && !comment.startsWith("[")) {
                            if (comment.contains("las fechas de pago del TXT:")) {
                                String fTxt = comment.substring(comment.indexOf("las fechas de pago del TXT:") + 27).trim();
                                if (!fechasTxtLista.contains(fTxt)) fechasTxtLista.add(fTxt);
                            } else {
                                if (!comentariosLista.contains(comment)) comentariosLista.add(comment);
                            }
                        }
                    }
                }

                for (SolicitudPermiso p : permsEmp) {
                    if (p.getFechaIncidencia() != null && p.getFechaIncidencia().equals(d)) {
                        String codigo = p.getTipoPermiso() != null ? p.getTipoPermiso().getCodigo() : "TXT";
                        tokenDia = codigo;

                        String comment = p.getJustificacionSupervisor() != null ? p.getJustificacionSupervisor().trim() : "";

                        // 1. Prioridad: ¿Quién resolvió la incidencia manualmente?
                        // 2. Si no, ¿Quién es el jefe directo?
                        // 3. Fallback: "Sistema"
                        String jefeNom = "";
                        String fullName = "Sistema";

                        if (p.getResueltoPor() != null) {
                            jefeNom = String.valueOf(p.getResueltoPor().getNomina()) + " ";
                            fullName = p.getResueltoPor().getNombreCompleto();
                        } else if (p.getEmpleado().getJefeDirectoNomina() != null) {
                            jefeNom = String.valueOf(p.getEmpleado().getJefeDirectoNomina()) + " ";
                        }

                        // Limpieza de nombre (Solo el primer nombre capitalizado)
                        String primerNombre = fullName.split(" ")[0];
                        if (primerNombre.length() > 1 && !fullName.equals("Sistema")) {
                            primerNombre = primerNombre.substring(0, 1).toUpperCase() + primerNombre.substring(1).toLowerCase();
                        }

                        // Si fullName es "Sistema", no intentamos capitalizar "Sistema" como nombre
                        String displayNombre = fullName.equals("Sistema") ? "" : primerNombre;

                        String authStr = codigo + ": " + jefeNom + displayNombre;
                        if (!autorizadoresLista.contains(authStr)) autorizadoresLista.add(authStr);

                        if ("TXT".equalsIgnoreCase(codigo) && p.getDesglosesPago() != null && !p.getDesglosesPago().isEmpty()) {
                            String desgloseTxt = p.getDesglosesPago().stream()
                                    .map(pago -> pago.getFechaPago().format(headerFormat) + " (" + pago.getHorasPago() + "h)")
                                    .collect(Collectors.joining(", "));
                            if (!fechasTxtLista.contains(desgloseTxt)) fechasTxtLista.add(desgloseTxt);
                        }

                        if (!comment.isEmpty() && !comment.startsWith("[") && !comentariosLista.contains(comment)) {
                            comentariosLista.add(comment);
                        }
                    }
                }

                fila.append(",").append(tokenDia);
            }

            // Compilado Final de Columnas Limpias
            String comentariosFinal = comentariosLista.isEmpty() ? "" : String.join(" | ", comentariosLista);
            String fechasTxtFinal = fechasTxtLista.isEmpty() ? "" : String.join(" | ", fechasTxtLista);
            String autorizaFinal = autorizadoresLista.isEmpty() ? "" : String.join(", ", autorizadoresLista);

            fila.append(",\"").append(comentariosFinal.replace("\"", "\"\"")).append("\"");
            fila.append(",\"").append(fechasTxtFinal.replace("\"", "\"\"")).append("\"");
            fila.append(",\"").append(autorizaFinal.replace("\"", "\"\"")).append("\"");

            writer.println(fila.toString());
        }

        // ✨ FIN DEL REEMPLAZO
        writer.flush();
        writer.close();
    }

    @GetMapping("/pantallas/gestion-saldos/exportar-recuperaciones")
    public void exportarRecuperacionesCsv(
            @RequestParam(value = "semana", required = false) Integer semana,
            @RequestParam(value = "anio", required = false) Integer anio,
            @RequestParam(value = "fechaInicio", required = false) String fechaInicioStr,
            @RequestParam(value = "fechaFin", required = false) String fechaFinStr,
            HttpServletResponse response) throws IOException {

        response.setContentType("text/csv;charset=UTF-8");
        LocalDate dateStart = null;
        LocalDate dateEnd = null;
        String semanaLabel = "N/A";

        WeekFields weekFields = WeekFields.ISO;

        if (semana != null) {
            int targetAnio = (anio != null) ? anio : LocalDate.now().getYear();
            semanaLabel = String.valueOf(semana);
            dateStart = LocalDate.of(targetAnio, 1, 4).with(weekFields.weekOfWeekBasedYear(), semana).with(java.time.DayOfWeek.MONDAY);
            dateEnd = dateStart.plusDays(6);
        } else if (fechaInicioStr != null && !fechaInicioStr.trim().isEmpty() && fechaFinStr != null && !fechaFinStr.trim().isEmpty()) {
            dateStart = LocalDate.parse(fechaInicioStr);
            dateEnd = LocalDate.parse(fechaFinStr);
            semanaLabel = String.valueOf(dateStart.get(weekFields.weekOfWeekBasedYear()));
        } else {
            LocalDate hoy = LocalDate.now();
            semanaLabel = String.valueOf(hoy.get(weekFields.weekOfWeekBasedYear()));
            dateStart = hoy.with(java.time.DayOfWeek.MONDAY);
            dateEnd = dateStart.plusDays(6);
        }

        response.setHeader("Content-Disposition", "attachment; filename=Recuperaciones_Semana_" + semanaLabel + ".csv");

        final LocalDate dStart = dateStart;
        final LocalDate dEnd = dateEnd;

        List<SolicitudVacaciones> recuperacionesOrdinarias = solicitudVacacionesRepository.findAll().stream()
                .filter(s -> "Recuperación de Falta".equalsIgnoreCase(s.getTipoSolicitud()))
                .filter(s -> List.of("Aprobado", "Aprobada").contains(s.getEstatus()))
                .filter(s -> s.getFechaInicio() != null && !s.getFechaInicio().isBefore(dStart) && !s.getFechaInicio().isAfter(dEnd))
                .collect(Collectors.toList());

        List<SolicitudPermiso> permisosAprobados = solicitudPermisoRepository.findAll().stream()
                .filter(p -> "APROBADO".equalsIgnoreCase(p.getEstatus()))
                .filter(p -> p.getFechaIncidencia() != null && !p.getFechaIncidencia().isBefore(dStart) && !p.getFechaIncidencia().isAfter(dEnd))
                .collect(Collectors.toList());

        PrintWriter writer = response.getWriter();
        writer.write("\uFEFF");

        writer.println("Semana," + semanaLabel + ",,,,");
        writer.println("Nomina,Nombre completo,Incidencia a aplicar,Comentario,Fecha de autorizacion,Quien Autoriza");

        DateTimeFormatter formatMx = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        for (SolicitudVacaciones rec : recuperacionesOrdinarias) {
            String autorizador = rec.getAprobadoPor() != null ? rec.getAprobadoPor().getNombreCompleto() : "Nóminas/RH";
            String fechaAut = rec.getFechaAprobacionJefe() != null ? rec.getFechaAprobacionJefe().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) : "N/A";
            String jefeNom = rec.getJefeAutorizadorNomina() != null ? String.valueOf(rec.getJefeAutorizadorNomina()) : "RH";

            writer.println(rec.getEmpleado().getNomina() + ",\"" +
                    rec.getEmpleado().getNombreCompleto() + "\",\"A Cuenta Vacaciones\",\"" +
                    (rec.getComentarioJefe() != null ? rec.getComentarioJefe().trim() : "Sin observaciones") + "\",\"" +
                    fechaAut + "\",\"" + jefeNom + " " + autorizador + "\"");
        }

        for (SolicitudPermiso perm : permisosAprobados) {
            String codigo = perm.getTipoPermiso() != null ? perm.getTipoPermiso().getCodigo() : "TXT";
            String comentariosTxt = perm.getJustificacionSupervisor() != null ? perm.getJustificacionSupervisor().trim() : "";

            if ("TXT".equalsIgnoreCase(codigo) && perm.getDesglosesPago() != null && !perm.getDesglosesPago().isEmpty()) {
                String desgloses = perm.getDesglosesPago().stream()
                        .map(pago -> pago.getFechaPago().format(formatMx))
                        .collect(Collectors.joining(" y "));
                comentariosTxt = "las fechas de pago del TXT: " + desgloses;
            }

            String fechaAut = perm.getFechaSolicitud() != null ? perm.getFechaSolicitud().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) : "N/A";
            String jefeNom = perm.getEmpleado().getJefeDirectoNomina() != null ? String.valueOf(perm.getEmpleado().getJefeDirectoNomina()) : "Supervisor";

            writer.println(perm.getEmpleado().getNomina() + ",\"" +
                    perm.getEmpleado().getNombreCompleto() + "\",\"" + codigo + "\",\"" +
                    comentariosTxt.replace("\"", "\"\"") + "\",\"" +
                    fechaAut + "\",\"" + jefeNom + " Autorizado por Supervisor de Área\"");
        }

        // 🎯 CORRECCIÓN QUIRÚRGICA: Cambiamos la variable fantasma SheriffAudit por el stream oficial writer
        writer.flush();
        writer.close();
    }

    @PostMapping("/pantallas/gestion-saldos/depurar-vencido")
    public String depurarSaldoVencidoManual(@RequestParam("empleadoId") Integer empleadoId,
                                            @RequestParam("justificacionDepurar") String justificacion,
                                            HttpSession session,
                                            RedirectAttributes redirectAttributes) {
        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer userName = Integer.parseInt(logueadoObj.toString());

        try {
            vacationsService.depurarSaldoVencidoManual(userName, empleadoId, justificacion);
            redirectAttributes.addFlashAttribute("mensajeExito", "El saldo de ciclos anteriores fue depurado con éxito. Se notificó al colaborador en su Dashboard.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al procesar la depuración: " + e.getMessage());
        }

        return "redirect:/pantallas/gestion-saldos?tab=depuracion";
    }

    @GetMapping("/pantallas/gestion-saldos/exportar-saldos-vencidos")
    public void exportarSaldosVencidosCsv(HttpSession session, HttpServletResponse response) throws IOException {
        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) {
            response.sendRedirect("/login");
            return;
        }

        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=Roster_Saldos_Vencidos_Ciclos_Anteriores.csv");

        List<Empleado> todosEmpleados = empleadoRepository.findAll();
        PrintWriter writer = response.getWriter();
        writer.write("\uFEFF");

        writer.println("Nomina,Nombre Completo,Regimen,CC,Nombre CC,WC,Nombre WC,Fecha Ingreso,Saldo de Ciclos Anteriores (Vencido),Saldo Periodo Actual,Saldo Proporcional,Fecha Limite Gracia");

        DateTimeFormatter formatMx = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        for (Empleado emp : todosEmpleados) {
            if (emp.getEstatus() == null || "ACTIVO".equalsIgnoreCase(emp.getEstatus())) {
                String fIngreso = emp.getFechaIngreso() != null ? emp.getFechaIngreso().format(formatMx) : "N/A";
                String fLimite = emp.getFechaLimiteExpiracion() != null ? emp.getFechaLimiteExpiracion().format(formatMx) : "N/A";

                writer.println(emp.getNomina() + ",\"" +
                        emp.getNombreCompleto() + "\",\"" +
                        emp.getTipoEmpleado() + "\"," +
                        (emp.getCentroCosto() != null ? emp.getCentroCosto().getId() : "N/A") + ",\"" +
                        (emp.getCentroCosto() != null ? emp.getCentroCosto().getNombre() : "N/A") + "\"," +
                        (emp.getWorkCenter() != null ? emp.getWorkCenter().getId() : "N/A") + ",\"" +
                        (emp.getWorkCenter() != null ? emp.getWorkCenter().getNombre() : "N/A") + "\",\"" +
                        fIngreso + "\"," +
                        emp.getSaldoCiclosAnteriores() + "," +
                        emp.getSaldoPeriodoActual() + "," +
                        (emp.getSaldoProporcional() != null ? emp.getSaldoProporcional() : "0.00") + ",\"" +
                        fLimite + "\"");
            }
        }
        writer.flush();
        writer.close();
    }

    @PostMapping("/pantallas/solicitudes/cancelar-vacacion-api")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> cancelarVacacionApi(@RequestParam("idSolicitud") Integer idSolicitud, HttpSession session) {
        Object nominaObj = session.getAttribute("usuarioLogueado");
        if (nominaObj == null) return org.springframework.http.ResponseEntity.status(401).body(Map.of("error", "Sesión expirada"));

        try {
            vacationsService.cancelarSolicitudPorEmpleado(idSolicitud, Integer.parseInt(nominaObj.toString()));
            return org.springframework.http.ResponseEntity.ok(Map.of("mensaje", "Vacación cancelada correctamente."));
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/pantallas/solicitudes/cancelar-permiso-api")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> cancelarPermisoApi(@RequestParam("idSolicitud") Long idSolicitud, HttpSession session) {
        Object nominaObj = session.getAttribute("usuarioLogueado");
        if (nominaObj == null) return org.springframework.http.ResponseEntity.status(401).body(Map.of("error", "Sesión expirada"));

        try {
            permisosService.cancelarPermisoPorEmpleado(idSolicitud, Integer.parseInt(nominaObj.toString()));
            return org.springframework.http.ResponseEntity.ok(Map.of("mensaje", "Permiso cancelado correctamente."));
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // 🟢 API GET: Extrae los datos actuales del permiso para llenar el modal
    @GetMapping("/pantallas/solicitudes/api/permiso/{id}")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> obtenerDetallePermisoApi(@PathVariable Long id, HttpSession session) {
        Object nominaObj = session.getAttribute("usuarioLogueado");
        if (nominaObj == null) return org.springframework.http.ResponseEntity.status(401).body(Map.of("error", "Sesión expirada"));

        try {
            SolicitudPermiso permiso = solicitudPermisoRepository.findById(id.intValue())
                    .orElseThrow(() -> new RuntimeException("Permiso no encontrado."));

            Map<String, Object> data = new HashMap<>();
            data.put("id", permiso.getId());
            data.put("codigoPermiso", permiso.getTipoPermiso() != null ? permiso.getTipoPermiso().getCodigo() : "N/A");
            data.put("fechaIncidencia", permiso.getFechaIncidencia() != null ? permiso.getFechaIncidencia().toString() : "");
            data.put("turno", permiso.getTurno() != null ? permiso.getTurno().getNombreTurno() : "");
            data.put("justificacion", permiso.getJustificacionSupervisor() != null ? permiso.getJustificacionSupervisor() : "");
            data.put("esPorHoras", permiso.getEsPorHoras());
            data.put("horasPermiso", permiso.getHorasPermiso());

            // Si es TXT, mandamos sus pagos
            List<Map<String, Object>> pagos = new ArrayList<>();
            if (permiso.getDesglosesPago() != null) {
                for (SolicitudTxtPago p : permiso.getDesglosesPago()) {
                    Map<String, Object> pd = new HashMap<>();
                    pd.put("fecha", p.getFechaPago().toString());
                    pd.put("horas", p.getHorasPago());
                    pagos.add(pd);
                }
            }
            data.put("desglosesPago", pagos);

            return org.springframework.http.ResponseEntity.ok(data);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // 🟢 API POST: Recibe los datos nuevos y los guarda
    @PostMapping("/pantallas/solicitudes/editar-permiso-api/{id}")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> guardarEdicionPermisoApi(@PathVariable Long id, @RequestBody com.hrms.vacaciones.dto.SolicitudPermisoRequestDTO request, HttpSession session) {
        Object nominaObj = session.getAttribute("usuarioLogueado");
        if (nominaObj == null) return org.springframework.http.ResponseEntity.status(401).body(Map.of("error", "Sesión expirada"));

        try {
            permisosService.actualizarSolicitudPermiso(id, request, Integer.parseInt(nominaObj.toString()));
            return org.springframework.http.ResponseEntity.ok(Map.of("mensaje", "Solicitud actualizada correctamente."));
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ✨ NUEVO ENDPOINT: Alimentador del Calendario de Visibilidad Total (Auditoría RH)
    @GetMapping("/pantallas/gestion-saldos/api/calendario-planta")
    @ResponseBody
    public List<Map<String, Object>> obtenerEventosPlantaAuditoria(
            @RequestParam(value = "esquema", defaultValue = "TODOS") String esquema) {
        return vacationsService.obtenerEventosCalendarioPlanta(esquema);
    }

    // =========================================================================
    // 📊 MÓDULO DE PRENÓMINA Y RELOJ CHECADOR
    // =========================================================================
    @GetMapping("/pantallas/prenomina")
    public org.springframework.web.servlet.ModelAndView mostrarPrenomina(jakarta.servlet.http.HttpSession session) {
        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) {
            return new org.springframework.web.servlet.ModelAndView("redirect:/login");
        }

        Integer miNomina = Integer.parseInt(logueadoObj.toString());
        org.springframework.web.servlet.ModelAndView mav = new org.springframework.web.servlet.ModelAndView("prenomina");

        // 1. Evaluar si es Jefe Operativo
        com.hrms.vacaciones.model.Empleado empleado = empleadoRepository.findById(miNomina).orElse(null);
        boolean esJefe = false;
        if (empleado != null && empleado.getRolJerarquico() != null) {
            String rol = empleado.getRolJerarquico().toUpperCase();
            esJefe = rol.contains("SUPERVISOR") || rol.contains("SHIFT") || rol.contains("GERENTE");
        }

        // 2. Evaluar si es Nómina
        boolean esNomina = vacationsService.esAdminORH(miNomina);

        // ✨ 3. NUEVO: Evaluar si es Visor Global (Secretarias, Auditores)
        com.hrms.vacaciones.model.ConfiguracionRolesNomina visorPre = rolesNominaRepo.findById("VISOR_PRENOMINA").orElse(null);
        com.hrms.vacaciones.model.ConfiguracionRolesNomina visorBio = rolesNominaRepo.findById("VISOR_BIOMETRICO").orElse(null);
        com.hrms.vacaciones.model.ConfiguracionRolesNomina visorMet = rolesNominaRepo.findById("VISOR_METRICAS").orElse(null);

        boolean esVisorPrenomina = (visorPre != null && miNomina.equals(visorPre.getNumNominaAsignada()));
        boolean esVisorBiometrico = (visorBio != null && miNomina.equals(visorBio.getNumNominaAsignada()));
        boolean esVisorMetricas = (visorMet != null && miNomina.equals(visorMet.getNumNominaAsignada()));

        // 4. Pasar variables al frontend
        mav.addObject("esJefe", esJefe);
        mav.addObject("esNomina", esNomina);
        mav.addObject("esVisorPrenomina", esVisorPrenomina);
        mav.addObject("esVisorBiometrico", esVisorBiometrico);
        mav.addObject("esVisorMetricas", esVisorMetricas);

        String rolUsuario = session.getAttribute("rolUsuario") != null ? session.getAttribute("rolUsuario").toString() : "USUARIO";
        mav.addObject("rolUsuario", rolUsuario);

        return mav;
    }

    // =========================================================================
    // 🛡️ IMPORTADOR DE PRENÓMINA AUTORIZADA
    // =========================================================================
    @Autowired
    private com.hrms.vacaciones.service.PrenominaAutorizadaImportService importServiceAutorizada;

    @PostMapping("/prenomina/importar-autorizada")
    public String importarAutorizada(@RequestParam("archivoExcelPulido") org.springframework.web.multipart.MultipartFile file, org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        try {
            importServiceAutorizada.importarPrenominaPulida(file);
            redirectAttributes.addFlashAttribute("mensajeExito", "Prenómina pulida subida correctamente. Los ajustes manuales han sido guardados.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al cargar la prenómina pulida: " + e.getMessage());
        }
        return "redirect:/pantallas/prenomina";
    }
}

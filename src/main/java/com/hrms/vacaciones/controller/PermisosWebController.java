package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.model.SolicitudPermiso;
import com.hrms.vacaciones.model.SolicitudVacaciones;
import com.hrms.vacaciones.repository.EmpleadoRepository;
import com.hrms.vacaciones.repository.TurnoRepository;
import com.hrms.vacaciones.repository.SolicitudVacacionesRepository;
import com.hrms.vacaciones.repository.MotivoRechazoRepository;
import com.hrms.vacaciones.service.PermisosService;
import com.hrms.vacaciones.service.VacacionesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletResponse;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpSession;

@Controller
public class PermisosWebController {

    @Autowired
    private PermisosService permisosService;

    @Autowired
    private EmpleadoRepository empleadoRepository;

    @Autowired
    private VacacionesService vacacionesService;

    @Autowired
    private TurnoRepository turnoRepo;

    @Autowired
    private SolicitudVacacionesRepository solicitudVacacionesRepository;

    @Autowired
    private MotivoRechazoRepository motivoRechazoRepo;

    /**
     * Endpoint Maestro: Muestra la bandeja de aprobaciones unificada y control de piso.
     */
    @GetMapping("/pantallas/aprobaciones-permisos")
    public String mostrarBandejaPermisos(HttpSession session, Model model) {
        Object nominaObj = session.getAttribute("usuarioLogueado");
        if (nominaObj == null) {
            return "redirect:/login";
        }
        Integer nominaJefe = Integer.parseInt(nominaObj.toString());

        Empleado jefe = empleadoRepository.findById(nominaJefe)
                .orElseThrow(() -> new RuntimeException("Error: No se encontró el jefe con la nómina: " + nominaJefe));

        model.addAttribute("jefe", jefe);

        // 1. Obtenemos la plantilla
        List<Empleado> plantilla = vacacionesService.obtenerPlantillaDelJefe(nominaJefe);
        model.addAttribute("plantilla", plantilla);

        // 🎯 2. EL MOTOR FALTANTE: Contamos a la gente por WorkCenter (Headcount)
        Map<Integer, Long> headcountMap = plantilla.stream()
                .filter(e -> e.getWorkCenter() != null)
                .collect(Collectors.groupingBy(e -> e.getWorkCenter().getId(), Collectors.counting()));
        model.addAttribute("headcountMap", headcountMap);

        List<com.hrms.vacaciones.model.WorkCenter> misLineas = vacacionesService.obtenerWorkCentersPorJefe(nominaJefe);
        model.addAttribute("workCentersACargo", misLineas);
        model.addAttribute("listaTurnosMaster", turnoRepo.findByActivoTrue());

        List<Integer> wcIds = (misLineas != null) ? misLineas.stream().map(com.hrms.vacaciones.model.WorkCenter::getId).toList() : new ArrayList<>();

        // 🔒 LISTA BLANCA INDIVIDUAL: Solo permitimos incidencias operativas de un día que no sean vacaciones
        List<SolicitudPermiso> permisosFiltrados = permisosService.obtenerPermisosPendientesPorJefe(nominaJefe, wcIds).stream()
                .filter(p -> p.getTipoPermiso() != null
                        && p.getTipoPermiso().getCodigo() != null
                        && !"V".equalsIgnoreCase(p.getTipoPermiso().getCodigo().trim())
                        && !"VACACIONES".equalsIgnoreCase(p.getTipoPermiso().getCodigo().trim()))
                .toList();
        model.addAttribute("permisosPendientes", permisosFiltrados);

        // 🔒 CANDADO MAESTRO DE LISTA BLANCA (WHITELIST): Solo se renderizan permisos extendidos autorizados.
        List<SolicitudVacaciones> rangosFiltrados = vacacionesService.obtenerRangosPermisosPendientesPorJefe(nominaJefe).stream()
                .filter(r -> r.getTipoSolicitud() != null && (
                        "PT".equalsIgnoreCase(r.getTipoSolicitud().trim()) ||
                                "Paro Técnico".equalsIgnoreCase(r.getTipoSolicitud().trim()) ||
                                "HO".equalsIgnoreCase(r.getTipoSolicitud().trim()) ||
                                "Home Office".equalsIgnoreCase(r.getTipoSolicitud().trim()) ||
                                "C".equalsIgnoreCase(r.getTipoSolicitud().trim()) ||
                                "P".equalsIgnoreCase(r.getTipoSolicitud().trim()) ||
                                "TET".equalsIgnoreCase(r.getTipoSolicitud().trim())
                ))
                .toList();
        model.addAttribute("rangosDinamicosPendientes", rangosFiltrados);

        model.addAttribute("historialTxtColectivo", permisosService.obtenerHistorialTxtColectivoPorJefe(wcIds));

        Map<String, Long> metricasArea = permisosService.obtenerMetricasAreaPermisos(nominaJefe, wcIds);
        model.addAttribute("permisosAprobadosMes", metricasArea.get("permisosAprobadosMes"));
        model.addAttribute("ausenciasPermisosManana", metricasArea.get("ausenciasPermisosManana"));

        // 🛡️ Tubería de Motivos de Rechazo oficiales para Permisos (TXT, HO, PT, etc.)
        model.addAttribute("motivosRechazo", motivoRechazoRepo.findByModuloAndActivoTrue("PERMISOS"));

        return "aprobaciones-permisos";
    }

    /**
     * Procesa la respuesta individual de incidencias de un solo día (Aprobar / Rechazar).
     */
    @PostMapping("/jefe/permisos/responder")
    public String responderSolicitudIndividual(
            @RequestParam("idSolicitud") Long idSolicitud,
            @RequestParam("aprobado") boolean aprobado,
            @RequestParam(value = "comentarioRechazo", required = false) String comentarioRechazo,
            @RequestParam("nominaJefe") Integer nominaJefe,
            RedirectAttributes redirectAttributes) {

        try {
            // 🎯 AQUI METIMOS EL BISTURÍ: Se agregó nominaJefe
            permisosService.resolverSolicitudPermiso(idSolicitud, aprobado, comentarioRechazo, nominaJefe);
            String mensaje = aprobado ? "La incidencia fue autorizada y enviada a pre-nómina."
                    : "La solicitud de permiso fue rebotada correctamente.";
            redirectAttributes.addFlashAttribute("mensajeExito", mensaje);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al procesar: " + e.getMessage());
        }

        return "redirect:/pantallas/aprobaciones-permisos?nomina=" + nominaJefe;
    }

    /**
     * Procesa la respuesta de Aprobación o Rechazo MASIVO con Comentario Único.
     */
    @PostMapping("/jefe/permisos/responder-masivo")
    public String responderSolicitudesEnBloque(
            @RequestParam("nominaJefe") Integer nominaJefe,
            @RequestParam("aprobado") boolean aprobado,
            @RequestParam(value = "comentarioRechazo", required = false) String comentarioRechazo,
            @RequestParam(value = "itemsSeleccionados", required = false) List<String> itemsSeleccionados,
            RedirectAttributes redirectAttributes) {

        if (itemsSeleccionados == null || itemsSeleccionados.isEmpty()) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error de selección: No elegiste ninguna solicitud del listado.");
            return "redirect:/pantallas/aprobaciones-permisos?nomina=" + nominaJefe;
        }

        int exitosPermisos = 0;
        int exitosRangos = 0;
        int fallidos = 0;

        for (String token : itemsSeleccionados) {
            try {
                if (token.startsWith("permiso-")) {
                    Long id = Long.parseLong(token.replace("permiso-", ""));
                    // 🎯 AQUI TAMBIEN METIMOS EL BISTURÍ: Se agregó nominaJefe para las masivas
                    permisosService.resolverSolicitudPermiso(id, aprobado, comentarioRechazo, nominaJefe);
                    exitosPermisos++;
                } else if (token.startsWith("rango-")) {
                    Integer id = Integer.parseInt(token.replace("rango-", ""));

                    // 🔒 AUDITORÍA CONTABLE EN LOTE: Validar físicamente en BD que no se intente alterar una vacación ordinaria
                    SolicitudVacaciones checkRango = solicitudVacacionesRepository.findById(id)
                            .orElseThrow(() -> new RuntimeException("Error: No se localizó la solicitud #" + id));

                    if (checkRango.getTipoSolicitud() == null
                            || "V".equalsIgnoreCase(checkRango.getTipoSolicitud().trim())
                            || "VACACIONES".equalsIgnoreCase(checkRango.getTipoSolicitud().trim())) {
                        throw new RuntimeException("Denegado: Intento de procesar vacaciones desde el panel de permisos.");
                    }

                    if (aprobado) {
                        vacacionesService.aprobarPorJefe(id, nominaJefe);
                    } else {
                        com.hrms.vacaciones.dto.RespuestaJefeRequest respuestaDto =
                                new com.hrms.vacaciones.dto.RespuestaJefeRequest(id, false, comentarioRechazo != null ? comentarioRechazo : "Rechazado en lote masivo.");
                        vacacionesService.procesarRespuestaJefe(respuestaDto);
                    }
                    exitosRangos++;
                }
            } catch (Exception e) {
                fallidos++;
            }
        }

        String dictamen = aprobado ? "Aprobadas Masivamente" : "Rechazadas Masivamente";
        String txtExito = String.format("Proceso en lote finalizado con éxito: %d incidencias individuales y %d permisos extendidos fueron %s.", exitosPermisos, exitosRangos, dictamen);

        if (fallidos > 0) {
            redirectAttributes.addFlashAttribute("mensajeError", txtExito + " Ocurrió un error inesperado en " + fallidos + " registros.");
        } else {
            redirectAttributes.addFlashAttribute("mensajeExito", txtExito);
        }

        return "redirect:/pantallas/aprobaciones-permisos?nomina=" + nominaJefe;
    }

    /**
     * Exportador industrial de la Bitácora de Lotes TXT Colectivo a Excel (CSV).
     */
    @GetMapping("/jefe/permisos/exportar-txt-colectivo")
    public void exportarBitacoraTxtColectivo(@RequestParam("nomina") Integer nominaJefe, HttpServletResponse response) throws Exception {
        List<com.hrms.vacaciones.model.WorkCenter> misLineas = vacacionesService.obtenerWorkCentersPorJefe(nominaJefe);
        List<Integer> wcIds = (misLineas != null) ? misLineas.stream().map(com.hrms.vacaciones.model.WorkCenter::getId).toList() : new ArrayList<>();
        List<SolicitudPermiso> lista = permisosService.obtenerHistorialTxtColectivoPorJefe(wcIds);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=Bitacora_TXT_Colectivo_Jefe_" + nominaJefe + ".csv");

        response.getOutputStream().write(0xEF);
        response.getOutputStream().write(0xBB);
        response.getOutputStream().write(0xBF);

        PrintWriter writer = new PrintWriter(new OutputStreamWriter(response.getOutputStream(), "UTF-8"));
        writer.println("No. Nómina,Colaborador,Línea (WC),Fecha del Paro (Incidencia),Fechas de Reposición (Plan de Pago),Horas Totales,Justificación de Planta,Fecha de Inyección");

        for (SolicitudPermiso p : lista) {
            StringBuilder pagosStr = new StringBuilder();
            BigDecimal horasTotales = BigDecimal.ZERO;
            if (p.getDesglosesPago() != null) {
                for (var pago : p.getDesglosesPago()) {
                    if (pagosStr.length() > 0) pagosStr.append(" | ");
                    pagosStr.append(pago.getFechaPago()).append(" (").append(pago.getHorasPago()).append(" hrs)");
                    horasTotales = horasTotales.add(pago.getHorasPago());
                }
            }

            String comentario = p.getJustificacionSupervisor() != null
                    ? p.getJustificacionSupervisor().replace(",", " ").replace("\n", " ").replace("\r", " ")
                    : "Sin notas";

            writer.println(String.format("%d,%s,WC %d,%s,%s,%s,%s,%s",
                    p.getEmpleado().getNomina(),
                    p.getEmpleado().getNombreCompleto(),
                    p.getEmpleado().getWorkCenter() != null ? p.getEmpleado().getWorkCenter().getId() : 0,
                    p.getFechaIncidencia(),
                    pagosStr.length() > 0 ? pagosStr.toString() : "N/A",
                    horasTotales.toString(),
                    comentario,
                    p.getFechaSolicitud() != null ? p.getFechaSolicitud().toLocalDate().toString() : "N/A"
            ));
        }
        writer.flush();
        writer.close();
    }

    /**
     * Registra un lote masivo de TXT Colectivo por línea operativa.
     */
    @PostMapping("/jefe/permisos/colectivo-guardar")
    public String guardarTxtColectivo(
            @RequestParam("nominaJefe") Integer nominaJefe,
            @RequestParam("fechaIncidenciaColectiva") String fechaStr,
            @RequestParam("empleadoIds") List<Integer> empleadoIds,
            @RequestParam("justificacionColectiva") String justificacion,
            RedirectAttributes redirectAttributes) {

        try {
            LocalDate fechaIncidencia = LocalDate.parse(fechaStr);
            permisosService.registrarTxtColectivoMasivo(nominaJefe, fechaIncidencia, empleadoIds, justificacion);
            redirectAttributes.addFlashAttribute("mensajeExito", "¡Lote de TXT Colectivo applied! Se inyectaron " + empleadoIds.size() + " registros autorizados de forma directa.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error en lote masivo: " + e.getMessage());
        }
        return "redirect:/pantallas/aprobaciones-permisos?nomina=" + nominaJefe;
    }

    /**
     * Permite al supervisor solicitar la recuperación de una falta extraordinaria con tipo de incidencia.
     */
    @PostMapping("/jefe/permisos/solicitar-recuperacion")
    public String supervisorSolicitaRecuperacionExtraordinaria(
            @RequestParam("nominaJefe") Integer nominaJefe,
            @RequestParam("nominaEmpleado") Integer userName,
            @RequestParam("fechaFalta") String fechaFaltaStr,
            @RequestParam("tipoIncidencia") String tipoIncidencia,
            @RequestParam("justificacion") String justificacion,
            RedirectAttributes redirectAttributes) {

        try {
            LocalDate fechaFalta = LocalDate.parse(fechaFaltaStr);
            String justificacionConTipo = "[" + tipoIncidencia.toUpperCase() + "] " + justificacion;

            vacacionesService.crearSolicitudRecuperacion(nominaJefe, userName, fechaFalta, justificacionConTipo);
            redirectAttributes.addFlashAttribute("mensajeExito", "La solicitud extraordinaria de recuperación [" + tipoIncidencia + "] fue enviada con éxito a Nóminas.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Rechazo contable: " + e.getMessage());
        }
        return "redirect:/pantallas/aprobaciones-permisos?nomina=" + nominaJefe;
    }

    /**
     * API del Calendario: Devuelve el mapeo unificado de incidencias con protección Lazy Loading.
     */
    @GetMapping("/jefe/api/calendario-permisos-eventos")
    @ResponseBody
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Map<String, Object>> API_calendarioPermisosEventos(@RequestParam("nomina") Integer nominaJefe) {
        List<com.hrms.vacaciones.model.WorkCenter> misLineas = vacacionesService.obtenerWorkCentersPorJefe(nominaJefe);
        List<Integer> wcIds = (misLineas != null) ? misLineas.stream().map(com.hrms.vacaciones.model.WorkCenter::getId).toList() : new ArrayList<>();

        return permisosService.obtenerEventosCalendarioPermisos(nominaJefe, wcIds);
    }

    /**
     * Procesa la respuesta de permisos por rango extendido (PT/HO) y asegura el retorno a la bandeja correcta.
     */
    @PostMapping("/jefe/permisos/responder-rango")
    public String responderSolicitudPermisoRango(
            @RequestParam("nominaJefe") Integer nominaJefe,
            @RequestParam("idSolicitud") Integer idSolicitud,
            @RequestParam(value = "aprobado", defaultValue = "true") boolean aprobado,
            RedirectAttributes redirectAttributes) {

        try {
            // 🔒 COMPROBACIÓN TRANSACCIONAL INDIVIDUAL: Detener inmediatamente si es una solicitud de vacaciones ordinarias
            SolicitudVacaciones checkRango = solicitudVacacionesRepository.findById(idSolicitud)
                    .orElseThrow(() -> new RuntimeException("Error: No se localizó la solicitud de rango #" + idSolicitud));

            if (checkRango.getTipoSolicitud() == null
                    || "V".equalsIgnoreCase(checkRango.getTipoSolicitud().trim())
                    || "VACACIONES".equalsIgnoreCase(checkRango.getTipoSolicitud().trim())) {
                throw new RuntimeException("Operación Denegada: No se permite procesar vacaciones desde esta pantalla.");
            }

            if (aprobado) {
                vacacionesService.aprobarPorJefe(idSolicitud, nominaJefe);
                redirectAttributes.addFlashAttribute("mensajeExito", "El permiso extendido fue autorizado correctamente.");
            } else {
                com.hrms.vacaciones.dto.RespuestaJefeRequest respuestaDto =
                        new com.hrms.vacaciones.dto.RespuestaJefeRequest(idSolicitud, false, "Rechazado desde acción rápida de permisos");
                vacacionesService.procesarRespuestaJefe(respuestaDto);
                redirectAttributes.addFlashAttribute("mensajeExito", "La solicitud de permiso fue rechazada.");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("mensajeError", "Error al procesar el permiso: " + e.getMessage());
        }

        return "redirect:/pantallas/aprobaciones-permisos?nomina=" + nominaJefe;
    }
}
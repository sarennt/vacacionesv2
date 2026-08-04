package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.model.*;
import com.hrms.vacaciones.repository.*;
import com.hrms.vacaciones.service.VacacionesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Slf4j
@Controller
public class TorreControlController {

    private final DiasFestivosRepository diasFestivosRepository;
    private final ConfiguracionSistemaRepository configSistemaRepo;
    private final ConfiguracionCorteNominaRepository configCorteRepo;
    private final ConfiguracionRolesNominaRepository rolesNominaRepo;
    private final TipoPermisoRepository tipoPermisoRepo;
    private final TurnoRepository turnoRepo;
    private final VacacionesService vacacionesService;
    private final ReglaTopeEstacionRepository reglaTopeEstacionRepository;
    private final MotivoRechazoRepository motivoRechazoRepo; // ✨ Nueva Tubería de Auditoría

    @Autowired
    public TorreControlController(DiasFestivosRepository diasFestivosRepository,
                                  ConfiguracionSistemaRepository configSistemaRepo,
                                  ConfiguracionCorteNominaRepository configCorteRepo,
                                  ConfiguracionRolesNominaRepository rolesNominaRepo,
                                  TipoPermisoRepository tipoPermisoRepo,
                                  TurnoRepository turnoRepo,
                                  VacacionesService vacacionesService,
                                  ReglaTopeEstacionRepository reglaTopeEstacionRepository,
                                  MotivoRechazoRepository motivoRechazoRepo) {
        this.diasFestivosRepository = diasFestivosRepository;
        this.configSistemaRepo = configSistemaRepo;
        this.configCorteRepo = configCorteRepo;
        this.rolesNominaRepo = rolesNominaRepo;
        this.tipoPermisoRepo = tipoPermisoRepo;
        this.turnoRepo = turnoRepo;
        this.vacacionesService = vacacionesService;
        this.reglaTopeEstacionRepository = reglaTopeEstacionRepository;
        this.motivoRechazoRepo = motivoRechazoRepo;
    }

    @GetMapping("/pantallas/torre-control")
    public String mostrarTorreControl(HttpSession session, Model model) {
        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return "redirect:/login";
        Integer nominaUsuario = Integer.parseInt(logueadoObj.toString());

        if (!vacacionesService.esAdminORH(nominaUsuario)) {
            log.warn("¡Alerta de Intruso! La nómina {} intentó meterse sin permisos.", nominaUsuario);
            return "redirect:/pantallas/dashboard?error=sin-permisos";
        }

        model.addAttribute("nominaLogueada", nominaUsuario);

        // --- CARGA DE CATÁLOGOS BASE ---
        model.addAttribute("listaFestivos", diasFestivosRepository.findByActivoTrue());
        model.addAttribute("listaTurnosMaestros", turnoRepo.findAll());
        model.addAttribute("listaIncidencias", tipoPermisoRepo.findAll());
        model.addAttribute("listaMotivosRechazo", motivoRechazoRepo.findAll()); // ✨ Payload de Rechazos

        // --- 🛡️ EXTRACCIÓN DINÁMICA DESDE LA MATRIZ DE COBERTURA REGLAS ---
        model.addAttribute("listaReglasTope", reglaTopeEstacionRepository.findAll());

        // Candado por defecto del corte de Ranking de Rezago para inyección inicial
        model.addAttribute("rankingCorteDefault", configSistemaRepo.findById("RANKING_DEFAULT_CORTE").map(ConfiguracionSistema::getValor).orElse("10"));

        // Reglas de cortes y anticipaciones tradicionales
        model.addAttribute("horasAntSind", configSistemaRepo.findById("HORAS_ANTICIPACION_SIND").map(ConfiguracionSistema::getValor).orElse("48"));
        ConfiguracionCorteNomina cSind = configCorteRepo.findByTipoEmpleado("SINDICALIZADO").orElse(new ConfiguracionCorteNomina());
        model.addAttribute("corteSind", cSind.getDiaCorte() != null ? cSind.getDiaCorte() : 2);
        model.addAttribute("horaCorteSind", cSind.getHoraCorte());

        model.addAttribute("horasAntAdmin", configSistemaRepo.findById("HORAS_ANTICIPACION_ADMIN").map(ConfiguracionSistema::getValor).orElse("0"));
        ConfiguracionCorteNomina cAdmin = configCorteRepo.findByTipoEmpleado("ADMINISTRATIVO").orElse(new ConfiguracionCorteNomina());
        model.addAttribute("corteAdmin", cAdmin.getDiaCorte() != null ? cAdmin.getDiaCorte() : 3);
        model.addAttribute("horaCorteAdmin", cAdmin.getHoraCorte());

        // Tiempo SLA Jefaturas
        model.addAttribute("horasSlaJefe", configSistemaRepo.findById("SLA_RESPUESTA_JEFE").map(ConfiguracionSistema::getValor).orElse("48"));

        // Responsables asignados
        model.addAttribute("respSind", rolesNominaRepo.findById("RESPONSABLE_SIND").map(ConfiguracionRolesNomina::getNumNominaAsignada).orElse(null));
        model.addAttribute("respAdmin", rolesNominaRepo.findById("RESPONSABLE_ADMIN").map(ConfiguracionRolesNomina::getNumNominaAsignada).orElse(null));
        model.addAttribute("jefaNomina", rolesNominaRepo.findById("JEFA_NOMINA").map(ConfiguracionRolesNomina::getNumNominaAsignada).orElse(null));
        model.addAttribute("progMaster", rolesNominaRepo.findById("PROGRAMADOR_MASTER").map(ConfiguracionRolesNomina::getNumNominaAsignada).orElse(null));
        model.addAttribute("progBackup", rolesNominaRepo.findById("PROGRAMADOR_RESPALDO").map(ConfiguracionRolesNomina::getNumNominaAsignada).orElse(null));

        // =========================================================================
        // ✨ NUEVO: LECTURA DEL SWITCH MAESTRO DE AUTO-APROBACIÓN (LA BARREDORA)
        // =========================================================================
        boolean escalamientoActivo = configSistemaRepo.findById("ESCALAMIENTO_AUTOMATICO")
                .map(c -> "TRUE".equalsIgnoreCase(c.getValor()) || "ENABLED".equalsIgnoreCase(c.getValor()) || "1".equals(c.getValor()))
                .orElse(false);

        model.addAttribute("escalamientoActivo", escalamientoActivo);
        // =========================================================================

        return "torre-control";
    }

    // =========================================================================
    // 🛡️ ADMINISTRACIÓN EN CALIENTE: MOTIVOS DE RECHAZO ESTANDARIZADOS
    // =========================================================================
    @PostMapping("/admin/motivos-rechazo/guardar")
    public String guardarMotivoRechazo(
            @RequestParam("modulo") String modulo,
            @RequestParam("codigo") String codigo,
            @RequestParam("descripcion") String descripcion,
            org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {

        try {
            MotivoRechazo motivo = MotivoRechazo.builder()
                    .modulo(modulo.toUpperCase().trim())
                    .codigo(codigo.toUpperCase().trim())
                    .descripcion(descripcion.trim())
                    .activo(true)
                    .build();
            motivoRechazoRepo.save(motivo);

            // Alerta de éxito para el Front
            redirectAttributes.addFlashAttribute("mensajeExitoMotivo", "¡Motivo '" + codigo.toUpperCase() + "' indexado al catálogo con éxito!");
            log.info("Torre de Control: Nuevo motivo de rechazo fijo [{}] guardado contablemente.", codigo);

        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("Advertencia de duplicidad en motivos de rechazo: {}", e.getMessage());
            redirectAttributes.addFlashAttribute("mensajeErrorMotivo",
                    "¡Código Duplicado! El código '" + codigo.toUpperCase() + "' ya existe dentro del universo de " + modulo.toUpperCase() + ". Usa un identificador diferente.");

        } catch (Exception e) {
            log.error("Falla crítica al registrar motivo de rechazo: ", e);
            redirectAttributes.addFlashAttribute("mensajeErrorMotivo",
                    "Error de Validación de Insumos: Revisa que el texto no exceda los límites de caracteres o contenga símbolos inválidos.");
        }

        return "redirect:/pantallas/torre-control?tab=incidencias";
    }

    @PostMapping("/admin/motivos-rechazo/desactivar/{id}")
    public String desactivarMotivoRechazo(@PathVariable("id") Integer id) {
        motivoRechazoRepo.findById(id).ifPresent(m -> {
            m.setActivo(false);
            motivoRechazoRepo.save(m);
            log.info("Torre de Control: Motivo de rechazo ID {} inhabilitado de las bandejas.", id);
        });
        return "redirect:/pantallas/torre-control?tab=incidencias";
    }

    @PostMapping("/admin/motivos-rechazo/activar/{id}")
    public String activarMotivoRechazo(@PathVariable("id") Integer id) {
        motivoRechazoRepo.findById(id).ifPresent(m -> {
            m.setActivo(true);
            motivoRechazoRepo.save(m);
            log.info("Torre de Control: Motivo de rechazo ID {} reactivado exitosamente.", id);
        });
        return "redirect:/pantallas/torre-control?tab=incidencias";
    }

    // =========================================================================
    // 🎯 PARAMETRIZACIÓN DINÁMICA DE COBERTURAS Y RANKING DE CORTE GLOBAL
    // =========================================================================
    @PostMapping("/admin/reglas-cobertura/guardar")
    public String guardarReglaTopeDinamica(
            @RequestParam("minOperadores") Integer minOps,
            @RequestParam("maxOperadores") Integer maxOps,
            @RequestParam("maxAusentesPorDia") Integer maxAus) {

        ReglaTopeEstacion nuevaRegla = ReglaTopeEstacion.builder()
                .minOperadores(minOps)
                .maxOperadores(maxOps)
                .maxAusentesPorDia(maxAus)
                .build();
        reglaTopeEstacionRepository.save(nuevaRegla);
        log.info("Torre de Control: Nueva regla de Headcount Caps inyectada exitosamente.");
        return "redirect:/pantallas/torre-control?tab=turnos";
    }

    @PostMapping("/admin/reglas-cobertura/eliminar/{id}")
    public String eliminarReglaTopeDinamica(@PathVariable("id") Integer id) {
        reglaTopeEstacionRepository.deleteById(id);
        log.info("Torre de Control: Regla de Cobertura ID {} removida de PostgreSQL.", id);
        return "redirect:/pantallas/torre-control?tab=turnos";
    }

    @PostMapping("/admin/reglas-globales/guardar-ranking-default")
    public String guardarRankingDefault(@RequestParam("rankingCorteDefault") String rankingCorteDefault) {
        configSistemaRepo.save(ConfiguracionSistema.builder()
                .clave("RANKING_DEFAULT_CORTE")
                .valor(rankingCorteDefault)
                .descripcion("Corte por defecto del ranking de rezago")
                .build());
        log.info("Torre de Control: Umbral Base de Rezago VIP guardado en configuraciones.");
        return "redirect:/pantallas/torre-control?tab=turnos";
    }

    // ==========================================
    // SECCIÓN: GESTIÓN DE TURNOS MAESTROS (cat_turnos)
    // ==========================================
    @PostMapping("/admin/turnos/guardar")
    public String guardarTurnoUnificado(
            @RequestParam("nombreTurno") String nombreTurno,
            @RequestParam("horasJornada") BigDecimal horasJornada,
            @RequestParam("mundo") String mundo,
            @RequestParam(value = "diasDescanso", required = false) List<String> diasDescanso,
            @RequestParam(value = "peso", defaultValue = "3") Integer peso,
            @RequestParam(value = "esPorDefecto", required = false) Boolean esPorDefecto) {

        StringBuilder sb = new StringBuilder();
        if (diasDescanso != null) {
            for (String d : diasDescanso) {
                String ds = traducirDiaFiel(d);
                if (ds != null) { if (sb.length() > 0) sb.append(","); sb.append(ds); }
            }
        }
        String unificados = sb.length() > 0 ? sb.toString() : "DOMINGO";

        Turno turno = new Turno();
        turno.setNombreTurno(nombreTurno.trim());
        turno.setHorasJornada(horasJornada);
        turno.setMundo(mundo.toUpperCase());
        turno.setDiasDescanso(unificados);
        turno.setPeso(peso);
        turno.setEsPorDefecto(esPorDefecto != null && esPorDefecto);
        turno.setActivo(true);

        turnoRepo.save(turno);
        return "redirect:/pantallas/torre-control?tab=turnos";
    }

    @PostMapping("/admin/turnos/actualizar-datos/{id}")
    public String actualizarDatosTurnoUnificado(
            @PathVariable("id") Integer id,
            @RequestParam("horasJornada") BigDecimal horasJornada,
            @RequestParam("mundo") String mundo,
            @RequestParam("diasDescanso") String diasDescanso,
            @RequestParam("peso") Integer peso,
            @RequestParam(value = "esPorDefecto", required = false) Boolean esPorDefecto) {

        turnoRepo.findById(id).ifPresent(turno -> {
            turno.setHorasJornada(horasJornada);
            turno.setMundo(mundo.toUpperCase());
            turno.setDiasDescanso(diasDescanso.toUpperCase().trim());
            turno.setPeso(peso);
            turno.setEsPorDefecto(esPorDefecto != null && esPorDefecto);
            turnoRepo.save(turno);
        });
        return "redirect:/pantallas/torre-control?tab=turnos";
    }

    @PostMapping("/admin/turnos/desactivar/{id}")
    public String desactivarTurnoUnificado(@PathVariable("id") Integer id) {
        turnoRepo.findById(id).ifPresent(t -> { t.setActivo(false); turnoRepo.save(t); });
        return "redirect:/pantallas/torre-control?tab=turnos";
    }

    @PostMapping("/admin/turnos/activar/{id}")
    public String activarTurnoUnificado(@PathVariable("id") Integer id) {
        turnoRepo.findById(id).ifPresent(t -> { t.setActivo(true); turnoRepo.save(t); });
        return "redirect:/pantallas/torre-control?tab=turnos";
    }

    private String traducirDiaFiel(String dia) {
        if (dia == null || dia.trim().isEmpty()) return null;
        String s = dia.trim();
        switch (s) {
            case "1": return "LUNES"; case "2": return "MARTES"; case "3": return "MIERCOLES";
            case "4": return "JUEVES"; case "5": return "VIERNES"; case "6": return "SABADO";
            case "0": case "7": return "DOMINGO"; default: return s.toUpperCase();
        }
    }

    private Integer parseOptionalInteger(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return null; }
    }

    @PostMapping("/admin/motivos-rechazo/actualizar-datos/{id}")
    public String actualizarDatosMotivoRechazo(
            @PathVariable("id") Integer id,
            @RequestParam("descripcion") String descripcion,
            org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {

        try {
            motivoRechazoRepo.findById(id).ifPresent(motivo -> {
                motivo.setDescripcion(descripcion.trim());
                motivoRechazoRepo.save(motivo);
                log.info("Torre de Control: Motivo de rechazo ID {} actualizado con éxito.", id);
            });
            redirectAttributes.addFlashAttribute("mensajeExitoMotivo", "¡Descripción de rechazo modificada con éxito!");
        } catch (Exception e) {
            log.error("Falla al actualizar motivo de rechazo: ", e);
            redirectAttributes.addFlashAttribute("mensajeErrorMotivo", "Falla del Servidor: No se pudo actualizar la descripción.");
        }
        return "redirect:/pantallas/torre-control?tab=incidencias";
    }

    @PostMapping("/admin/nomina/guardar-sind") public String guardarReglasSind(@RequestParam("corteSind") Integer corteSind, @RequestParam("horaCorte") String horaCorte, @RequestParam("horasAntSind") String horasSind) { configSistemaRepo.save(ConfiguracionSistema.builder().clave("HORAS_ANTICIPACION_SIND").valor(horasSind).descripcion("Horas previas").build()); ConfiguracionCorteNomina conf = configCorteRepo.findByTipoEmpleado("SINDICALIZADO").orElse(new ConfiguracionCorteNomina()); conf.setTipoEmpleado("SINDICALIZADO"); conf.setDiaCorte(corteSind); if (horaCorte != null && !horaCorte.isEmpty()) conf.setHoraCorte(LocalTime.parse(horaCorte)); configCorteRepo.save(conf); return "redirect:/pantallas/torre-control?tab=nomina"; }
    @PostMapping("/admin/nomina/guardar-admin") public String guardarReglasAdmin(@RequestParam("corteAdmin") Integer corteAdmin, @RequestParam("horaCorteAdmin") String horaCorteAdmin, @RequestParam("horasAntAdmin") String horasAdmin) { configSistemaRepo.save(ConfiguracionSistema.builder().clave("HORAS_ANTICIPACION_ADMIN").valor(horasAdmin).descripcion("Horas previas").build()); ConfiguracionCorteNomina conf = configCorteRepo.findByTipoEmpleado("ADMINISTRATIVO").orElse(new ConfiguracionCorteNomina()); conf.setTipoEmpleado("ADMINISTRATIVO"); conf.setDiaCorte(corteAdmin); if (horaCorteAdmin != null && !horaCorteAdmin.isEmpty()) conf.setHoraCorte(LocalTime.parse(horaCorteAdmin)); configCorteRepo.save(conf); return "redirect:/pantallas/torre-control?tab=nomina"; }
    @PostMapping("/admin/nomina/guardar-roles") public String guardarRolesNomina(@RequestParam("respSind") String respSind, @RequestParam("respAdmin") String respAdmin, @RequestParam("jefaNomina") String jefaNomina, @RequestParam("progMaster") String progMaster, @RequestParam("progBackup") String progBackup) { rolesNominaRepo.save(new ConfiguracionRolesNomina("RESPONSABLE_SIND", parseOptionalInteger(respSind))); rolesNominaRepo.save(new ConfiguracionRolesNomina("RESPONSABLE_ADMIN", parseOptionalInteger(respAdmin))); rolesNominaRepo.save(new ConfiguracionRolesNomina("JEFA_NOMINA", parseOptionalInteger(jefaNomina))); rolesNominaRepo.save(new ConfiguracionRolesNomina("PROGRAMADOR_MASTER", parseOptionalInteger(progMaster))); rolesNominaRepo.save(new ConfiguracionRolesNomina("PROGRAMADOR_RESPALDO", parseOptionalInteger(progBackup))); return "redirect:/pantallas/torre-control?tab=nomina"; }
    @PostMapping("/admin/nomina/guardar-sla")
    public String guardarSlaJefe(@RequestParam("horasSlaJefe") String horasSlaJefe) {
        configSistemaRepo.save(ConfiguracionSistema.builder()
                .clave("SLA_RESPUESTA_JEFE")
                .valor(horasSlaJefe)
                .descripcion("Horas de SLA para respuesta del Jefe")
                .build());
        log.info("Torre de Control: Tiempo SLA actualizado a {} horas", horasSlaJefe);
        return "redirect:/pantallas/torre-control?tab=nomina";
    }
    @PostMapping("/admin/festivos/guardar") public String guardarFestivo(@RequestParam("fecha") LocalDate fecha, @RequestParam("descripcion") String descripcion) { diasFestivosRepository.save(DiasFestivos.builder().fecha(fecha).descripcion(descripcion).activo(true).build()); return "redirect:/pantallas/torre-control?tab=festivos"; }
    @PostMapping("/admin/festivos/desactivar/{id}") public String desactivarFestivo(@PathVariable("id") Integer id) { diasFestivosRepository.findById(id).ifPresent(f -> { f.setActivo(false); diasFestivosRepository.save(f); }); return "redirect:/pantallas/torre-control?tab=festivos"; }
    @PostMapping("/admin/incidencias/guardar") public String guardarIncidencia(@RequestParam("codigo") String codigo, @RequestParam("descripcion") String descripcion, @RequestParam("aplicaA") String aplicaA) { tipoPermisoRepo.save(TipoPermiso.builder().codigo(codigo.toUpperCase().trim()).descripcion(descripcion.trim()).aplicaA(aplicaA.toUpperCase()).activo(true).build()); return "redirect:/pantallas/torre-control?tab=incidencias"; }
    @PostMapping("/admin/incidencias/desactivar/{id}") public String desactivarIncidencia(@PathVariable("id") Integer id) { tipoPermisoRepo.findById(id).ifPresent(p -> { p.setActivo(false); tipoPermisoRepo.save(p); }); return "redirect:/pantallas/torre-control?tab=incidencias"; }
    @PostMapping("/admin/incidencias/activar/{id}") public String activarIncidencia(@PathVariable("id") Integer id) { tipoPermisoRepo.findById(id).ifPresent(p -> { p.setActivo(true); tipoPermisoRepo.save(p); }); return "redirect:/pantallas/torre-control?tab=incidencias"; }
    @PostMapping("/admin/incidencias/actualizar-cobertura/{id}") public String actualizarCoberturaIncidencia(@PathVariable("id") Integer id, @RequestParam("aplicaA") String aplicaA) { tipoPermisoRepo.findById(id).ifPresent(p -> { p.setAplicaA(aplicaA.toUpperCase()); tipoPermisoRepo.save(p); }); return "redirect:/pantallas/torre-control?tab=incidencias"; }
}
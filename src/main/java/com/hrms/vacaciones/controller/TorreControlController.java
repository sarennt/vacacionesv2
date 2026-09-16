package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.model.*;
import com.hrms.vacaciones.repository.*;
import com.hrms.vacaciones.service.TabuladorCache;
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
import com.hrms.vacaciones.service.TabuladorCache;


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
    private final MotivoRechazoRepository motivoRechazoRepo;
    private final TabuladorVacacionesRepository tabuladorRepo;
    private final TabuladorCache tabuladorCache;
    private final RangoTurnoRepository rangoTurnoRepo;

    @Autowired
    public TorreControlController(DiasFestivosRepository diasFestivosRepository,
                                  ConfiguracionSistemaRepository configSistemaRepo,
                                  ConfiguracionCorteNominaRepository configCorteRepo,
                                  ConfiguracionRolesNominaRepository rolesNominaRepo,
                                  TipoPermisoRepository tipoPermisoRepo,
                                  TurnoRepository turnoRepo,
                                  VacacionesService vacacionesService,
                                  MotivoRechazoRepository motivoRechazoRepo,
                                  TabuladorVacacionesRepository tabuladorRepo,
                                  TabuladorCache tabuladorCache,
                                  RangoTurnoRepository rangoTurnoRepo) {
        this.diasFestivosRepository = diasFestivosRepository;
        this.configSistemaRepo = configSistemaRepo;
        this.configCorteRepo = configCorteRepo;
        this.rolesNominaRepo = rolesNominaRepo;
        this.tipoPermisoRepo = tipoPermisoRepo;
        this.turnoRepo = turnoRepo;
        this.vacacionesService = vacacionesService;
        this.motivoRechazoRepo = motivoRechazoRepo;
        this.tabuladorRepo = tabuladorRepo;
        this.tabuladorCache = tabuladorCache;
        this.rangoTurnoRepo = rangoTurnoRepo;
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
        model.addAttribute("listaRangos", rangoTurnoRepo.findAll());
        model.addAttribute("listaIncidencias", tipoPermisoRepo.findAll());
        model.addAttribute("listaMotivosRechazo", motivoRechazoRepo.findAll());
        // ✨ SEMILLERO Y CARGA DEL TABULADOR DE LEY
        if (tabuladorRepo.count() == 0) {
            int[] baseLey = {12, 14, 16, 18, 20, 22, 22, 22, 22, 22, 24, 24, 24, 24, 24, 26, 26, 26, 26, 26};
            for (int i = 0; i < baseLey.length; i++) {
                tabuladorRepo.save(new TabuladorVacaciones(i + 1, baseLey[i]));
            }
            log.info("Torre de Control: Semillero del Tabulador de Vacaciones inyectado con éxito.");
        }
        model.addAttribute("listaTabulador", tabuladorRepo.findAll(org.springframework.data.domain.Sort.by("anioAntiguedad")));
        // ✨ Inyectamos el bono al HTML
        model.addAttribute("bonoAdmin", configSistemaRepo.findById("BONO_DIAS_ADMIN").map(ConfiguracionSistema::getValor).orElse("2"));

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
        // Llaves especiales para visores globales (Secretarias/Auditores)
        model.addAttribute("visorPrenomina", rolesNominaRepo.findById("VISOR_PRENOMINA").map(ConfiguracionRolesNomina::getNumNominaAsignada).orElse(null));
        model.addAttribute("visorBiometrico", rolesNominaRepo.findById("VISOR_BIOMETRICO").map(ConfiguracionRolesNomina::getNumNominaAsignada).orElse(null));
        model.addAttribute("visorMetricas", rolesNominaRepo.findById("VISOR_METRICAS").map(ConfiguracionRolesNomina::getNumNominaAsignada).orElse(null));

        // =========================================================================
        // ✨ LECTURA DEL SWITCH MAESTRO DE AUTO-APROBACIÓN (LA BARREDORA)
        // =========================================================================
        boolean escalamientoActivo = configSistemaRepo.findById("ESCALAMIENTO_AUTOMATICO")
                .map(c -> "TRUE".equalsIgnoreCase(c.getValor()) || "ENABLED".equalsIgnoreCase(c.getValor()) || "1".equals(c.getValor()))
                .orElse(false);

        model.addAttribute("escalamientoActivo", escalamientoActivo);

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

    // ==========================================
    // SECCIÓN: GESTIÓN DE TURNOS MAESTROS (cat_turnos)
    // ==========================================
    @PostMapping("/admin/turnos/guardar")
    public String guardarTurnoUnificado(
            @RequestParam("nombreTurno") String nombreTurno,
            @RequestParam("horasJornada") BigDecimal horasJornada,
            @RequestParam("horasSemanales") BigDecimal horasSemanales, // ✨ NUEVO
            @RequestParam("mundo") String mundo,
            @RequestParam(value = "diasDescanso", required = false) List<String> diasDescanso) {

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
        turno.setHorasSemanales(horasSemanales); // ✨ NUEVO
        turno.setMundo(mundo.toUpperCase());
        turno.setDiasDescanso(unificados);
        turno.setActivo(true);

        turnoRepo.save(turno);
        return "redirect:/pantallas/torre-control?tab=turnos";
    }

    @PostMapping("/admin/turnos/actualizar-datos/{id}")
    public String actualizarDatosTurnoUnificado(
            @PathVariable("id") Integer id,
            @RequestParam("horasJornada") BigDecimal horasJornada,
            @RequestParam("horasSemanales") BigDecimal horasSemanales, // ✨ NUEVO
            @RequestParam("mundo") String mundo,
            @RequestParam("diasDescanso") String diasDescanso) {

        turnoRepo.findById(id).ifPresent(turno -> {
            turno.setHorasJornada(horasJornada);
            turno.setHorasSemanales(horasSemanales); // ✨ NUEVO
            turno.setMundo(mundo.toUpperCase());
            turno.setDiasDescanso(diasDescanso.toUpperCase().trim());
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
    @PostMapping("/admin/rangos/guardar")
    public String guardarRangoTurno(
            @RequestParam("nombreTurno") String nombreTurno,
            @RequestParam("entradaDesde") String entradaDesde,
            @RequestParam("entradaHasta") String entradaHasta,
            @RequestParam("salidaDesde") String salidaDesde,
            @RequestParam("salidaHasta") String salidaHasta) {

        RangoTurno rango = RangoTurno.builder()
                .nombreTurno(nombreTurno.trim().toUpperCase())
                .entradaDesde(LocalTime.parse(entradaDesde))
                .entradaHasta(LocalTime.parse(entradaHasta))
                .salidaDesde(LocalTime.parse(salidaDesde))
                .salidaHasta(LocalTime.parse(salidaHasta))
                .build();

        rangoTurnoRepo.save(rango);
        return "redirect:/pantallas/torre-control?tab=turnos";
    }

    @PostMapping("/admin/rangos/actualizar-datos/{id}")
    public String actualizarDatosRangoTurno(
            @PathVariable("id") Integer id,
            @RequestParam("entradaDesde") String entradaDesde,
            @RequestParam("entradaHasta") String entradaHasta,
            @RequestParam("salidaDesde") String salidaDesde,
            @RequestParam("salidaHasta") String salidaHasta) {

        rangoTurnoRepo.findById(id).ifPresent(rango -> {
            rango.setEntradaDesde(LocalTime.parse(entradaDesde));
            rango.setEntradaHasta(LocalTime.parse(entradaHasta));
            rango.setSalidaDesde(LocalTime.parse(salidaDesde));
            rango.setSalidaHasta(LocalTime.parse(salidaHasta));
            rangoTurnoRepo.save(rango);
        });
        return "redirect:/pantallas/torre-control?tab=turnos";
    }

    @PostMapping("/admin/rangos/eliminar/{id}")
    public String eliminarRangoTurno(@PathVariable("id") Integer id) {
        rangoTurnoRepo.deleteById(id);
        return "redirect:/pantallas/torre-control?tab=turnos";
    }

    // ==========================================
    // SECCIÓN: NÓMINA, FESTIVOS E INCIDENCIAS
    // ==========================================
    @PostMapping("/admin/nomina/guardar-sind") public String guardarReglasSind(@RequestParam("corteSind") Integer corteSind, @RequestParam("horaCorte") String horaCorte, @RequestParam("horasAntSind") String horasSind) { configSistemaRepo.save(ConfiguracionSistema.builder().clave("HORAS_ANTICIPACION_SIND").valor(horasSind).descripcion("Horas previas").build()); ConfiguracionCorteNomina conf = configCorteRepo.findByTipoEmpleado("SINDICALIZADO").orElse(new ConfiguracionCorteNomina()); conf.setTipoEmpleado("SINDICALIZADO"); conf.setDiaCorte(corteSind); if (horaCorte != null && !horaCorte.isEmpty()) conf.setHoraCorte(LocalTime.parse(horaCorte)); configCorteRepo.save(conf); return "redirect:/pantallas/torre-control?tab=nomina"; }
    @PostMapping("/admin/nomina/guardar-admin") public String guardarReglasAdmin(@RequestParam("corteAdmin") Integer corteAdmin, @RequestParam("horaCorteAdmin") String horaCorteAdmin, @RequestParam("horasAntAdmin") String horasAdmin) { configSistemaRepo.save(ConfiguracionSistema.builder().clave("HORAS_ANTICIPACION_ADMIN").valor(horasAdmin).descripcion("Horas previas").build()); ConfiguracionCorteNomina conf = configCorteRepo.findByTipoEmpleado("ADMINISTRATIVO").orElse(new ConfiguracionCorteNomina()); conf.setTipoEmpleado("ADMINISTRATIVO"); conf.setDiaCorte(corteAdmin); if (horaCorteAdmin != null && !horaCorteAdmin.isEmpty()) conf.setHoraCorte(LocalTime.parse(horaCorteAdmin)); configCorteRepo.save(conf); return "redirect:/pantallas/torre-control?tab=nomina"; }
    @PostMapping("/admin/nomina/guardar-roles") public String guardarRolesNomina(@RequestParam("respSind") String respSind, @RequestParam("respAdmin") String respAdmin, @RequestParam("jefaNomina") String jefaNomina, @RequestParam("progMaster") String progMaster, @RequestParam("progBackup") String progBackup) { rolesNominaRepo.save(new ConfiguracionRolesNomina("RESPONSABLE_SIND", parseOptionalInteger(respSind))); rolesNominaRepo.save(new ConfiguracionRolesNomina("RESPONSABLE_ADMIN", parseOptionalInteger(respAdmin))); rolesNominaRepo.save(new ConfiguracionRolesNomina("JEFA_NOMINA", parseOptionalInteger(jefaNomina))); rolesNominaRepo.save(new ConfiguracionRolesNomina("PROGRAMADOR_MASTER", parseOptionalInteger(progMaster))); rolesNominaRepo.save(new ConfiguracionRolesNomina("PROGRAMADOR_RESPALDO", parseOptionalInteger(progBackup))); return "redirect:/pantallas/torre-control?tab=nomina"; }
    @PostMapping("/admin/nomina/guardar-espectadores")
    public String guardarEspectadoresEspeciales(
            @RequestParam("visorPrenomina") String visorPrenomina,
            @RequestParam("visorBiometrico") String visorBiometrico,
            @RequestParam("visorMetricas") String visorMetricas) {

        rolesNominaRepo.save(new ConfiguracionRolesNomina("VISOR_PRENOMINA", parseOptionalInteger(visorPrenomina)));
        rolesNominaRepo.save(new ConfiguracionRolesNomina("VISOR_BIOMETRICO", parseOptionalInteger(visorBiometrico)));
        rolesNominaRepo.save(new ConfiguracionRolesNomina("VISOR_METRICAS", parseOptionalInteger(visorMetricas)));

        log.info("Torre de Control: Permisos de Espectador Global actualizados exitosamente.");
        return "redirect:/pantallas/torre-control?tab=espectadores";
    }
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
    // =========================================================================
// ✨ CONTROL DEL SWITCH MAESTRO DE ESCALAMIENTO
// =========================================================================
    @PostMapping("/admin/configuracion/toggle-escalamiento")
    public String toggleEscalamiento(@RequestParam("habilitado") boolean habilitado,
                                     org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {

        // Convertimos el booleano al String oficial
        String nuevoValor = habilitado ? "TRUE" : "FALSE";

        // Buscamos la llave en la BD o la creamos si no existe
        ConfiguracionSistema config = configSistemaRepo.findById("ESCALAMIENTO_AUTOMATICO").orElse(null);

        if (config != null) {
            config.setValor(nuevoValor);
            configSistemaRepo.save(config);
        } else {
            configSistemaRepo.save(ConfiguracionSistema.builder()
                    .clave("ESCALAMIENTO_AUTOMATICO")
                    .valor(nuevoValor)
                    .descripcion("Switch Maestro de Escalamiento a Supervisor")
                    .build());
        }

        // Disparamos la alerta de colores en la Torre de Control
        if (habilitado) {
            log.info("Torre de Control: Robot de Escalamiento ENCENDIDO.");
            redirectAttributes.addFlashAttribute("mensajeExito", "¡Robot de Escalamiento a Supervisor ENCENDIDO! Las solicitudes ignoradas ahora subirán de nivel.");
        } else {
            log.info("Torre de Control: Robot de Escalamiento APAGADO.");
            redirectAttributes.addFlashAttribute("mensajeExito", "¡Escalamiento APAGADO! Ahora la Barredora Auto-Aprobará las solicitudes directamente a favor del operador.");
        }

        return "redirect:/pantallas/torre-control?tab=nomina";
    }
    @PostMapping("/admin/festivos/guardar")
    public String guardarFestivo(
            @RequestParam("fecha") LocalDate fecha,
            @RequestParam("descripcion") String descripcion,
            @RequestParam(value = "porLey", defaultValue = "NO") String porLey,
            @RequestParam(value = "porContrato", defaultValue = "NO") String porContrato) {

        diasFestivosRepository.save(DiasFestivos.builder()
                .fecha(fecha)
                .descripcion(descripcion.trim())
                .porLey(porLey.toUpperCase())
                .porContrato(porContrato.toUpperCase())
                .activo(true)
                .build());

        return "redirect:/pantallas/torre-control?tab=festivos";
    }
    @PostMapping("/admin/festivos/desactivar/{id}") public String desactivarFestivo(@PathVariable("id") Integer id) { diasFestivosRepository.findById(id).ifPresent(f -> { f.setActivo(false); diasFestivosRepository.save(f); }); return "redirect:/pantallas/torre-control?tab=festivos"; }
    @PostMapping("/admin/incidencias/guardar") public String guardarIncidencia(@RequestParam("codigo") String codigo, @RequestParam("descripcion") String descripcion, @RequestParam("aplicaA") String aplicaA) { tipoPermisoRepo.save(TipoPermiso.builder().codigo(codigo.toUpperCase().trim()).descripcion(descripcion.trim()).aplicaA(aplicaA.toUpperCase()).activo(true).build()); return "redirect:/pantallas/torre-control?tab=incidencias"; }
    @PostMapping("/admin/incidencias/desactivar/{id}") public String desactivarIncidencia(@PathVariable("id") Integer id) { tipoPermisoRepo.findById(id).ifPresent(p -> { p.setActivo(false); tipoPermisoRepo.save(p); }); return "redirect:/pantallas/torre-control?tab=incidencias"; }
    @PostMapping("/admin/incidencias/activar/{id}") public String activarIncidencia(@PathVariable("id") Integer id) { tipoPermisoRepo.findById(id).ifPresent(p -> { p.setActivo(true); tipoPermisoRepo.save(p); }); return "redirect:/pantallas/torre-control?tab=incidencias"; }
    @PostMapping("/admin/incidencias/actualizar-cobertura/{id}") public String actualizarCoberturaIncidencia(@PathVariable("id") Integer id, @RequestParam("aplicaA") String aplicaA) { tipoPermisoRepo.findById(id).ifPresent(p -> { p.setAplicaA(aplicaA.toUpperCase()); tipoPermisoRepo.save(p); }); return "redirect:/pantallas/torre-control?tab=incidencias"; }
    // ==========================================
    // SECCIÓN: TABULADOR DE VACACIONES (Dinámico)
    // ==========================================
    @PostMapping("/admin/tabulador/actualizar")
    public String actualizarTabuladorMasivo(
            @RequestParam("anios") List<Integer> anios,
            @RequestParam("dias") List<Integer> dias,
            @RequestParam("bonoAdmin") String bonoAdmin, // ✨ ATRAPAMOS EL BONO
            org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {

        try {
            for (int i = 0; i < anios.size(); i++) {
                tabuladorRepo.save(new TabuladorVacaciones(anios.get(i), dias.get(i)));
            }

            // ✨ GUARDAMOS EL BONO EN CONFIGURACIONES
            ConfiguracionSistema configBono = configSistemaRepo.findById("BONO_DIAS_ADMIN").orElse(null);
            if(configBono != null){
                configBono.setValor(bonoAdmin);
                configSistemaRepo.save(configBono);
            } else {
                configSistemaRepo.save(ConfiguracionSistema.builder().clave("BONO_DIAS_ADMIN").valor(bonoAdmin).descripcion("Bono extra de vacaciones para WC y BCI").build());
            }

            tabuladorCache.actualizarCache();

            redirectAttributes.addFlashAttribute("mensajeExito", "¡Tabulador Maestro actualizado correctamente! Los cálculos de saldos futuros usarán esta nueva base.");
            log.info("Torre de Control: Tabulador modificado en caliente.");
        } catch (Exception e) {
            log.error("Falla al actualizar tabulador: ", e);
            redirectAttributes.addFlashAttribute("mensajeError", "Error al procesar el tabulador. Intente nuevamente.");
        }

        return "redirect:/pantallas/torre-control?tab=tabulador";
    }
}
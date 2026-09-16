package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.model.AsistenciaBiometrico;
import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.repository.AsistenciaBiometricoRepository;
import com.hrms.vacaciones.repository.EmpleadoRepository;
import com.hrms.vacaciones.repository.SolicitudPermisoRepository;
import com.hrms.vacaciones.service.PrenominaAutorizadaImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import com.hrms.vacaciones.repository.SolicitudVacacionesRepository;
import com.hrms.vacaciones.model.SolicitudVacaciones;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@RestController
@RequestMapping("/api/prenomina")
@RequiredArgsConstructor
public class PrenominaRestController {

    private final AsistenciaBiometricoRepository asistenciaRepository;
    private final EmpleadoRepository empleadoRepository;
    private final SolicitudVacacionesRepository vacacionesRepository;
    private final com.hrms.vacaciones.repository.SolicitudPermisoRepository permisoRepository;
    private final com.hrms.vacaciones.repository.PrenominaAutorizadaRepository prenominaAutorizadaRepository;
    private final com.hrms.vacaciones.service.PrenominaService prenominaService;
    private final com.hrms.vacaciones.repository.ConfiguracionRolesNominaRepository rolesNominaRepository;

    // Método auxiliar privado para saber si el que consulta es de Nóminas (Ve toda la planta)
    private boolean esPerfilNomina(Integer jefeId) {
        if (jefeId == null) return false;
        return rolesNominaRepository.findAll().stream()
                .anyMatch(rol -> jefeId.equals(rol.getNumNominaAsignada()));
    }

    @GetMapping("/datos-semana")
    public ResponseEntity<?> obtenerDatosSemana(
            @RequestParam("inicio") String inicioStr,
            @RequestParam("fin") String finStr,
            @RequestParam("tipo") String tipoNomina,
            @RequestParam(value = "jefeId", required = false) Integer jefeId) {

        LocalDate inicio = LocalDate.parse(inicioStr);
        LocalDate fin = LocalDate.parse(finStr);
        boolean esNomina = esPerfilNomina(jefeId);

        List<Empleado> plantillaBase = empleadoRepository.findAll().stream()
                .filter(e -> tipoNomina.equalsIgnoreCase(e.getTipoEmpleado()) && "ACTIVO".equalsIgnoreCase(e.getEstatus()))
                .filter(e -> esNomina || perteneceAlJefe(e, jefeId))
                .toList();

        List<AsistenciaBiometrico> registros = asistenciaRepository.findByFechaReferenciaBetweenOrderByEmpleadoNominaAscFechaReferenciaAsc(inicio, fin).stream()
                .filter(r -> tipoNomina.equalsIgnoreCase(r.getEmpleado().getTipoEmpleado()))
                .filter(r -> esNomina || jefeId == null || jefeId.equals(r.getEmpleado().getNomina()) || jefeId.equals(r.getEmpleado().getJefeDirectoNomina()))
                .toList();

        List<com.hrms.vacaciones.model.SolicitudVacaciones> vacacionesSemanales = vacacionesRepository.findAll().stream()
                .filter(v -> tipoNomina.equalsIgnoreCase(v.getEmpleado().getTipoEmpleado()))
                .filter(v -> List.of("APROBADO", "APROBADA").contains(v.getEstatus().toUpperCase()))
                .filter(v -> !v.getFechaInicio().isAfter(fin) && !v.getFechaFin().isBefore(inicio))
                .filter(v -> esNomina || jefeId == null || jefeId.equals(v.getEmpleado().getNomina()) || jefeId.equals(v.getEmpleado().getJefeDirectoNomina()))
                .toList();

        List<com.hrms.vacaciones.model.SolicitudPermiso> permisosSemanales = permisoRepository.findAll().stream()
                .filter(p -> tipoNomina.equalsIgnoreCase(p.getEmpleado().getTipoEmpleado()))
                .filter(p -> "APROBADO".equalsIgnoreCase(p.getEstatus()))
                .filter(p -> p.getFechaIncidencia() != null && !p.getFechaIncidencia().isBefore(inicio) && !p.getFechaIncidencia().isAfter(fin))
                .filter(p -> esNomina || jefeId == null || jefeId.equals(p.getEmpleado().getNomina()) || jefeId.equals(p.getEmpleado().getJefeDirectoNomina()))
                .toList();

        List<Map<String, Object>> respuesta = agruparPorEmpleado(plantillaBase, registros, vacacionesSemanales, permisosSemanales, inicio);
        return ResponseEntity.ok(respuesta);
    }

    private List<Map<String, Object>> agruparPorEmpleado(
            List<Empleado> plantillaBase,
            List<AsistenciaBiometrico> registros,
            List<SolicitudVacaciones> vacacionesSemanales,
            List<com.hrms.vacaciones.model.SolicitudPermiso> permisosSemanales,
            LocalDate inicioSemana) {

        Map<Integer, Map<String, Object>> mapa = new HashMap<>();
        Map<Integer, String> catalogoTurnos = new HashMap<>();

        for (AsistenciaBiometrico r : registros) {
            catalogoTurnos.put(r.getEmpleado().getNomina(), r.getTurnoDominante());
        }
        for (SolicitudVacaciones v : vacacionesSemanales) {
            if (!catalogoTurnos.containsKey(v.getEmpleado().getNomina()) && v.getTurno() != null) {
                catalogoTurnos.put(v.getEmpleado().getNomina(), v.getTurno().getNombreTurno());
            }
        }

        // ✨ INICIALIZAR EL CASCARÓN CON TODA LA PLANTILLA BASE ACTIVA
        for (Empleado empObj : plantillaBase) {
            Integer nomina = empObj.getNomina();
            mapa.putIfAbsent(nomina, new HashMap<>());
            Map<String, Object> datosEmp = mapa.get(nomina);

            datosEmp.put("nomina", nomina);
            datosEmp.put("nombre", empObj.getNombreCompleto());
            datosEmp.put("cc", empObj.getCentroCosto() != null ? empObj.getCentroCosto().getId() : "");
            datosEmp.put("nombreCc", empObj.getCentroCosto() != null ? empObj.getCentroCosto().getNombre() : "");
            datosEmp.put("wc", empObj.getWorkCenter() != null ? empObj.getWorkCenter().getId() : "");
            datosEmp.put("nombreWc", empObj.getWorkCenter() != null ? empObj.getWorkCenter().getNombre() : "");

            String shiftNombre = "Sin asignacion";
            String supervisorNombre = "Sin asignacion";
            String apuNombre = "Sin asignacion";
            Integer wcId = empObj.getWorkCenter() != null ? empObj.getWorkCenter().getId() : null;

            if (wcId != null) {
                List<Empleado> shiftLeaders = empleadoRepository.findEmpleadosJefesPorWcId(wcId);
                if (shiftLeaders != null && !shiftLeaders.isEmpty()) {
                    shiftNombre = shiftLeaders.stream()
                            .map(Empleado::getNombreCompleto)
                            .filter(n -> n != null && !n.trim().isEmpty())
                            .map(nombre -> {
                                String primerNombre = nombre.trim().split("\\s+")[0];
                                return primerNombre.substring(0, 1).toUpperCase() + primerNombre.substring(1).toLowerCase();
                            })
                            .distinct()
                            .collect(Collectors.joining(" / "));

                    List<Empleado> supervisores = new ArrayList<>();
                    for (Empleado shift : shiftLeaders) {
                        Integer supNomina = shift.getJefeDirectoNomina();
                        if (supNomina != null) empleadoRepository.findById(supNomina).ifPresent(supervisores::add);
                    }

                    if (!supervisores.isEmpty()) {
                        supervisorNombre = supervisores.stream()
                                .map(Empleado::getNombreCompleto)
                                .filter(n -> n != null && !n.trim().isEmpty())
                                .map(nombre -> {
                                    String primerNombre = nombre.trim().split("\\s+")[0];
                                    return primerNombre.substring(0, 1).toUpperCase() + primerNombre.substring(1).toLowerCase();
                                })
                                .distinct()
                                .collect(Collectors.joining(" / "));

                        List<Empleado> apus = new ArrayList<>();
                        for (Empleado sup : supervisores) {
                            Integer apuNomina = sup.getJefeDirectoNomina();
                            if (apuNomina != null) empleadoRepository.findById(apuNomina).ifPresent(apus::add);
                        }

                        if (!apus.isEmpty()) {
                            apuNombre = apus.stream()
                                    .map(Empleado::getNombreCompleto)
                                    .filter(n -> n != null && !n.trim().isEmpty())
                                    .map(nombre -> {
                                        String primerNombre = nombre.trim().split("\\s+")[0];
                                        return primerNombre.substring(0, 1).toUpperCase() + primerNombre.substring(1).toLowerCase();
                                    })
                                    .distinct()
                                    .collect(Collectors.joining(" / "));
                        }
                    }
                }
            }

            datosEmp.put("shift", shiftNombre);
            datosEmp.put("supervisor", supervisorNombre);
            datosEmp.put("apu", apuNombre);
            datosEmp.put("turno", catalogoTurnos.getOrDefault(nomina, "S/T"));
            datosEmp.put("totalTE", 0.0);
            datosEmp.put("totalHrs", 0.0);

            String[] dias = {"LUN", "MAR", "MIE", "JUE", "VIE", "SAB", "DOM"};
            for (int i = 0; i < 7; i++) {
                String d = dias[i];
                LocalDate fechaDia = inicioSemana.plusDays(i);

                datosEmp.put(d, 0.0);
                datosEmp.put("te" + d, 0.0);
                datosEmp.put("deuda" + d, 0.0);
                datosEmp.put("inc" + d, "");

                boolean esVacacion = vacacionesSemanales.stream()
                        .filter(v -> v.getEmpleado().getNomina().equals(nomina))
                        .anyMatch(v -> !fechaDia.isBefore(v.getFechaInicio()) && !fechaDia.isAfter(v.getFechaFin()));
                datosEmp.put("vac" + d, esVacacion);
            }

            List<String> autorizadores = new ArrayList<>();
            List<String> detalleTxtList = new ArrayList<>();

            for (com.hrms.vacaciones.model.SolicitudPermiso p : permisosSemanales) {
                if (!p.getEmpleado().getNomina().equals(nomina)) continue;

                LocalDate fInc = p.getFechaIncidencia();
                int diaIdx = fInc.getDayOfWeek().getValue() - 1;
                String codigo = p.getTipoPermiso() != null ? p.getTipoPermiso().getCodigo() : "TXT";

                datosEmp.put("inc" + dias[diaIdx], codigo);

                if ("TXT".equalsIgnoreCase(codigo) && p.getDesglosesPago() != null) {
                    StringBuilder txtStr = new StringBuilder("Falta: " + fInc + " -> Pagos: ");
                    for (com.hrms.vacaciones.model.SolicitudTxtPago pago : p.getDesglosesPago()) {
                        LocalDate fPago = pago.getFechaPago();
                        txtStr.append(fPago).append(" (").append(pago.getHorasPago()).append("h) ");
                        if (!fPago.isBefore(inicioSemana) && !fPago.isAfter(inicioSemana.plusDays(6))) {
                            int pagoDiaIdx = fPago.getDayOfWeek().getValue() - 1;
                            datosEmp.put("deuda" + dias[pagoDiaIdx], pago.getHorasPago().doubleValue());
                        }
                    }
                    detalleTxtList.add(txtStr.toString());
                }

                Empleado autorizadorObj = p.getResueltoPor() != null ? p.getResueltoPor() : p.getEmpleado().getJefeDirecto();
                if (autorizadorObj != null && autorizadorObj.getNombreCompleto() != null) {
                    String[] partes = autorizadorObj.getNombreCompleto().trim().split("\\s+");
                    String corto = partes[0] + (partes.length > 1 ? " " + partes[1] : "");
                    if (!autorizadores.contains(corto)) autorizadores.add(corto);
                }
            }

            datosEmp.put("detalleTxt", String.join(" | ", detalleTxtList));
            datosEmp.put("quienAutoriza", String.join(" / ", autorizadores));
        }

        // ✨ VACIAR HORAS REALES DEL BIOMÉTRICO A LA MATRIZ
        for (AsistenciaBiometrico r : registros) {
            Map<String, Object> datosEmp = mapa.get(r.getEmpleado().getNomina());
            if (datosEmp == null) continue; // Protección por si no estaba en plantilla activa

            int diaSemana = r.getFechaReferencia().getDayOfWeek().getValue();
            String[] nombresDias = {"LUN", "MAR", "MIE", "JUE", "VIE", "SAB", "DOM"};
            String diaClave = nombresDias[diaSemana - 1];
            String teClave = "te" + diaClave;

            double horasBrutas = r.getHorasEfectivas() != null ? r.getHorasEfectivas() : 0.0;
            double horasRedondeadas = Math.floor(horasBrutas * 2) / 2.0;
            Number horasExistentes = (Number) datosEmp.getOrDefault(diaClave, 0.0);
            datosEmp.put(diaClave, horasExistentes.doubleValue() + horasRedondeadas);

            double teDiaBruto = r.getTiempoExtra() != null ? r.getTiempoExtra() : 0.0;
            double teDiaRedondeado = Math.floor(teDiaBruto * 2) / 2.0;
            Number teDiarioExistente = (Number) datosEmp.getOrDefault(teClave, 0.0);
            datosEmp.put(teClave, teDiarioExistente.doubleValue() + teDiaRedondeado);

            Number teTotalActual = (Number) datosEmp.getOrDefault("totalTE", 0.0);
            datosEmp.put("totalTE", teTotalActual.doubleValue() + teDiaRedondeado);

            Number totalHrsActual = (Number) datosEmp.getOrDefault("totalHrs", 0.0);
            datosEmp.put("totalHrs", totalHrsActual.doubleValue() + horasRedondeadas);
        }

        // ✨ CRUCE MATEMÁTICO TXT VS RELOJ: Deducción automática de la bolsa semanal
        for (Map<String, Object> emp : mapa.values()) {
            double totalTeNeto = 0.0;
            String[] todosLosDias = {"LUN", "MAR", "MIE", "JUE", "VIE", "SAB", "DOM"};

            for (String d : todosLosDias) {
                Number teDiario = (Number) emp.getOrDefault("te" + d, 0.0);
                Number deudaDiaria = (Number) emp.getOrDefault("deuda" + d, 0.0);

                double balanceDia = teDiario.doubleValue() - deudaDiaria.doubleValue();

                // Si cubrió su deuda y además le sobró tiempo, eso es lo único que se va a la bolsa de pago.
                // (Si quedó en negativo, el frontend ya se encarga de evidenciar la deuda viva).
                if (balanceDia > 0) {
                    totalTeNeto += balanceDia;
                }
            }
            // Sobreescribimos el total bruto con el total pagable real
            emp.put("totalTE", totalTeNeto);
        }

        // ✨ FILTRO DE FALTAS
        for (Map<String, Object> emp : mapa.values()) {
            int faltas = 0;
            String turnoDom = emp.get("turno") != null ? emp.get("turno").toString().toUpperCase() : "";
            String[] todosLosDias = {"LUN", "MAR", "MIE", "JUE", "VIE", "SAB", "DOM"};

            for (String d : todosLosDias) {
                Number horas = (Number) emp.getOrDefault(d, 0.0);
                Boolean estaDeVacaciones = (Boolean) emp.getOrDefault("vac" + d, false);

                if ((horas == null || horas.doubleValue() == 0.0) && !estaDeVacaciones) {
                    boolean esDescanso = false;
                    if (d.equals("DOM")) {
                        esDescanso = true;
                    } else if (d.equals("SAB") && (turnoDom.contains("MIXTO") || turnoDom.contains("3RO") || turnoDom.contains("12HRS"))) {
                        esDescanso = true;
                    }

                    if (!esDescanso) {
                        faltas++;
                    }
                }
            }
            emp.put("totalFaltas", faltas);
        }

        return new ArrayList<>(mapa.values());
    }

    @GetMapping("/autorizada")
    public ResponseEntity<?> obtenerPrenominaAutorizada(
            @RequestParam("semana") String semana,
            @RequestParam("tipo") String tipoNomina,
            @RequestParam(value = "jefeId", required = false) Integer jefeId) {

        boolean esNomina = esPerfilNomina(jefeId);

        List<com.hrms.vacaciones.model.PrenominaAutorizada> registros = prenominaAutorizadaRepository.findBySemanaContableOrderByNominaAsc(semana)
                .stream()
                .filter(r -> r.getNomina() != null)
                .filter(r -> empleadoRepository.findById(r.getNomina())
                        .map(e -> tipoNomina.equalsIgnoreCase(e.getTipoEmpleado()) &&
                                (esNomina || perteneceAlJefe(e, jefeId))) // ✨ APLICAMOS LA HERENCIA AQUÍ
                        .orElse(false))
                .toList();

        return ResponseEntity.ok(registros);
    }

    @GetMapping("/auditoria-reloj")
    public ResponseEntity<?> auditarReloj(
            @RequestParam("inicio") String inicioStr,
            @RequestParam("fin") String finStr,
            @RequestParam("tipo") String tipoNomina,
            @RequestParam(value = "jefeId", required = false) Integer jefeId) {

        LocalDate inicio = LocalDate.parse(inicioStr);
        LocalDate fin = LocalDate.parse(finStr);
        boolean esNomina = esPerfilNomina(jefeId);

        List<AsistenciaBiometrico> asistencias = asistenciaRepository
                .findByFechaReferenciaBetweenOrderByEmpleadoNominaAscFechaReferenciaAsc(inicio, fin).stream()
                .filter(a -> tipoNomina.equalsIgnoreCase(a.getEmpleado().getTipoEmpleado()))
                .filter(a -> esNomina || perteneceAlJefe(a.getEmpleado(), jefeId))
                .toList();

        List<Map<String, Object>> respuesta = new ArrayList<>();

        for (AsistenciaBiometrico a : asistencias) {
            Map<String, Object> dto = new HashMap<>();
            dto.put("nomina", a.getEmpleado().getNomina());
            dto.put("nombre", a.getEmpleado().getNombreCompleto());
            dto.put("fecha", a.getFechaReferencia().toString());
            dto.put("dia", a.getFechaReferencia().getDayOfWeek().getValue());

            String entCruda = (a.getEntrada() != null && a.getEntrada().getHoraOriginal() != null) ? a.getEntrada().getHoraOriginal() : "--:--";
            String salCruda = (a.getSalida() != null && a.getSalida().getHoraOriginal() != null) ? a.getSalida().getHoraOriginal() : "--:--";

            dto.put("entradaCruda", entCruda);
            dto.put("salidaCruda", salCruda);
            dto.put("horasEfectivas", a.getHorasEfectivas());
            dto.put("estatus", a.getEstatus());

            respuesta.add(dto);
        }

        return ResponseEntity.ok(respuesta);
    }

    @GetMapping("/exportar")
    public void exportarPrenominaCsv(
            @RequestParam(value = "semana", required = false) Integer semana,
            @RequestParam(value = "anio", required = false) Integer anio,
            @RequestParam(value = "inicio", required = false) String inicioStr,
            @RequestParam(value = "fin", required = false) String finStr,
            @RequestParam("tipo") String tipo,
            @RequestParam("tipoNomina") String tipoNomina,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {

        LocalDate inicio = null;
        LocalDate fin = null;
        String semanaContable = "";

        if (inicioStr != null && !inicioStr.isEmpty() && finStr != null && !finStr.isEmpty()) {
            inicio = LocalDate.parse(inicioStr);
            fin = LocalDate.parse(finStr);
            int week = inicio.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
            semanaContable = String.valueOf(week);
        } else if (semana != null && anio != null) {
            java.time.temporal.WeekFields weekFields = java.time.temporal.WeekFields.ISO;
            inicio = LocalDate.of(anio, 1, 1)
                    .with(weekFields.weekOfYear(), semana)
                    .with(weekFields.dayOfWeek(), 1);
            fin = inicio.plusDays(6);
            semanaContable = String.valueOf(semana);
        }

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"Reporte_" + tipo.toUpperCase() + "_" + LocalDate.now() + ".csv\"");

        try (java.io.PrintWriter writer = response.getWriter()) {
            // BOM UTF-8 para que Excel lea los acentos correctamente
            writer.write('\uFEFF');

            if ("prenomina".equals(tipo)) {
                // Título superior con el rango y semana
                writer.println("REPORTE DE PRENOMINA: DEL " + inicio + " AL " + fin + " (SEMANA " + semanaContable + ")");

                // Armado dinámico de las cabeceras
                StringBuilder header = new StringBuilder("NOMINA,NOMBRE,WC,NOMBRE_WC,CC,NOMBRE_CC");
                List<LocalDate> fechas = inicio.datesUntil(fin.plusDays(1)).toList();
                for (LocalDate fecha : fechas) {
                    header.append(",").append(fecha.toString());
                }
                header.append(",HORAS_EFECTIVAS");
                writer.println(header.toString());

                List<AsistenciaBiometrico> registros = asistenciaRepository
                        .findByFechaReferenciaBetweenOrderByEmpleadoNominaAscFechaReferenciaAsc(inicio, fin).stream()
                        .filter(r -> tipoNomina.equalsIgnoreCase(r.getEmpleado().getTipoEmpleado()))
                        .toList();

                List<com.hrms.vacaciones.model.PrenominaAutorizada> autorizadas = prenominaAutorizadaRepository.findBySemanaContableOrderByNominaAsc(semanaContable);
                Map<Integer, String> turnosAutorizados = autorizadas.stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.hrms.vacaciones.model.PrenominaAutorizada::getNomina,
                                a -> a.getTurno() != null ? a.getTurno() : "S/T",
                                (t1, t2) -> t1));

                Map<Integer, Map<String, Object>> mapEmpleados = new LinkedHashMap<>();

                // Agrupación y suma de horas procesadas
                for (AsistenciaBiometrico r : registros) {
                    Integer nom = r.getEmpleado().getNomina();
                    mapEmpleados.putIfAbsent(nom, new HashMap<>());
                    Map<String, Object> empData = mapEmpleados.get(nom);

                    if (!empData.containsKey("base")) {
                        empData.put("nomina", nom);
                        empData.put("nombre", r.getEmpleado().getNombreCompleto());
                        empData.put("wc", r.getEmpleado().getWorkCenter() != null ? r.getEmpleado().getWorkCenter().getId() : "");
                        empData.put("nombreWc", r.getEmpleado().getWorkCenter() != null ? r.getEmpleado().getWorkCenter().getNombre() : "");
                        empData.put("cc", r.getEmpleado().getCentroCosto() != null ? r.getEmpleado().getCentroCosto().getId() : "");
                        empData.put("nombreCc", r.getEmpleado().getCentroCosto() != null ? r.getEmpleado().getCentroCosto().getNombre() : "");

                        empData.put("base", true);

                        for (LocalDate f : fechas) {
                            empData.put(f.toString(), 0.0);
                        }
                    }

                    double horasBrutas = r.getHorasEfectivas() != null ? r.getHorasEfectivas() : 0.0;
                    double horasRedondeadas = Math.floor(horasBrutas * 2) / 2.0; // Solo horas procesadas
                    String fechaStr = r.getFechaReferencia().toString();

                    if (empData.containsKey(fechaStr)) {
                        double actual = (double) empData.get(fechaStr);
                        empData.put(fechaStr, actual + horasRedondeadas);
                    }
                }

                // Inyección en el CSV
                for (Map<String, Object> empData : mapEmpleados.values()) {
                    Integer nom = (Integer) empData.get("nomina");

                    StringBuilder row = new StringBuilder();
                    row.append(nom).append(",")
                            .append("\"").append(empData.get("nombre")).append("\",")
                            .append("\"").append(empData.get("wc")).append("\",")
                            .append("\"").append(empData.get("nombreWc")).append("\",")
                            .append("\"").append(empData.get("cc")).append("\",")
                            .append("\"").append(empData.get("nombreCc")).append("\"");

                    double totalHoras = 0.0;
                    for (LocalDate fecha : fechas) {
                        double horasDia = (double) empData.get(fecha.toString());
                        totalHoras += horasDia;
                        row.append(",").append(String.format("%.2f", horasDia));
                    }
                    row.append(",").append(String.format("%.2f", totalHoras));

                    writer.println(row.toString());
                }
            } else {
                writer.println("NOMINA,NOMBRE,RECUPERACION,OBSERVACIONES");
            }
        }
    }

    @GetMapping("/individual")
    public ResponseEntity<?> obtenerMiPrenominaHibrida(
            @RequestParam("semana") String semana,
            jakarta.servlet.http.HttpSession session) {

        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) return ResponseEntity.status(401).build();
        Integer miNomina = Integer.parseInt(logueadoObj.toString());

        // 1. Deducir las fechas reales de la semana solicitada (Año Actual)
        int anioActual = LocalDate.now().getYear();
        java.time.temporal.WeekFields weekFields = java.time.temporal.WeekFields.ISO;
        LocalDate inicio = LocalDate.of(anioActual, 1, 1)
                .with(weekFields.weekOfYear(), Integer.parseInt(semana))
                .with(weekFields.dayOfWeek(), 1);
        LocalDate fin = inicio.plusDays(6);

        // 2. Extraer la radiografía en vivo (Biométrico + Permisos)
        List<Empleado> empList = empleadoRepository.findById(miNomina).stream().toList();
        if(empList.isEmpty()) return ResponseEntity.notFound().build();

        String tipoEmpleado = empList.get(0).getTipoEmpleado() != null ? empList.get(0).getTipoEmpleado() : "SINDICALIZADO";

        List<AsistenciaBiometrico> registros = asistenciaRepository
                .findByFechaReferenciaBetweenOrderByEmpleadoNominaAscFechaReferenciaAsc(inicio, fin).stream()
                .filter(r -> r.getEmpleado().getNomina().equals(miNomina)).toList();

        List<com.hrms.vacaciones.model.SolicitudVacaciones> vacs = vacacionesRepository.findAll().stream()
                .filter(v -> v.getEmpleado().getNomina().equals(miNomina))
                .filter(v -> List.of("APROBADO", "APROBADA").contains(v.getEstatus().toUpperCase()))
                .filter(v -> !v.getFechaInicio().isAfter(fin) && !v.getFechaFin().isBefore(inicio)).toList();

        List<com.hrms.vacaciones.model.SolicitudPermiso> perms = permisoRepository.findAll().stream()
                .filter(p -> p.getEmpleado().getNomina().equals(miNomina))
                .filter(p -> "APROBADO".equalsIgnoreCase(p.getEstatus()))
                .filter(p -> p.getFechaIncidencia() != null && !p.getFechaIncidencia().isBefore(inicio) && !p.getFechaIncidencia().isAfter(fin)).toList();

        List<Map<String, Object>> crudoList = agruparPorEmpleado(empList, registros, vacs, perms, inicio);
        Map<String, Object> crudo = crudoList.isEmpty() ? new HashMap<>() : crudoList.get(0);

        // 3. Consultar la tabla de Autorizados (Excel Final)
        com.hrms.vacaciones.model.PrenominaAutorizada auth = prenominaAutorizadaRepository
                .findByNominaAndSemanaContable(miNomina, semana).orElse(null);

        // 4. Armar el Frankestein (Si hay Autorizada manda ella, si no, manda el Crudo)
        Map<String, Object> hibrido = new HashMap<>();
        hibrido.put("nomina", miNomina);

        hibrido.put("turno", auth != null && auth.getTurno() != null ? auth.getTurno() : crudo.getOrDefault("turno", "S/T"));
        hibrido.put("totalFaltas", auth != null && auth.getTotalFaltas() != null ? auth.getTotalFaltas() : crudo.getOrDefault("totalFaltas", 0));
        hibrido.put("totalTe", auth != null && auth.getTotalTe() != null ? auth.getTotalTe() : crudo.getOrDefault("totalTE", 0.0));

        // ✨ CORRECCIÓN: Mostrar ÚNICAMENTE las observaciones de la matriz Autorizada (Excel RH)
        String obsAuth = (auth != null && auth.getObservaciones() != null && !auth.getObservaciones().trim().isEmpty())
                ? auth.getObservaciones()
                : "Sin observaciones.";
        hibrido.put("observaciones", obsAuth);

        String[] diasUpper = {"LUN", "MAR", "MIE", "JUE", "VIE", "SAB", "DOM"};
        String[] diasCamel = {"Lun", "Mar", "Mie", "Jue", "Vie", "Sab", "Dom"};

        for (int i = 0; i < 7; i++) {
            String dUpper = diasUpper[i];
            String dCamel = diasCamel[i];

            // Inyectar Asistencia
            String asisFinal = auth != null ? obtenerAsisAuth(auth, i) : null;
            if (asisFinal == null || asisFinal.trim().isEmpty() || asisFinal.equals("-")) {
                Number horasCrudo = (Number) crudo.getOrDefault(dUpper, 0.0);
                Boolean esVacacion = (Boolean) crudo.getOrDefault("vac" + dUpper, false);
                String incidencia = (String) crudo.getOrDefault("inc" + dUpper, "");

                if (esVacacion) asisFinal = "V";
                else if (!incidencia.isEmpty()) asisFinal = incidencia;
                else if (horasCrudo.doubleValue() > 0) asisFinal = "A";
                else {
                    boolean esDescanso = dUpper.equals("DOM") || (dUpper.equals("SAB") && crudo.getOrDefault("turno", "").toString().toUpperCase().matches(".*(MIXTO|3RO|12HRS).*"));
                    asisFinal = esDescanso ? "D" : "F";
                }
            }
            hibrido.put("asis" + dCamel, asisFinal);

            // Inyectar Tiempo Extra Neto
            Double teFinal = auth != null ? obtenerTeAuth(auth, i) : null;
            if (teFinal == null) {
                Number teCrudo = (Number) crudo.getOrDefault("te" + dUpper, 0.0);
                Number deuda = (Number) crudo.getOrDefault("deuda" + dUpper, 0.0);
                double balance = teCrudo.doubleValue() - deuda.doubleValue();
                teFinal = balance > 0 ? (Math.floor(balance * 2) / 2.0) : 0.0;
            }
            hibrido.put("te" + dCamel, teFinal);
        }

        return ResponseEntity.ok(hibrido);
    }

    private String obtenerAsisAuth(com.hrms.vacaciones.model.PrenominaAutorizada auth, int dia) {
        switch(dia) {
            case 0: return auth.getAsisLun(); case 1: return auth.getAsisMar();
            case 2: return auth.getAsisMie(); case 3: return auth.getAsisJue();
            case 4: return auth.getAsisVie(); case 5: return auth.getAsisSab();
            case 6: return auth.getAsisDom(); default: return null;
        }
    }

    private Double obtenerTeAuth(com.hrms.vacaciones.model.PrenominaAutorizada auth, int dia) {
        switch(dia) {
            case 0: return auth.getTeLun(); case 1: return auth.getTeMar();
            case 2: return auth.getTeMie(); case 3: return auth.getTeJue();
            case 4: return auth.getTeVie(); case 5: return auth.getTeSab();
            case 6: return auth.getTeDom(); default: return null;
        }
    }

    private boolean perteneceAlJefe(Empleado e, Integer jefeId) {
        if (jefeId == null || jefeId.equals(e.getNomina())) return true;

        // 1. Relación Directa (Fallback por si algún administrativo/oficina sí lo tiene mapeado directo)
        if (jefeId.equals(e.getJefeDirectoNomina())) return true;

        // 2. Mapeo por Cadena de Mando de Work Center (La regla real de piso en la Planta)
        if (e.getWorkCenter() != null) {
            List<Empleado> shiftLeaders = empleadoRepository.findEmpleadosJefesPorWcId(e.getWorkCenter().getId());

            if (shiftLeaders != null) {
                for (Empleado shift : shiftLeaders) {
                    // Nivel 1: ¿El jefe que consulta es Shift Leader de la línea del operador?
                    if (jefeId.equals(shift.getNomina())) return true;

                    // Nivel 2: ¿El jefe que consulta es el Supervisor (jefe del Shift Leader)?
                    if (jefeId.equals(shift.getJefeDirectoNomina())) return true;

                    // Nivel 3: ¿El jefe que consulta es el APU (jefe del Supervisor)?
                    if (shift.getJefeDirectoNomina() != null) {
                        Empleado supervisor = empleadoRepository.findById(shift.getJefeDirectoNomina()).orElse(null);
                        if (supervisor != null && jefeId.equals(supervisor.getJefeDirectoNomina())) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }
}
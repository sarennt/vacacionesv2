package com.hrms.vacaciones.service;

import com.hrms.vacaciones.model.CentroCosto;
import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.model.WorkCenter;
import com.hrms.vacaciones.model.DiasFestivos;
import com.hrms.vacaciones.repository.CentroCostoRepository;
import com.hrms.vacaciones.repository.EmpleadoRepository;
import com.hrms.vacaciones.repository.WorkCenterRepository;
import com.hrms.vacaciones.repository.DiasFestivosRepository;
import com.hrms.vacaciones.repository.AuditoriaSaldoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private final CentroCostoRepository centroCostoRepository;
    private final WorkCenterRepository workCenterRepository;
    private final EmpleadoRepository empleadoRepository;
    private final DiasFestivosRepository diasFestivosRepository;
    private final AuditoriaSaldoRepository auditoriaSaldoRepository;

    private void validarNombreArchivo(MultipartFile file, String patronEsperado) {
        String nombreOriginal = file.getOriginalFilename();
        if (nombreOriginal == null || !nombreOriginal.toLowerCase().contains(patronEsperado.toLowerCase())) {
            throw new RuntimeException("Error: Archivo incorrecto. Se esperaba un nombre que contenga '" + patronEsperado + "'.");
        }
    }

    private void validarCabecerasEsperadas(Sheet sheet, String[] columnasEsperadas) {
        Row rowZero = sheet.getRow(0);
        if (rowZero == null) {
            throw new RuntimeException("Error: El archivo Excel no contiene cabeceras en la fila 1.");
        }
        for (int i = 0; i < columnasEsperadas.length; i++) {
            Cell cell = rowZero.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) {
                throw new RuntimeException("Error: Falta la columna obligatoria '" + columnasEsperadas[i] + "' en la posición " + (i + 1));
            }
            String cabeceraReal = cell.getStringCellValue().trim();
            if (!cabeceraReal.equalsIgnoreCase(columnasEsperadas[i].trim())) {
                throw new RuntimeException("Estructura rota. La columna " + (i + 1) + " debe llamarse '" + columnasEsperadas[i] + "' (Se leyó: '" + cabeceraReal + "').");
            }
        }
    }

    // --- 1. IMPORTAR MAESTRO DE CC Y WC ---
    @Transactional
    public void importarCatalogo(MultipartFile file) {
        validarNombreArchivo(file, "CATALOGO DE WC Y CC");
        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);

            Iterator<Row> rowIterator = sheet.iterator();
            if (rowIterator.hasNext()) rowIterator.next();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();

                Integer ccId = getIntegerValue(row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                String ccName = getStringValue(row.getCell(1, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                Integer wcId = getIntegerValue(row.getCell(2, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                String wcName = getStringValue(row.getCell(3, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));

                if (ccId == null) continue;

                CentroCosto cc = centroCostoRepository.findById(ccId).orElseGet(() -> new CentroCosto());
                cc.setId(ccId);
                cc.setNombre(ccName != null ? ccName.trim() : "CC " + ccId);
                centroCostoRepository.save(cc);

                if (wcId != null) {
                    WorkCenter wc = workCenterRepository.findById(wcId).orElseGet(() -> new WorkCenter());
                    wc.setId(wcId);
                    wc.setNombre(wcName != null ? wcName.trim() : "WC " + wcId);
                    wc.setCentroCosto(cc);
                    workCenterRepository.save(wc);
                }
            }
            log.info("✅ Torre de Control: Catálogo maestro indexado con orden seguro (CC en Columna A).");
        } catch (Exception e) {
            throw new RuntimeException("Falla en catálogos: " + e.getMessage(), e);
        }
    }

    // --- 2. IMPORTAR EMPLEADOS ---
    @Transactional
    public void importarEmpleados(MultipartFile file) {
        validarNombreArchivo(file, "ACTUALIZACION PLANTILLA");
        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            validarCabecerasEsperadas(sheet, new String[]{
                    "Nomina", "TAG", "First last name", "Second last name", "Name", "antigüedad", "contrato", "CC", "WC", "PUESTO", "Tipo de empleado"
            });

            Iterator<Row> rowIterator = sheet.iterator();
            if (rowIterator.hasNext()) rowIterator.next();

            // ✨ PASO 1: Crear la memoria temporal para el escáner de sobrevivientes
            java.util.List<Integer> nominasEnExcel = new java.util.ArrayList<>();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                Integer nominaId = getIntegerValue(row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                if (nominaId == null || nominaId == 0) continue;

                // ✨ PASO 2: Registrar que este empleado SÍ vino vivo en el Excel
                nominasEnExcel.add(nominaId);

                Empleado empleado = empleadoRepository.findById(nominaId).orElseGet(() -> {
                    Empleado newEmp = new Empleado();
                    newEmp.setNomina(nominaId);
                    return newEmp;
                });

                // ... (AQUÍ SE QUEDA EXACTAMENTE IGUAL TODO TU CÓDIGO DONDE GUARDAS NOMBRE, FECHAS Y CENTROS DE COSTO) ...

                // ✨ NUEVO: Leemos la columna "Tipo de empleado" (Índice 10) y la traducimos
                String tipoCrudo = getStringValue(row.getCell(10, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                empleado.setTipoEmpleado(normalizarTipoEmpleado(tipoCrudo));

                empleado.setEstatus("ACTIVO");
                empleadoRepository.save(empleado);
            }

            // ✨ PASO 3: LA GUILLOTINA DE BAJAS (Cruce contra PostgreSQL)
            // Una vez que terminó de leer el Excel, cruzamos contra la base de datos
            java.util.List<Empleado> todosLosEmpleados = empleadoRepository.findAll();
            for (Empleado empDb : todosLosEmpleados) {
                // Si está ACTIVO en la BD, pero NO lo anotamos en la lista del Excel... ¡Cuello!
                if ("ACTIVO".equalsIgnoreCase(empDb.getEstatus()) && !nominasEnExcel.contains(empDb.getNomina())) {
                    empDb.setEstatus("BAJA");
                    // Desvinculamos sus líneas y jefes para que no haga basura en las métricas de las Jefaturas
                    empDb.setWorkCenter(null);
                    empDb.setCentroCosto(null);
                    empDb.setJefeDirectoNomina(null);
                    empDb.setJefeDirecto(null);
                    empleadoRepository.save(empDb);
                    log.info("🔻 GUILLOTINA ACTIVADA: Empleado {} dado de BAJA por omisión en Excel.", empDb.getNomina());
                }
            }

            log.info("✅ Torre de Control: Plantilla indexada al centavo con nombres y apellidos separados.");
        } catch (Exception e) {
            throw new RuntimeException("Falla en plantilla personal: " + e.getMessage(), e);
        }
    }

    // --- 3. IMPORTAR ORGANIGRAMA DE JEFES ---
    @Transactional
    public void importarJefes(MultipartFile file) {
        validarNombreArchivo(file, "Plantilla Jefes");
        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            validarCabecerasEsperadas(sheet, new String[]{
                    "Nomina", "Nombre del empleado", "Rol Jerarquico", "Nomina Jefe Directo", "CC a cargo", "WC a cargo"
            });

            java.util.Map<Integer, Integer> mapaJefeToCc = new java.util.HashMap<>();

            Iterator<Row> rowIterator = sheet.iterator();
            if (rowIterator.hasNext()) rowIterator.next();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                Integer nominaId = getIntegerValue(row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                if (nominaId == null || nominaId == 0) continue;

                if (!empleadoRepository.existsById(nominaId)) {
                    Empleado cascaron = new Empleado();
                    cascaron.setNomina(nominaId);
                    cascaron.setEstatus("ACTIVO");
                    empleadoRepository.save(cascaron);
                }

                Integer ccACargo = getIntegerValue(row.getCell(4, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                if (ccACargo != null) {
                    mapaJefeToCc.put(nominaId, ccACargo);
                }
            }

            rowIterator = sheet.iterator();
            if (rowIterator.hasNext()) rowIterator.next();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                Integer nominaId = getIntegerValue(row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                if (nominaId == null) continue;

                Empleado empleado = empleadoRepository.findById(nominaId).orElseThrow();

                String rol = getStringValue(row.getCell(2, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                if (rol != null) empleado.setRolJerarquico(rol.trim());

                Integer jefeNominaId = getIntegerValue(row.getCell(3, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                empleado.setJefeDirectoNomina(jefeNominaId);
                if (jefeNominaId != null) {
                    if (!empleadoRepository.existsById(jefeNominaId)) {
                        Empleado jCascaron = new Empleado();
                        jCascaron.setNomina(jefeNominaId);
                        jCascaron.setEstatus("ACTIVO");
                        empleadoRepository.save(jCascaron);
                    }
                    empleadoRepository.findById(jefeNominaId).ifPresent(empleado::setJefeDirecto);
                }

                Integer ccACargo = getIntegerValue(row.getCell(4, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                CentroCosto rowCc = null;
                if (ccACargo != null) {
                    final Integer finalCc = ccACargo;
                    rowCc = centroCostoRepository.findById(ccACargo).orElseGet(() -> {
                        CentroCosto newCc = new CentroCosto();
                        newCc.setId(finalCc);
                        newCc.setNombre("CC " + finalCc);
                        return centroCostoRepository.save(newCc);
                    });

                    if (empleado.getCentrosCostoACargo() == null) empleado.setCentrosCostoACargo(new ArrayList<>());
                    if (!empleado.getCentrosCostoACargo().contains(rowCc)) {
                        empleado.getCentrosCostoACargo().add(rowCc);
                    }
                }

                Integer wcACargo = getIntegerValue(row.getCell(5, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                if (wcACargo != null) {
                    final Integer finalWc = wcACargo;
                    final CentroCosto ccFilaDirecta = rowCc;
                    final Integer idJefeSuperior = jefeNominaId;

                    WorkCenter wc = workCenterRepository.findById(finalWc).orElseGet(() -> {
                        WorkCenter newWc = new WorkCenter();
                        newWc.setId(finalWc);
                        newWc.setNombre("WC " + finalWc);

                        CentroCosto ccHeredado = ccFilaDirecta;

                        if (ccHeredado == null && idJefeSuperior != null) {
                            Integer ccIdDelJefe = mapaJefeToCc.get(idJefeSuperior);
                            if (ccIdDelJefe != null) {
                                ccHeredado = centroCostoRepository.findById(ccIdDelJefe).orElse(null);
                            }
                        }

                        if (ccHeredado == null) {
                            Integer ccIdPropio = mapaJefeToCc.get(nominaId);
                            if (ccIdPropio != null) {
                                ccHeredado = centroCostoRepository.findById(ccIdPropio).orElse(null);
                            }
                        }

                        if (ccHeredado == null) {
                            throw new RuntimeException("Estructura rota: El WC " + finalWc +
                                    " asignado al Shift Leader " + nominaId +
                                    " no se pudo dar de alta porque su Supervisor [" + idJefeSuperior +
                                    "] no tiene ningún Centro de Costos declarado en la plantilla.");
                        }

                        newWc.setCentroCosto(ccHeredado);
                        log.info("✨ Autoprovisionamiento: Se creó el WC {} asignándole el CC {} de su Supervisor", finalWc, ccHeredado.getId());
                        return workCenterRepository.save(newWc);
                    });

                    if (empleado.getWorkCentersACargo() == null) empleado.setWorkCentersACargo(new ArrayList<>());
                    if (!empleado.getWorkCentersACargo().contains(wc)) {
                        empleado.getWorkCentersACargo().add(wc);
                        log.info("✅ Tabla jefes_work_centers: Vinculando línea {} al Shift Leader {}", finalWc, nominaId);
                    }
                }
                empleadoRepository.save(empleado);
            }
            log.info("🧠 Organigrama relacional completado con éxito mapeando los WC a los CC de las jefaturas.");
        } catch (Exception e) {
            throw new RuntimeException("Falla en jerarquías de jefes: " + e.getMessage(), e);
        }
    }

    // --- 4. CALENDARIO DE DÍAS FESTIVOS ---
    @Transactional
    public void importarFestivosMasivos(MultipartFile file) {
        validarNombreArchivo(file, "CATALOGO DE DIAS FESTIVOS");
        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            validarCabecerasEsperadas(sheet, new String[]{"FECHA", "DESCRIPCION", "POR LEY", "POR CONTRATO"});

            Iterator<Row> rowIterator = sheet.iterator();
            if (rowIterator.hasNext()) rowIterator.next();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                LocalDate fechaFestivo = getLocalDateValue(row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                String desc = getStringValue(row.getCell(1, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                String porLey = getStringValue(row.getCell(2, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                String porContrato = getStringValue(row.getCell(3, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));

                if (fechaFestivo == null || desc == null) continue;

                DiasFestivos festivo = DiasFestivos.builder()
                        .fecha(fechaFestivo)
                        .descripcion(desc.trim())
                        .porLey(porLey != null ? porLey.trim().toUpperCase() : "NO")
                        .porContrato(porContrato != null ? porContrato.trim().toUpperCase() : "NO")
                        .activo(true)
                        .build();

                diasFestivosRepository.save(festivo);
            }
        } catch (Exception e) {
            throw new RuntimeException("Falla en días festivos: " + e.getMessage(), e);
        }
    }

    // --- 5. SEMILLERO CONTABLE: INICIALIZACIÓN DE SALDOS ---
    @Transactional
    public void importarSaldosIniciales(MultipartFile file) {
        validarNombreArchivo(file, "SALDOS INICIALES-MIGRACIONES");
        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            validarCabecerasEsperadas(sheet, new String[]{
                    "Nomina", "TAG", "First last name", "Second last name", "Name", "antigüedad", "contrato", "CC", "WC", "PUESTO", "Tipo de empleado", "SALDO DEVENGADO"
            });

            Iterator<Row> rowIterator = sheet.iterator();
            if (rowIterator.hasNext()) rowIterator.next();

            int actualizados = 0;

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();

                Integer nominaId = getIntegerValue(row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                Double saldoDevengado = getNumericValue(row.getCell(11, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));

                if (nominaId == null) {
                    Cell celdaNominaCruda = row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    String valorCrudo = getStringValue(celdaNominaCruda);
                    if (valorCrudo != null && !valorCrudo.trim().isEmpty()) {
                        log.error("❌ ERROR CRÍTICO Semillero: La celda de nómina en la fila {} no es un número válido. Valor crudo: [{}]", row.getRowNum(), valorCrudo);
                    }
                    continue;
                }

                if (saldoDevengado == null) continue;

                Empleado emp = empleadoRepository.findById(nominaId).orElse(null);
                if (emp != null) {
                    emp.setSaldoVacacionesActual(BigDecimal.valueOf(saldoDevengado));

                    // ✨ NUEVO: Actualizamos y traducimos su esquema operativo desde el Semillero (Índice 10)
                    String tipoCrudo = getStringValue(row.getCell(10, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                    emp.setTipoEmpleado(normalizarTipoEmpleado(tipoCrudo));

                    empleadoRepository.save(emp);

                    if (emp.getFechaIngreso() != null) {
                        LocalDate hoy = LocalDate.now();
                        int anioActual = hoy.getYear();

                        LocalDate aniversarioEsteAnio = emp.getFechaIngreso().withYear(anioActual);

                        if (!aniversarioEsteAnio.isAfter(hoy)) {
                            String tagAniversario = "[ANIVERSARIO #" + anioActual + "]";

                            java.util.List<com.hrms.vacaciones.model.AuditoriaSaldo> historial =
                                    auditoriaSaldoRepository.findByNominaEmpleadoOrderByFechaMovimientoDesc(nominaId);

                            boolean yaTieneCandado = false;
                            if (historial != null) {
                                for (com.hrms.vacaciones.model.AuditoriaSaldo audit : historial) {
                                    if (audit.getComentarioJustificacion() != null &&
                                            audit.getComentarioJustificacion().contains(tagAniversario)) {
                                        yaTieneCandado = true;
                                        break;
                                    }
                                }
                            }

                            if (!yaTieneCandado) {
                                com.hrms.vacaciones.model.AuditoriaSaldo candadoEspejo = new com.hrms.vacaciones.model.AuditoriaSaldo();

                                candadoEspejo.setNominaEmpleado(nominaId);
                                candadoEspejo.setComentarioJustificacion(tagAniversario);
                                candadoEspejo.setFechaMovimiento(LocalDateTime.now());
                                candadoEspejo.setDiasModificados(0.0);
                                candadoEspejo.setRealizadoPor("1555");

                                auditoriaSaldoRepository.save(candadoEspejo);
                                log.info("🔒 Escudo Anti-Duplicados sembrado para Nómina {}: {}", nominaId, tagAniversario);
                            }
                        }
                    }

                    actualizados++;
                } else {
                    log.error("❌ ERROR CRÍTICO Semillero: La nómina entera [{}] SÍ se leyó bien, pero NO EXISTE en la base de datos de Empleados. Se omitió su saldo.", nominaId);
                }
            }
            log.info("📊 Proceso de Semillero Terminado. Total de saldos inyectados: {}", actualizados);
        } catch (Exception e) {
            throw new RuntimeException("Falla en semillero contable: " + e.getMessage(), e);
        }
    }

    // ✨ TRADUCTOR INTELIGENTE DE RRHH (Normaliza la Base de Datos)
    private String normalizarTipoEmpleado(String tipoCrudo) {
        if (tipoCrudo == null || tipoCrudo.trim().isEmpty()) {
            return "SINDICALIZADO"; // Fallback por defecto
        }
        String t = tipoCrudo.trim().toUpperCase();

        // Si RH sube BCD o algo con "SIND", lo forzamos a SINDICALIZADO
        if (t.equals("BCD") || t.contains("SIND")) {
            return "SINDICALIZADO";
        }
        // Si RH sube el formato viejo o el nuevo, lo unificamos
        if (t.equals("EXTRANJERO") || t.equals("WC-EXTRANJERO")) {
            return "WC-EXTRANJERO";
        }

        // Pasa limpios los BCI, WC, y ADMINISTRATIVO
        return t;
    }

    // --- MÉTODOS PARSEADORES SEGUROS PARA APACHE POI ---
    private String getStringValue(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().trim();
        if (cell.getCellType() == CellType.NUMERIC) {
            long val = (long) cell.getNumericCellValue();
            if (val == cell.getNumericCellValue()) return String.valueOf(val);
            return String.valueOf(cell.getNumericCellValue());
        }
        return null;
    }

    private Integer getIntegerValue(Cell cell) {
        if (cell == null) return null;

        if (cell.getCellType() == CellType.NUMERIC) {
            return (int) cell.getNumericCellValue();
        }

        if (cell.getCellType() == CellType.STRING) {
            try {
                String txt = cell.getStringCellValue().replaceAll("[\\s\\u00A0]+", "").replace(",", "");

                if (txt.contains(".")) {
                    txt = txt.substring(0, txt.indexOf("."));
                }

                return txt.isEmpty() ? null : Integer.parseInt(txt);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private Double getNumericValue(Cell cell) {
        if (cell == null) return null;

        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        else if (cell.getCellType() == CellType.FORMULA) {
            try {
                return cell.getNumericCellValue();
            } catch (Exception e) {
                return null;
            }
        }
        else if (cell.getCellType() == CellType.STRING) {
            try {
                String textoLimpio = cell.getStringCellValue().trim().replace(",", "");
                return Double.parseDouble(textoLimpio);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private LocalDate getLocalDateValue(Cell cell) {
        if (cell == null) return null;

        if (cell.getCellType() == CellType.NUMERIC) {
            try {
                return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            } catch (Exception e) {
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                }
            }
        }

        if (cell.getCellType() == CellType.STRING) {
            String txt = cell.getStringCellValue().trim();
            if (txt.isEmpty()) return null;
            try {
                if (txt.contains("/")) {
                    return LocalDate.parse(txt, DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                } else if (txt.contains("-")) {
                    return LocalDate.parse(txt);
                }
            } catch (Exception e) {
                try {
                    double numerico = Double.parseDouble(txt);
                    return DateUtil.getJavaDate(numerico).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                } catch (Exception ignored) {}
                log.error("Error crítico de parseo sobre fecha: {}", txt);
            }
        }
        return null;
    }
}
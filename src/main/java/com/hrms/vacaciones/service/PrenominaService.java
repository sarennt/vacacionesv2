package com.hrms.vacaciones.service;

import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.model.SolicitudVacaciones;
import com.hrms.vacaciones.repository.EmpleadoRepository;
import com.hrms.vacaciones.repository.SolicitudVacacionesRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PrenominaService {

    private final EmpleadoRepository empleadoRepository;
    private final SolicitudVacacionesRepository solicitudVacacionesRepository;

    public ByteArrayInputStream generarExcelPrenomina(Integer jefeId, LocalDate fechaLunes) {
        Empleado jefe = empleadoRepository.findById(jefeId)
                .orElseThrow(() -> new IllegalArgumentException("Jefe no encontrado con ID: " + jefeId));

        List<Empleado> empleadosFiltrados = obtenerEmpleadosFiltradosPorPermisos(jefe);

        LocalDate fechaDomingo = fechaLunes.plusDays(6);
        List<SolicitudVacaciones> solicitudes = solicitudVacacionesRepository
                .findByEstatusAndFechaInicioLessThanEqualAndFechaFinGreaterThanEqual("APROBADO", fechaDomingo,
                        fechaLunes);

        Map<Integer, List<SolicitudVacaciones>> solicitudesPorEmpleado = solicitudes.stream()
                .collect(Collectors.groupingBy(s -> s.getEmpleado().getNomina()));

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Prenómina");

            // Estilos
            CellStyle headerStyle = crearEstiloEncabezado(workbook);
            CellStyle dataStyle = crearEstiloDatos(workbook);

            // Generar 7 fechas de la semana
            List<LocalDate> fechasSemana = new ArrayList<>();
            for (int i = 0; i <= 6; i++) {
                fechasSemana.add(fechaLunes.plusDays(i));
            }

            // Fila 1: Encabezados
            Row headerRow = sheet.createRow(0);
            Cell cellIndex = headerRow.createCell(0);
            cellIndex.setCellValue("NO.");
            cellIndex.setCellStyle(headerStyle);

            Cell cellName = headerRow.createCell(1);
            cellName.setCellValue("NOMBRE");
            cellName.setCellStyle(headerStyle);

            for (int i = 0; i < fechasSemana.size(); i++) {
                Cell dateCell = headerRow.createCell(2 + i);
                dateCell.setCellValue(fechasSemana.get(i).toString());
                dateCell.setCellStyle(headerStyle);
            }

            Cell cellAutorizado = headerRow.createCell(2 + fechasSemana.size());
            cellAutorizado.setCellValue("AUTORIZADO POR");
            cellAutorizado.setCellStyle(headerStyle);

            // Poblar Datos
            int rowIndex = 1;
            for (int i = 0; i < empleadosFiltrados.size(); i++) {
                Empleado emp = empleadosFiltrados.get(i);
                Row row = sheet.createRow(rowIndex++);

                Cell c0 = row.createCell(0);
                c0.setCellValue(i + 1);
                c0.setCellStyle(dataStyle);

                Cell c1 = row.createCell(1);
                c1.setCellValue(emp.getNombreCompleto());
                c1.setCellStyle(dataStyle);

                List<SolicitudVacaciones> solsEmp = solicitudesPorEmpleado.getOrDefault(emp.getNomina(),
                        Collections.emptyList());

                String autorizadoPor = "";

                for (int colDate = 0; colDate < fechasSemana.size(); colDate++) {
                    LocalDate currentDay = fechasSemana.get(colDate);
                    Cell cDate = row.createCell(2 + colDate);

                    boolean tieneVacacion = solsEmp.stream().anyMatch(
                            s -> !currentDay.isBefore(s.getFechaInicio()) && !currentDay.isAfter(s.getFechaFin()));

                    if (tieneVacacion) {
                        cDate.setCellValue("V");
                        if (autorizadoPor.isEmpty()) {
                            // Encontrar la solicitud que cubre este día para obtener el autorizador
                            SolicitudVacaciones s = solsEmp.stream()
                                    .filter(sol -> !currentDay.isBefore(sol.getFechaInicio())
                                            && !currentDay.isAfter(sol.getFechaFin()))
                                    .findFirst().orElse(null);

                            if (s != null) {
                                if (s.getAprobadoPor() != null && s.getAprobadoPor().getNombreCompleto() != null) {
                                    autorizadoPor = s.getAprobadoPor().getNombreCompleto().split(" ")[0];
                                } else {
                                    autorizadoPor = "DEFAULT";
                                }
                            }
                        }
                    } else {
                        cDate.setCellValue("");
                    }
                    cDate.setCellStyle(dataStyle);
                }

                Cell cAutorizado = row.createCell(2 + fechasSemana.size());
                cAutorizado.setCellValue(autorizadoPor.isEmpty() ? "" : autorizadoPor);
                cAutorizado.setCellStyle(dataStyle);
            }

            sheet.autoSizeColumn(1);

            workbook.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Error al generar el archivo Excel de prenómina", e);
        }
    }

    private List<Empleado> obtenerEmpleadosFiltradosPorPermisos(Empleado jefe) {
        String rolJefe = jefe.getRol() != null ? jefe.getRol().getNombreRol().toUpperCase() : "";
        List<Empleado> todos = empleadoRepository.findAll();

        if (rolJefe.contains("ADMIN") || rolJefe.contains("RH") || rolJefe.contains("HR")) {
            return todos;
        }

        // Si es Supervisor / Shift Leader u otro rol, filtrar por área o work center
        Integer jefeAreaId = jefe.getArea() != null ? jefe.getArea().getId() : null;
        Integer jefeWCId = jefe.getWorkCenter() != null ? jefe.getWorkCenter().getId() : null;

        return todos.stream().filter(emp -> {
            boolean mismaArea = emp.getArea() != null && emp.getArea().getId().equals(jefeAreaId);
            boolean mismoWC = emp.getWorkCenter() != null && emp.getWorkCenter().getId().equals(jefeWCId);
            boolean subordinadoDirecto = emp.getJefeDirecto() != null
                    && emp.getJefeDirecto().getNomina().equals(jefe.getNomina());

            if (!mismaArea && !mismoWC && !subordinadoDirecto) {
                return false;
            }

            boolean esConfidencial = emp.getEsPerfilConfidencial() != null && emp.getEsPerfilConfidencial();
            if (esConfidencial && !subordinadoDirecto) {
                return false; // Oculta a confidenciales si no es subordinado directo
            }

            return true;
        }).collect(Collectors.toList());
    }

    private CellStyle crearEstiloEncabezado(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle crearEstiloDatos(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }
}

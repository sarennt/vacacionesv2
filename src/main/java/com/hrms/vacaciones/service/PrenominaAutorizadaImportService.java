package com.hrms.vacaciones.service;

import com.hrms.vacaciones.model.PrenominaAutorizada;
import com.hrms.vacaciones.repository.PrenominaAutorizadaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrenominaAutorizadaImportService {

    private final PrenominaAutorizadaRepository repository;

    @Transactional
    public void importarPrenominaPulida(MultipartFile file) {
        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);

            // 1. Extraer la Semana Contable de la Fila 0 (Celda B1 / Índice 1)
            Row rowZero = sheet.getRow(0);
            String semana = getStringValue(rowZero.getCell(1, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
            if (semana == null || semana.isEmpty()) {
                throw new RuntimeException("No se encontró el número de semana (WK) en la fila 1.");
            }

            // 2. Limpiamos la base de datos para esa semana (evita duplicados si resubes el archivo)
            repository.deleteBySemanaContable(semana);

            // 3. Empezamos a iterar desde la Fila 2 (brincando la cabecera verde de tu imagen)
            Iterator<Row> rowIterator = sheet.iterator();
            if (rowIterator.hasNext()) rowIterator.next(); // Brinca Fila 0 (WK)
            if (rowIterator.hasNext()) rowIterator.next(); // Brinca Fila 1 (Cabeceras)

            List<PrenominaAutorizada> lote = new ArrayList<>();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();

                Integer nomina = getIntegerValue(row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                if (nomina == null || nomina == 0) continue; // Fila vacía

                PrenominaAutorizada registro = new PrenominaAutorizada();
                registro.setSemanaContable(semana);
                registro.setNomina(nomina);
                registro.setNombre(getStringValue(row.getCell(1, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));
                registro.setTurno(getStringValue(row.getCell(2, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL))); // Capta el #N/A directo

                // Días y TE (Lunes a Domingo)
                registro.setAsisLun(getStringValue(row.getCell(3, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));
                registro.setTeLun(getNumericValue(row.getCell(4, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));

                registro.setAsisMar(getStringValue(row.getCell(5, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));
                registro.setTeMar(getNumericValue(row.getCell(6, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));

                registro.setAsisMie(getStringValue(row.getCell(7, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));
                registro.setTeMie(getNumericValue(row.getCell(8, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));

                registro.setAsisJue(getStringValue(row.getCell(9, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));
                registro.setTeJue(getNumericValue(row.getCell(10, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));

                registro.setAsisVie(getStringValue(row.getCell(11, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));
                registro.setTeVie(getNumericValue(row.getCell(12, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));

                registro.setAsisSab(getStringValue(row.getCell(13, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));
                registro.setTeSab(getNumericValue(row.getCell(14, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));

                registro.setAsisDom(getStringValue(row.getCell(15, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));
                registro.setTeDom(getNumericValue(row.getCell(16, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));

                // Totales y Observaciones
                registro.setTotalFaltas(getNumericValue(row.getCell(17, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));
                registro.setTotalTe(getNumericValue(row.getCell(18, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));
                registro.setObservaciones(getStringValue(row.getCell(19, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));

                lote.add(registro);
            }

            repository.saveAll(lote);
            log.info("✅ Prenómina pulida inyectada con éxito para la Semana {}", semana);

        } catch (Exception e) {
            throw new RuntimeException("Error procesando el Excel pulido: " + e.getMessage(), e);
        }
    }

    // --- PARSEADORES SEGUROS (Mismos que usas en tus otros importadores) ---
    private String getStringValue(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().trim();
        if (cell.getCellType() == CellType.NUMERIC) {
            long val = (long) cell.getNumericCellValue();
            if (val == cell.getNumericCellValue()) return String.valueOf(val);
            return String.valueOf(cell.getNumericCellValue());
        }
        if (cell.getCellType() == CellType.ERROR) return "#N/A";
        return null;
    }

    private Integer getIntegerValue(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) return (int) cell.getNumericCellValue();
        if (cell.getCellType() == CellType.STRING) {
            try {
                String txt = cell.getStringCellValue().replaceAll("[\\s\\u00A0]+", "").replace(",", "");
                if (txt.contains(".")) txt = txt.substring(0, txt.indexOf("."));
                return txt.isEmpty() ? null : Integer.parseInt(txt);
            } catch (Exception e) { return null; }
        }
        return null;
    }

    private Double getNumericValue(Cell cell) {
        if (cell == null) return 0.0;
        if (cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA) {
            try { return cell.getNumericCellValue(); } catch (Exception e) { return 0.0; }
        }
        if (cell.getCellType() == CellType.STRING) {
            try { return Double.parseDouble(cell.getStringCellValue().trim().replace(",", "")); }
            catch (Exception e) { return 0.0; }
        }
        return 0.0;
    }
}
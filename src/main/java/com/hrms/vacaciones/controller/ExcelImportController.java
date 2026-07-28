package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.service.ExcelImportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/import")
public class ExcelImportController {

    private final ExcelImportService excelImportService;

    public ExcelImportController(ExcelImportService excelImportService) {
        this.excelImportService = excelImportService;
    }

    @PostMapping("/catalogo")
    public ResponseEntity<String> importarCatalogo(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Error: Archivo vacío.");
        try {
            excelImportService.importarCatalogo(file);
            return ResponseEntity.ok("Catálogo de CC y WC importado correctamente.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @PostMapping("/jefes")
    public ResponseEntity<String> importarJefes(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Error: Archivo vacío.");
        try {
            excelImportService.importarJefes(file);
            return ResponseEntity.ok("Organigrama de Jefes importado correctamente.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @PostMapping("/empleados")
    public ResponseEntity<String> importarEmpleados(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Error: Archivo vacío.");
        try {
            excelImportService.importarEmpleados(file);
            return ResponseEntity.ok("Estructura de Empleados importada correctamente.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @PostMapping("/saldos-iniciales")
    public ResponseEntity<String> importarSaldosIniciales(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Error: Archivo vacío.");
        try {
            excelImportService.importarSaldosIniciales(file);
            return ResponseEntity.ok("Semillero de Saldos inicializado correctamente.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @PostMapping("/festivos-masivos")
    public ResponseEntity<String> importarFestivosMasivos(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Error: El archivo está vacío.");
        }
        try {
            excelImportService.importarFestivosMasivos(file);
            return ResponseEntity.ok("Catálogo de Días Festivos Anuales importado correctamente.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }
}
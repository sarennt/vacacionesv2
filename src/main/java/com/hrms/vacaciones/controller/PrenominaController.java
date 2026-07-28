package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.service.PrenominaService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/prenomina")
@RequiredArgsConstructor
public class PrenominaController {

    private final PrenominaService prenominaService;

    @GetMapping("/descargar")
    public ResponseEntity<InputStreamResource> descargarPrenomina(
            @RequestParam("jefeId") Integer jefeId,
            @RequestParam("fechaLunes") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaLunes) {

        ByteArrayInputStream stream = prenominaService.generarExcelPrenomina(jefeId, fechaLunes);
        InputStreamResource file = new InputStreamResource(stream);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=Prenomina_Vacaciones.xlsx")
                .contentType(
                        MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }
}

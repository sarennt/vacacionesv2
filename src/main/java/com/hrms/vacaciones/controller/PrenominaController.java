package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.model.PrenominaAutorizada;
import com.hrms.vacaciones.repository.PrenominaAutorizadaRepository;
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

import jakarta.servlet.http.HttpSession;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/prenomina")
@RequiredArgsConstructor
public class PrenominaController {

    private final PrenominaService prenominaService;
    // ✨ INYECCIÓN DEL REPOSITORIO (Lombok se encarga de instanciarlo)
    private final PrenominaAutorizadaRepository prenominaAutorizadaRepository;

    @GetMapping("/descargar")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> descargarPrenomina(
            @RequestParam("jefeId") Integer jefeId,
            @RequestParam("fechaLunes") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate fechaLunes) {

        java.io.ByteArrayInputStream stream = prenominaService.generarExcelPrenomina(jefeId, fechaLunes);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=Prenomina_Vacaciones.xlsx")
                .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(new org.springframework.core.io.InputStreamResource(stream));
    }
}
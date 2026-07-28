package com.hrms.vacaciones.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolicitudPermisoRangoRequestDTO {
    private Integer empleadoNomina;
    private String codigoPermiso; // HO, PT, VISITA, etc.
    // 🎯 Shield anti-error 400 para fechas
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate fechaInicio;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate fechaFin;
    private Integer rolDescansoId;
    private Integer jefeAutorizadorNomina; // Puede llegar null si seleccionan "Sin jefe en planta"
    private String comentarios;
    private MultipartFile comprobante; // Cachador del archivo PDF o Imagen
}
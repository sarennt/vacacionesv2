package com.hrms.vacaciones.dto;

import lombok.*;
import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolicitudPermisoRequestDTO {
    private Integer empleadoNomina;
    private String codigoPermiso; // V, TXT, HO, C, P, TET

    // El día de la falta (Se deja para compatibilidad con código viejo de 1 solo día)
    private LocalDate fechaIncidencia;

    // 🎯 NUEVO: El salvavidas para atrapar los múltiples días de paro del TXT Colectivo
    private List<LocalDate> fechasIncidencia;

    private String justificacionSupervisor;

    // 🎯 Captura del jefe directo seleccionado en la interfaz
    private Integer jefeAutorizadorNomina;

    // 🎯 EL SALVADOR DE LA COMPILACIÓN: Pasamos el turno específico de la falta
    private String nombreTurno; // "1ro", "2do", "3ro", "Mixto"

    private List<FilaPagoTxtDTO> desglosesPago;

    private java.math.BigDecimal horasPermiso;
    private Boolean esPorHoras;
}